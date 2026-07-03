from fastapi import FastAPI

from app.core.logging_config import configure_logging

configure_logging()

from app.api.basic_analysis import router as basic_analysis_router

app = FastAPI(title="Analysis Engine")


@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "service": "analysis-engine"
    }


app.include_router(basic_analysis_router)
