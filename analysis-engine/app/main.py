import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.core.logging_config import configure_logging

# 설정을 import 시 읽지 않고 lifespan에서 한 번 검증·고정하기 위해 애플리케이션
# 모듈 import와 로깅 초기화를 분리합니다. E402는 이 의도된 초기화 순서를 위한 예외입니다.
from app.core import model_registry  # noqa: E402
from app.core.settings import (  # noqa: E402
    AnalysisEngineSettings,
    clear_settings,
    install_settings,
)
from app.api.basic_analysis import (  # noqa: E402
    router as basic_analysis_router,
)
from app.api.readiness import router as readiness_router  # noqa: E402

logger = logging.getLogger("analysis-engine")


@asynccontextmanager
async def lifespan(app: FastAPI):
    try:
        settings = AnalysisEngineSettings.from_env()
        install_settings(settings)
        configure_logging(settings.log_dir)
        model_registry.configure_pool_sizes(settings)
        model_registry.preload_all()
    except Exception:
        logger.exception("Analysis Engine 설정 검증 또는 모델 프리로딩에 실패했습니다.")
        model_registry.close_all()
        clear_settings()
        raise

    try:
        yield
    finally:
        model_registry.close_all()
        clear_settings()
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
