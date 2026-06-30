from fastapi import FastAPI

app = FastAPI(title="Analysis Engine")


@app.get("/health")
def health_check():
    return {
        "status": "ok",
        "service": "analysis-engine"
    }