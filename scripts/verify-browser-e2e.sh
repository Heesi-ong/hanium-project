#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VIDEO_PATH="${TMPDIR:-/tmp}/hanium-e2e-$$.mp4"
trap 'rm -f "$VIDEO_PATH"' EXIT

command -v ffmpeg >/dev/null
curl -fsS http://127.0.0.1:8000/readiness | jq -e '.status == "ready"' >/dev/null
curl -fsSI http://127.0.0.1:5173/ >/dev/null

ffmpeg -loglevel error -y \
  -f lavfi -i "color=c=0x10244d:s=640x480:d=2" \
  -f lavfi -i "sine=frequency=440:duration=2" \
  -shortest -c:v libx264 -pix_fmt yuv420p -c:a aac "$VIDEO_PATH"

export E2E_VIDEO_PATH="$VIDEO_PATH"
export E2E_EMAIL="speakinsight-e2e-$(date +%s)@example.com"
export E2E_PASSWORD="E2e-Test-Password-2026"

cd "$ROOT_DIR/Front"
npm run test:e2e
