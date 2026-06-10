from contextlib import contextmanager
from queue import Empty, LifoQueue
from threading import Lock

import pymysql
from pymysql.cursors import DictCursor

from ..config import DB_CONFIG, DB_POOL_SIZE, DB_POOL_TIMEOUT_SECONDS


def _create_connection(config):
    return pymysql.connect(
        **config,
        cursorclass=DictCursor,
        autocommit=False,
        charset="utf8mb4",
        connect_timeout=5,
        read_timeout=30,
        write_timeout=30,
    )


class _PooledConnection:
    def __init__(self, pool, connection):
        self._pool = pool
        self._connection = connection
        self._closed = False

    def close(self):
        if not self._closed:
            self._closed = True
            self._pool.release(self._connection)

    def __getattr__(self, name):
        return getattr(self._connection, name)


class _ConnectionPool:
    def __init__(self, config, size, timeout):
        self._config = config
        self._size = size
        self._timeout = timeout
        self._available = LifoQueue(maxsize=size)
        self._created = 0
        self._lock = Lock()

    def acquire(self):
        try:
            connection = self._available.get_nowait()
        except Empty:
            with self._lock:
                if self._created < self._size:
                    connection = _create_connection(self._config)
                    self._created += 1
                else:
                    connection = None
            if connection is None:
                connection = self._available.get(timeout=self._timeout)
        try:
            connection.ping(reconnect=True)
        except Exception:
            self._discard(connection)
            raise
        return _PooledConnection(self, connection)

    def release(self, connection):
        try:
            connection.rollback()
            self._available.put_nowait(connection)
        except Exception:
            self._discard(connection)

    def _discard(self, connection):
        try:
            connection.close()
        finally:
            with self._lock:
                self._created = max(0, self._created - 1)


_runtime_pool = _ConnectionPool(DB_CONFIG, DB_POOL_SIZE, DB_POOL_TIMEOUT_SECONDS)


def get_connection(config=None):
    if config is not None:
        return _create_connection(config)
    return _runtime_pool.acquire()


@contextmanager
def transaction(config=None):
    connection = get_connection(config)
    try:
        connection.begin()
        yield connection
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


@contextmanager
def advisory_lock(name, timeout_seconds=10):
    # MySQL advisory locks are connection-scoped. Keep them off the runtime pool
    # so a slow external model request cannot exhaust request DB connections.
    connection = _create_connection(DB_CONFIG)
    acquired = False
    try:
        with connection.cursor() as cursor:
            cursor.execute("SELECT GET_LOCK(%s, %s) AS acquired", (name, timeout_seconds))
            acquired = cursor.fetchone()["acquired"] == 1
        yield acquired
    finally:
        if acquired:
            try:
                with connection.cursor() as cursor:
                    cursor.execute("SELECT RELEASE_LOCK(%s)", (name,))
            except Exception:
                pass
        connection.close()


def ping_database():
    connection = get_connection()
    try:
        connection.ping(reconnect=True)
    finally:
        connection.close()
