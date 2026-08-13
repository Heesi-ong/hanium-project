import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.core.logging_config import configure_logging

# 설정을 import 시 읽지 않고 lifespan에서 한 번 검증·고정하기 위해 애플리케이션
# 모듈 import와 로깅 초기화를 분리합니다. E402는 이 의도된 초기화 순서를 위한 예외입니다.
from app.api.video_llm_analysis import router as video_llm_analysis_router  # noqa: E402
from app.api.readiness import router as readiness_router  # noqa: E402
from app.core.settings import (  # noqa: E402
    VideoLlmSettings,
    clear_settings,
    install_settings,
)
from app.services.nvidia_runtime import configure_runtime  # noqa: E402

logger = logging.getLogger("video-llm-engine")


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        settings = VideoLlmSettings.from_env()
        install_settings(settings)
        configure_logging(settings.log_dir)
        configure_runtime(settings)
    except Exception:
        logger.exception("Video LLM 설정이 올바르지 않습니다.")
        clear_settings()
        raise

    try:
        yield
    finally:
        clear_settings()


app = FastAPI(title="Video LLM Engine", lifespan=lifespan)


@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "service": "video-llm-engine"
    }


app.include_router(video_llm_analysis_router)
app.include_router(readiness_router)
