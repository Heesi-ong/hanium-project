#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"
bash -n scripts/*.sh
.venv/bin/ruff check Back scripts
.venv/bin/python scripts/verify-config.py --env-file Back/.env.example
.venv/bin/python scripts/verify-storage.py
DISABLE_BACKGROUND_SERVICES=true .venv/bin/python -m coverage run --source=Back/app -m unittest discover -s Back/tests -v
.venv/bin/python -m coverage report --fail-under=48

cd "$ROOT_DIR/Front"
npm run format:check
npm run lint
npm run test
npm run build
