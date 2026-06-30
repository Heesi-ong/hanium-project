from fastapi import FastAPI

from app.api.video_llm_analysis import router as video_llm_analysis_router

app = FastAPI(title="Video LLM Engine")


@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "service": "video-llm-engine"
    }


app.include_router(video_llm_analysis_router)