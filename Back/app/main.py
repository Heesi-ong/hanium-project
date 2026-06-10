import logging
from contextlib import asynccontextmanager

from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import JSONResponse

from .config import ALLOWED_ORIGINS
from .middleware import add_request_id
from .middleware.upload_limit import reject_oversized_upload
from .routers import admin, analyze, auth, chat
from .services.analysis_jobs import recover_interrupted_jobs
from .services.chat_recovery import recover_stale_pending_messages
from .services.database import ping_database
from .services.readiness import get_readiness_status
from .workers.analysis_worker import analysis_worker_manager

logger = logging.getLogger(__name__)


@asynccontextmanager
async def lifespan(_app):
    try:
        recovery = recover_interrupted_jobs()
        if recovery["requeued"] or recovery["failed"]:
            logger.warning("Recovered analysis jobs: %s", recovery)
    except Exception:
        logger.exception("Failed to recover interrupted analysis jobs")
    try:
        recovered_messages = recover_stale_pending_messages()
        if recovered_messages:
            logger.warning("Marked %s interrupted chat messages as failed", recovered_messages)
    except Exception:
        logger.exception("Failed to recover interrupted chat messages")
    try:
        analysis_worker_manager.start()
    except Exception:
        logger.exception("Failed to start analysis workers")
    yield
    analysis_worker_manager.stop()


app = FastAPI(lifespan=lifespan)
app.middleware("http")(add_request_id)
app.middleware("http")(reject_oversized_upload)

app.add_middleware(
    CORSMiddleware,
    allow_origins=ALLOWED_ORIGINS,
    allow_credentials=True,
    allow_methods=["*"],
    allow_headers=["*"],
)

app.include_router(analyze.router)
app.include_router(auth.router)
app.include_router(chat.router)
app.include_router(admin.router)

@app.get("/")
def home():
    return {
        "message": "FastAPI backend running"
    }


@app.get("/health")
def health_check():
    ping_database()
    return {
        "status": "ok",
        "database": "connected"
    }


@app.get("/readiness")
def readiness_check():
    status = get_readiness_status()
    return JSONResponse(status_code=200 if status["status"] == "ready" else 503, content=status)
