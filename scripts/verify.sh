#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

cd "$ROOT_DIR"
bash -n scripts/*.sh
.venv/bin/ruff check Back
.venv/bin/python -m unittest discover -s Back/tests -v

cd "$ROOT_DIR/Front"
npm run format:check
npm run lint
npm run test
npm run build
