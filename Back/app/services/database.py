from contextlib import contextmanager

import pymysql
from pymysql.cursors import DictCursor

from ..config import DB_CONFIG


def get_connection():
    return pymysql.connect(
        **DB_CONFIG,
        cursorclass=DictCursor,
        autocommit=False,
        charset="utf8mb4",
        connect_timeout=5,
        read_timeout=30,
        write_timeout=30,
    )


@contextmanager
def transaction():
    connection = get_connection()
    try:
        connection.begin()
        yield connection
        connection.commit()
    except Exception:
        connection.rollback()
        raise
    finally:
        connection.close()


def ping_database():
    connection = get_connection()
    try:
        connection.ping(reconnect=True)
    finally:
        connection.close()
