from fastapi.responses import JSONResponse

from ..config import MAX_UPLOAD_MB


async def reject_oversized_upload(request, call_next):
    if request.method == "POST" and request.url.path == "/analyze/upload":
        content_length = request.headers.get("content-length")
        if content_length:
            try:
                maximum_bytes = MAX_UPLOAD_MB * 1024 * 1024 + 1024 * 1024
                if int(content_length) > maximum_bytes:
                    return JSONResponse(
                        status_code=413,
                        content={"detail": f"파일 크기는 {MAX_UPLOAD_MB}MB 이하여야 합니다."},
                    )
            except ValueError:
                pass
    return await call_next(request)
