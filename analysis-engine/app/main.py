import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.core.logging_config import configure_logging

configure_logging()

from app.core import model_registry
from app.api.basic_analysis import (
    resolve_analysis_engine_max_video_size_bytes,
    router as basic_analysis_router,
)
from app.api.readiness import router as readiness_router

logger = logging.getLogger("analysis-engine")


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        resolve_analysis_engine_max_video_size_bytes()
        model_registry.preload_all()
    except Exception:
        logger.exception("모델 프리로딩에 실패했습니다.")
        model_registry.close_all()
        raise

    try:
        yield
    finally:
        model_registry.close_all()
        logger.info("모델 풀을 정상 종료했습니다.")


app = FastAPI(title="Analysis Engine", lifespan=lifespan)


@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "service": "analysis-engine"
    }


app.include_router(basic_analysis_router)
app.include_router(readiness_router)
