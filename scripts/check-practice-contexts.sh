#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

.venv/bin/python -c '
from Back.app.services.analysis_jobs import list_all_job_ids
from Back.app.services.practice_coaching import list_orphan_practice_contexts

orphan_ids = list_orphan_practice_contexts(list_all_job_ids())
print(f"orphan_practice_contexts={len(orphan_ids)}")
for result_id in orphan_ids:
    print(result_id)
'
