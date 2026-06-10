import unittest
from unittest.mock import MagicMock, patch

from Back.app.services.database import _ConnectionPool, advisory_lock


class DatabasePoolTest(unittest.TestCase):
    @patch("Back.app.services.database._create_connection")
    def test_reuses_released_connection(self, create_connection):
        raw_connection = MagicMock()
        create_connection.return_value = raw_connection
        pool = _ConnectionPool({"host": "database"}, size=1, timeout=0.1)

        first = pool.acquire()
        first.close()
        second = pool.acquire()
        second.close()

        create_connection.assert_called_once()
        self.assertGreaterEqual(raw_connection.ping.call_count, 2)
        self.assertGreaterEqual(raw_connection.rollback.call_count, 2)

    @patch("Back.app.services.database._create_connection")
    def test_advisory_lock_uses_dedicated_connection_and_releases_it(self, create_connection):
        raw_connection = MagicMock()
        cursor = raw_connection.cursor.return_value.__enter__.return_value
        cursor.fetchone.return_value = {"acquired": 1}
        create_connection.return_value = raw_connection

        with advisory_lock("conversation-1", 3) as acquired:
            self.assertTrue(acquired)

        self.assertEqual(cursor.execute.call_args_list[0].args, ("SELECT GET_LOCK(%s, %s) AS acquired", ("conversation-1", 3)))
        self.assertEqual(cursor.execute.call_args_list[1].args, ("SELECT RELEASE_LOCK(%s)", ("conversation-1",)))
        raw_connection.close.assert_called_once()


if __name__ == "__main__":
    unittest.main()
