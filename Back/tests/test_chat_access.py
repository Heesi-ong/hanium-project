import unittest
from unittest.mock import patch

from fastapi import HTTPException

from Back.app.routers.chat import ConversationRequest, _decode_cursor, _encode_cursor, create_conversation, messages


class FakeCursor:
    def __init__(self):
        self.executions = []

    def execute(self, query, params=None):
        self.executions.append((query, params))

    def fetchone(self):
        return None

    def __enter__(self):
        return self

    def __exit__(self, *_args):
        return False


class FakeConnection:
    def __init__(self):
        self.cursor_instance = FakeCursor()
        self.closed = False

    def cursor(self):
        return self.cursor_instance

    def close(self):
        self.closed = True


class ChatAccessTests(unittest.TestCase):
    def test_cursor_round_trip(self):
        cursor = _encode_cursor({"updated_at": "2026-06-11 10:00:00", "id": 8})
        self.assertEqual(
            _decode_cursor(cursor, ("updated_at", "id")),
            {"updated_at": "2026-06-11 10:00:00", "id": 8},
        )

    def test_invalid_cursor_is_ignored(self):
        self.assertIsNone(_decode_cursor("not-a-cursor", ("id",)))

    @patch("Back.app.routers.chat.get_connection")
    def test_other_users_conversation_messages_are_hidden(self, get_connection):
        connection = FakeConnection()
        get_connection.return_value = connection

        with self.assertRaises(HTTPException) as raised:
            messages(123, limit=50, offset=0, user={"id": 7})

        self.assertEqual(raised.exception.status_code, 404)
        self.assertEqual(connection.cursor_instance.executions[0][1], (123, 7))
        self.assertTrue(connection.closed)

    @patch("Back.app.routers.chat.get_user_job", return_value=None)
    def test_other_users_analysis_cannot_be_attached_to_chat(self, _job):
        with self.assertRaises(HTTPException) as raised:
            create_conversation(
                ConversationRequest(title="발표 코칭", analysis_result_id="other-result"),
                user={"id": 7},
            )

        self.assertEqual(raised.exception.status_code, 404)


if __name__ == "__main__":
    unittest.main()
