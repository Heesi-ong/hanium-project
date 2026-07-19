import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.core.logging_config import configure_logging

configure_logging()

# 다른 애플리케이션 모듈은 로깅 핸들러가 이미 붙은 뒤에 import되도록 의도적으로 여기에
# 둡니다(파일 맨 위로 옮기지 않음). ruff의 E402(모듈 상단이 아닌 import)는 이 순서를
# 위한 의도된 예외라 노란색 경고 대신 명시적으로 무시합니다.
from app.api.video_llm_analysis import (  # noqa: E402
    resolve_video_max_size_bytes,
    router as video_llm_analysis_router,
)
from app.api.readiness import router as readiness_router  # noqa: E402

logger = logging.getLogger("video-llm-engine")


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        resolve_video_max_size_bytes()
    except Exception:
        logger.exception("Video LLM 영상 크기 제한 설정이 올바르지 않습니다.")
        raise

    yield


app = FastAPI(title="Video LLM Engine", lifespan=lifespan)


@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "service": "video-llm-engine"
    }


app.include_router(video_llm_analysis_router)
app.include_router(readiness_router)
