import unittest
from unittest.mock import Mock, patch

from fastapi import HTTPException

from Back.app.services.ollama_service import chat_with_ollama


class OllamaServiceTests(unittest.TestCase):
    @patch("Back.app.services.ollama_service.requests.post")
    def test_maps_ollama_content_and_tokens(self, post):
        response = Mock(ok=True)
        response.json.return_value = {
            "message": {"content": "안녕하세요."},
            "prompt_eval_count": 12,
            "eval_count": 4,
        }
        post.return_value = response

        result = chat_with_ollama([{"role": "user", "content": "안녕"}])

        self.assertEqual(result["content"], "안녕하세요.")
        self.assertEqual(result["total_tokens"], 16)
        self.assertFalse(post.call_args.kwargs["json"]["stream"])

    @patch("Back.app.services.ollama_service.requests.post")
    def test_maps_connection_error_to_503(self, post):
        import requests

        post.side_effect = requests.ConnectionError()
        with self.assertRaises(HTTPException) as raised:
            chat_with_ollama([])
        self.assertEqual(raised.exception.status_code, 503)

    @patch("Back.app.services.ollama_service.requests.post")
    def test_requests_structured_json_format(self, post):
        response = Mock(ok=True)
        response.json.return_value = {
            "message": {"content": "{}"},
            "prompt_eval_count": 1,
            "eval_count": 1,
        }
        post.return_value = response

        chat_with_ollama([], response_format="json", options={"temperature": 0.2}, think=False)

        self.assertEqual(post.call_args.kwargs["json"]["format"], "json")
        self.assertEqual(post.call_args.kwargs["json"]["options"]["temperature"], 0.2)
        self.assertFalse(post.call_args.kwargs["json"]["think"])


if __name__ == "__main__":
    unittest.main()
