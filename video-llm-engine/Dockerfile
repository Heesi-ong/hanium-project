FROM python:3.14-slim
WORKDIR /app

# mock(기본) | external-api | local-model
# mock/external-api는 requirements-base.txt(fastapi/uvicorn/httpx만)로 충분해 이미지가
# 가볍습니다. 실제 로컬 모델을 구동하는 local-model만 torch/transformers 등 무거운
# 스택(requirements-real-model.txt)과 ffmpeg/libgl1 같은 OS 패키지가 필요합니다.
# 빌드 예: docker build --build-arg VIDEO_LLM_BACKEND=local-model .
ARG VIDEO_LLM_BACKEND=mock
ENV VIDEO_LLM_BACKEND=$VIDEO_LLM_BACKEND

COPY requirements-base.txt requirements-real-model.txt ./

RUN if [ "$VIDEO_LLM_BACKEND" = "local-model" ]; then \
        apt-get update && apt-get install -y --no-install-recommends \
            ffmpeg \
            libgl1 \
            libglib2.0-0 \
        && rm -rf /var/lib/apt/lists/* \
        && pip install --no-cache-dir -r requirements-real-model.txt; \
    else \
        pip install --no-cache-dir -r requirements-base.txt; \
    fi

COPY . .

# 사용자가 업로드한 신뢰할 수 없는 영상을 다루는 서비스라, analysis-engine과 동일하게
# 파서 취약점이 나오더라도 컨테이너 안에서 root 권한을 얻지 못하도록 권한을 낮춥니다.
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser \
    && mkdir -p /storage \
    && chown -R appuser:appuser /app /storage
USER appuser

EXPOSE 8002
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8002"]
