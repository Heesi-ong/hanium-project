import unittest

from Back.app.middleware.upload_limit import UploadSizeLimitMiddleware


class UploadLimitTest(unittest.IsolatedAsyncioTestCase):
    async def test_streamed_upload_without_content_length_is_limited(self):
        async def app(_scope, receive, _send):
            await receive()
            await receive()

        middleware = UploadSizeLimitMiddleware(app)
        middleware.maximum_bytes = 5
        messages = [
            {"type": "http.request", "body": b"123", "more_body": True},
            {"type": "http.request", "body": b"456", "more_body": False},
        ]
        sent = []

        async def receive():
            return messages.pop(0)

        async def send(message):
            sent.append(message)

        await middleware(
            {"type": "http", "method": "POST", "path": "/analyze/upload", "headers": []},
            receive,
            send,
        )

        self.assertEqual(sent[0]["status"], 413)

    async def test_invalid_content_length_is_rejected(self):
        middleware = UploadSizeLimitMiddleware(lambda *_args: None)
        sent = []

        async def receive():
            return {"type": "http.request", "body": b"", "more_body": False}

        async def send(message):
            sent.append(message)

        await middleware(
            {
                "type": "http",
                "method": "POST",
                "path": "/analyze/upload",
                "headers": [(b"content-length", b"invalid")],
            },
            receive,
            send,
        )

        self.assertEqual(sent[0]["status"], 400)


if __name__ == "__main__":
    unittest.main()
