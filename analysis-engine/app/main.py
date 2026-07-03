import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.core.logging_config import configure_logging

configure_logging()

from app.core import model_registry
from app.api.basic_analysis import router as basic_analysis_router

logger = logging.getLogger("analysis-engine")


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        model_registry.preload_all()
    except Exception:
        logger.exception("모델 프리로딩에 실패했습니다.")
        raise

    yield


app = FastAPI(title="Analysis Engine", lifespan=lifespan)


@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "service": "analysis-engine"
    }


app.include_router(basic_analysis_router)
