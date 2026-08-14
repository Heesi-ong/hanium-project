#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
SOURCE_VIDEO="$PROJECT_ROOT/sample-demo.mp4"
OUTPUT_DIR="$PROJECT_ROOT/frontend/e2e/generated"

if ! command -v ffmpeg >/dev/null 2>&1; then
  echo "ffmpeg가 없어 golden 영상 fixture를 생성할 수 없습니다." >&2
  exit 1
fi

if [[ ! -f "$SOURCE_VIDEO" ]]; then
  echo "기준 영상이 없습니다: $SOURCE_VIDEO" >&2
  exit 1
fi

mkdir -p "$OUTPUT_DIR"

# 10초 미만 영상 신뢰도 감점과 짧은 입력 처리 경계를 검증합니다.
ffmpeg -hide_banner -loglevel error -y \
  -i "$SOURCE_VIDEO" \
  -t 6 \
  -map_metadata -1 \
  -c:v libx264 -preset veryfast -crf 28 \
  -c:a aac -b:a 64k \
  -movflags +faststart \
  "$OUTPUT_DIR/sample-short-6s.mp4"

# 얼굴 검출이 불리한 저조도·저해상도 환경과 오디오가 없는 fallback 경계를 함께 검증합니다.
ffmpeg -hide_banner -loglevel error -y \
  -i "$SOURCE_VIDEO" \
  -t 12 \
  -map_metadata -1 \
  -vf "scale=320:-2,eq=brightness=-0.70:saturation=0.25" \
  -an \
  -c:v libx264 -preset veryfast -crf 30 \
  -movflags +faststart \
  "$OUTPUT_DIR/sample-dark-muted-12s.mp4"

for fixture in \
  "$OUTPUT_DIR/sample-short-6s.mp4" \
  "$OUTPUT_DIR/sample-dark-muted-12s.mp4"; do
  ffprobe -v error -show_entries format=duration,size -of default=noprint_wrappers=1 "$fixture"
done
