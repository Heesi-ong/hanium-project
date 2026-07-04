FROM python:3.13-slim
WORKDIR /app

# 현재는 완전 Mock 응답만 반환하지만, requirements.txt에 torch/transformers 등
# 실제 모델 연동을 염두에 둔 의존성이 이미 포함되어 있어 이미지가 큽니다.
# (Phase 5: 실제 외부 API 연동 시 이 의존성 목록을 다시 정리할 필요가 있습니다.)
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        libgl1 \
        libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8002
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8002"]
