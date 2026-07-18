FROM python:3.12-slim
WORKDIR /app

# OpenCV / MediaPipe / ffmpeg 기반 영상·오디오 처리에 필요한 시스템 라이브러리입니다.
# (파이썬 pip 패키지만으로는 부족해서 OS 패키지로 추가 설치가 필요합니다.)
RUN apt-get update && apt-get install -y --no-install-recommends \
        ffmpeg \
        libgl1 \
        libglib2.0-0 \
    && rm -rf /var/lib/apt/lists/*

COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

COPY . .

# 사용자가 업로드한 신뢰할 수 없는 영상/오디오를 ffmpeg·OpenCV·MediaPipe로 직접 처리하므로,
# 파서 취약점이 나오더라도 컨테이너 안에서 root 권한을 얻지 못하도록 권한을 낮춥니다.
# /storage는 docker-compose에서 호스트 디렉터리를 바인드 마운트하는 지점이라 여기서
# chown해도 마운트 시점에는 호스트 쪽 권한을 따르지만, 이미지 자체 구조는 non-root로 맞춰둡니다.
RUN useradd --system --create-home --shell /usr/sbin/nologin appuser \
    && mkdir -p /storage \
    && chown -R appuser:appuser /app /storage
USER appuser

EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
