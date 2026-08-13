#!/usr/bin/env bash
set -euo pipefail

PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
SCRIPT="$PROJECT_ROOT/scripts/watch-analysis-logs.sh"
FIXTURE="$PROJECT_ROOT/scripts/tests/fixtures/analysis-logs.txt"
TEST_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/watch-analysis-logs-test.XXXXXX")"

cleanup() {
    rm -rf "$TEST_ROOT"
}
trap cleanup EXIT

all_output="$TEST_ROOT/all.txt"
job_output="$TEST_ROOT/job.txt"
stdin_output="$TEST_ROOT/stdin.txt"

NO_COLOR=1 bash "$SCRIPT" --input "$FIXTURE" > "$all_output"

grep -Fq '[QUEUE' "$all_output"
grep -Fq '[20260812051828-03f8a97d] [QUEUE' "$all_output"
if grep -Fq '[QUEUED' "$all_output"; then
    echo "status text in parentheses must not be treated as jobId" >&2
    exit 1
fi
grep -Fq '[BASIC 10%' "$all_output"
grep -Fq '[BASIC 4/9' "$all_output"
grep -Fq '[VIDEO_LLM 40%' "$all_output"
grep -Fq '[OPENAI 75%' "$all_output"
grep -Fq '[MERGE 90%' "$all_output"
grep -Fq '[DONE 100%' "$all_output"
grep -Fq '[WARN' "$all_output"
grep -Fq '] 기본 분석 요청을 analysis-engine으로 전송합니다.' "$all_output"
if grep -Fq 'STAGE_TRANSITION jobId=' "$all_output"; then
    echo "structured transition metadata must not be repeated in the display message" >&2
    exit 1
fi
grep -Fq '20260812052000-deadbeef' "$all_output"
if grep -Fq 'ready for connections' "$all_output"; then
    echo "non-analysis log must not be displayed" >&2
    exit 1
fi

NO_COLOR=1 bash "$SCRIPT" 20260812051828-03f8a97d --input "$FIXTURE" > "$job_output"
grep -Fq '20260812051828-03f8a97d' "$job_output"
if grep -Fq '20260812052000-deadbeef' "$job_output"; then
    echo "another job must not be displayed in job filter mode" >&2
    exit 1
fi

NO_COLOR=1 bash "$SCRIPT" --job 20260812051828-03f8a97d --input - < "$FIXTURE" > "$stdin_output"
cmp "$job_output" "$stdin_output"

if bash "$SCRIPT" --tail invalid --input "$FIXTURE" > /dev/null 2>&1; then
    echo "invalid --tail value must fail" >&2
    exit 1
fi

if bash "$SCRIPT" 'unsafe job id' --input "$FIXTURE" > /dev/null 2>&1; then
    echo "unsafe jobId must fail" >&2
    exit 1
fi

echo "watch-analysis-logs regression tests passed"
