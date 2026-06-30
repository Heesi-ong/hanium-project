from fastapi import FastAPI

app = FastAPI(title="Video LLM Engine")


@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "service": "video-llm-engine"
    }