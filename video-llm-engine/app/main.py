import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.core.logging_config import configure_logging

configure_logging()

from app.api.video_llm_analysis import (
    resolve_video_max_size_bytes,
    router as video_llm_analysis_router,
)
from app.api.readiness import router as readiness_router

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
