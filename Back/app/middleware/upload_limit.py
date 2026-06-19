"""multipart 파싱 전에 Content-Length와 수신 바이트 기준으로 업로드 크기를 제한한다."""

from fastapi.responses import JSONResponse

from ..config import MAX_UPLOAD_MB


class PayloadTooLarge(Exception):
    pass


class UploadSizeLimitMiddleware:
    def __init__(self, app):
        self.app = app
        self.maximum_bytes = MAX_UPLOAD_MB * 1024 * 1024 + 1024 * 1024

    async def __call__(self, scope, receive, send):
        if scope["type"] != "http" or scope["method"] != "POST" or scope["path"] != "/analyze/upload":
            await self.app(scope, receive, send)
            return

        headers = dict(scope.get("headers", []))
        content_length = headers.get(b"content-length")
        if content_length:
            try:
                if int(content_length) > self.maximum_bytes:
                    await self._reject(scope, receive, send)
                    return
            except ValueError:
                response = JSONResponse(status_code=400, content={"detail": "올바르지 않은 Content-Length입니다."})
                await response(scope, receive, send)
                return

        received_bytes = 0

        async def limited_receive():
            nonlocal received_bytes
            message = await receive()
            if message["type"] == "http.request":
                received_bytes += len(message.get("body", b""))
                if received_bytes > self.maximum_bytes:
                    raise PayloadTooLarge
            return message

        try:
            await self.app(scope, limited_receive, send)
        except PayloadTooLarge:
            await self._reject(scope, receive, send)

    async def _reject(self, scope, receive, send):
        response = JSONResponse(
            status_code=413,
            content={"detail": f"파일 크기는 {MAX_UPLOAD_MB}MB 이하여야 합니다."},
        )
        await response(scope, receive, send)
