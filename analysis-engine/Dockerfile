FROM python:3.13-slim
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

EXPOSE 8001
CMD ["uvicorn", "app.main:app", "--host", "0.0.0.0", "--port", "8001"]
