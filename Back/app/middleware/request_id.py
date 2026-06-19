"""요청마다 추적 가능한 request id를 부여하고 응답 헤더에 포함한다."""

import re
from uuid import uuid4

REQUEST_ID_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,128}$")


def get_request_id(value):
    if value and REQUEST_ID_PATTERN.fullmatch(value):
        return value
    return str(uuid4())


async def add_request_id(request, call_next):
    request_id = get_request_id(request.headers.get("X-Request-ID"))
    request.state.request_id = request_id
    response = await call_next(request)
    response.headers["X-Request-ID"] = request_id
    return response
