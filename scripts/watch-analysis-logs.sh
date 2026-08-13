#!/usr/bin/env bash
set -euo pipefail

usage() {
    cat <<'EOF'
Usage: scripts/watch-analysis-logs.sh [jobId] [options]

분석 작업의 backend/worker/engine 로그를 단계 중심의 한 줄 형식으로 표시합니다.

Options:
  --job JOB_ID       특정 분석 작업만 표시합니다. 첫 번째 위치 인자로도 지정할 수 있습니다.
  --all              모든 분석 작업을 표시합니다(기본값).
  --tail N           서비스별 최근 로그 행 수입니다(기본값: 200).
  --since DURATION   Docker Compose가 지원하는 기간만 조회합니다(예: 10m, 1h).
  --no-follow        과거 로그를 출력한 뒤 종료합니다.
  --input FILE       Docker 대신 FILE을 읽습니다. '-'이면 표준 입력을 읽습니다.
  --no-color         ANSI 색상을 사용하지 않습니다.
  -h, --help         도움말을 표시합니다.

Examples:
  scripts/watch-analysis-logs.sh 20260812051828-03f8a97d
  scripts/watch-analysis-logs.sh --all --since 10m
  scripts/watch-analysis-logs.sh --job 20260812051828-03f8a97d --no-follow
EOF
}

JOB_ID=""
TAIL_LINES=200
SINCE=""
FOLLOW=true
INPUT_FILE=""
COLOR_MODE=auto

while [[ $# -gt 0 ]]; do
    case "$1" in
        --job)
            [[ $# -ge 2 ]] || { echo "ERROR: --job requires a value." >&2; exit 2; }
            JOB_ID="$2"
            shift 2
            ;;
        --all)
            JOB_ID=""
            shift
            ;;
        --tail)
            [[ $# -ge 2 ]] || { echo "ERROR: --tail requires a value." >&2; exit 2; }
            TAIL_LINES="$2"
            shift 2
            ;;
        --since)
            [[ $# -ge 2 ]] || { echo "ERROR: --since requires a value." >&2; exit 2; }
            SINCE="$2"
            shift 2
            ;;
        --no-follow)
            FOLLOW=false
            shift
            ;;
        --input)
            [[ $# -ge 2 ]] || { echo "ERROR: --input requires a value." >&2; exit 2; }
            INPUT_FILE="$2"
            FOLLOW=false
            shift 2
            ;;
        --no-color)
            COLOR_MODE=never
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        --*)
            echo "ERROR: unknown option: $1" >&2
            usage >&2
            exit 2
            ;;
        *)
            if [[ -n "$JOB_ID" ]]; then
                echo "ERROR: only one jobId can be specified." >&2
                exit 2
            fi
            JOB_ID="$1"
            shift
            ;;
    esac
done

if [[ -n "$JOB_ID" && ! "$JOB_ID" =~ ^[A-Za-z0-9._-]+$ ]]; then
    echo "ERROR: jobId contains unsupported characters: $JOB_ID" >&2
    exit 2
fi

if ! [[ "$TAIL_LINES" =~ ^[0-9]+$ ]]; then
    echo "ERROR: --tail must be a non-negative integer." >&2
    exit 2
fi

if [[ "$COLOR_MODE" == auto ]]; then
    if [[ -t 1 && -z "${NO_COLOR:-}" ]]; then
        COLOR_MODE=always
    else
        COLOR_MODE=never
    fi
fi

format_logs() {
    awk -v selected_job="$JOB_ID" -v color_mode="$COLOR_MODE" '
        function trim(value) {
            sub(/^[[:space:]]+/, "", value)
            sub(/[[:space:]]+$/, "", value)
            return value
        }

        function unescape_json(value) {
            gsub(/\\n/, " ", value)
            gsub(/\\r/, " ", value)
            gsub(/\\t/, " ", value)
            gsub(/\\\"/, "\"", value)
            gsub(/\\\\/, "\\", value)
            return value
        }

        function json_string(line, key,    marker, start, rest, i, char, escaped, value) {
            marker = "\"" key "\":\""
            start = index(line, marker)
            if (start == 0) return ""
            rest = substr(line, start + length(marker))
            escaped = 0
            value = ""
            for (i = 1; i <= length(rest); i++) {
                char = substr(rest, i, 1)
                if (char == "\"" && !escaped) break
                value = value char
                if (char == "\\" && !escaped) escaped = 1
                else escaped = 0
            }
            return unescape_json(value)
        }

        function extract_service(line,    pipe_at, value) {
            pipe_at = index(line, "|")
            if (pipe_at == 0) {
                if (line ~ /\"logger_name\":\"com\.hanium\./) return "BACKEND"
                return "LOG"
            }
            value = trim(substr(line, 1, pipe_at - 1))
            sub(/-[0-9]+$/, "", value)
            if (value == "analysis-worker") return "WORKER"
            if (value == "analysis-engine") return "BASIC"
            if (value == "video-llm-engine") return "VIDEO_LLM"
            if (value == "backend") return "BACKEND"
            return toupper(value)
        }

        function extract_job(line,    value, rest) {
            value = json_string(line, "jobId")
            if (value != "") return value
            if (match(line, /jobId=[A-Za-z0-9._-]+/)) {
                value = substr(line, RSTART + 6, RLENGTH - 6)
                return value
            }
            if (match(line, /job_id=[A-Za-z0-9._-]+/)) {
                value = substr(line, RSTART + 7, RLENGTH - 7)
                return value
            }
            if (match(line, /\[[0-9][0-9]+-[A-Za-z0-9][A-Za-z0-9]+\]/)) {
                value = substr(line, RSTART + 1, RLENGTH - 2)
                return value
            }
            if (match(line, /\([0-9][0-9]+-[A-Za-z0-9][A-Za-z0-9]+\)/)) {
                value = substr(line, RSTART + 1, RLENGTH - 2)
                return value
            }
            return "-"
        }

        function extract_timestamp(line,    value) {
            value = json_string(line, "@timestamp")
            if (value == "" && match(line, /20[0-9][0-9]-[0-9][0-9]-[0-9][0-9]T[0-9:]+/)) {
                value = substr(line, RSTART, RLENGTH)
            }
            if (value == "") return strftime("%H:%M:%S")
            sub(/^.*T/, "", value)
            sub(/\..*$/, "", value)
            sub(/[+-][0-9][0-9]:?[0-9][0-9]$/, "", value)
            sub(/Z$/, "", value)
            return substr(value, 1, 8)
        }

        function extract_message(line,    value, pipe_at, body, marker_at) {
            value = json_string(line, "message")
            if (value != "") return value
            pipe_at = index(line, "|")
            body = pipe_at == 0 ? line : substr(line, pipe_at + 1)
            marker_at = index(body, " - ")
            if (marker_at > 0) body = substr(body, marker_at + 3)
            sub(/^\([^)]*\)[[:space:]]*/, "", body)
            return trim(body)
        }

        function extract_percent(message) {
            if (match(message, /percent=[0-9]+%/)) {
                return substr(message, RSTART + 8, RLENGTH - 8)
            }
            if (match(message, /\([0-9]+%\)/)) {
                return substr(message, RSTART + 1, RLENGTH - 2)
            }
            if (match(message, /[[:space:]][0-9]+%/)) {
                return trim(substr(message, RSTART, RLENGTH))
            }
            return ""
        }

        function clean_message(message) {
            sub(/^\[[A-Za-z0-9._-]+\][[:space:]]*/, "", message)
            sub(/^STAGE_TRANSITION jobId=[^[:space:]]+ step=[^[:space:]]+ percent=[0-9]+%[[:space:]]*/, "", message)
            sub(/^\([0-9]+%\)[[:space:]]*/, "", message)
            return message
        }

        function classify(message, service,    percent, step) {
            if (message ~ /실패/ && message ~ /fallback|FALLBACK|폴백|추정값/) return "WARN"
            if (message ~ /중단|파이프라인.*실패|FAILED|ERROR|Exception|DEAD_LETTER/) return "FAILED"
            if (match(message, /[1-9]\/9/)) return "BASIC " substr(message, RSTART, RLENGTH)

            percent = extract_percent(message)
            if (message ~ /COMPLETED|100%|파이프라인이 완료|기본 분석 완료/) {
                return percent == "" ? "DONE" : "DONE " percent
            }
            if (message ~ /QUEUED|실행을 접수|대기열/) return "QUEUE"
            if (message ~ /VIDEO_LLM_ANALYSIS|Video LLM|NVIDIA/) {
                return percent == "" ? "VIDEO_LLM" : "VIDEO_LLM " percent
            }
            if (message ~ /OPENAI_FEEDBACK|OpenAI|AI 피드백/) {
                return percent == "" ? "OPENAI" : "OPENAI " percent
            }
            if (message ~ /COMPACT|축약|정리\(compact\)/) {
                return percent == "" ? "COMPACT" : "COMPACT " percent
            }
            if (message ~ /MERGING_RESULT|최종 결과.*병합/) {
                return percent == "" ? "MERGE" : "MERGE " percent
            }
            if (message ~ /BASIC_ANALYSIS|기본 분석/) {
                return percent == "" ? "BASIC" : "BASIC " percent
            }
            if (service == "BASIC") return "BASIC"
            return "ANALYSIS"
        }

        function color_for(stage) {
            if (color_mode != "always") return ""
            if (stage ~ /^FAILED/) return "\033[31m"
            if (stage ~ /^DONE/) return "\033[32m"
            if (stage ~ /^QUEUE|^WARN/) return "\033[33m"
            if (stage ~ /^VIDEO_LLM|^OPENAI/) return "\033[35m"
            if (stage ~ /^BASIC/) return "\033[36m"
            return "\033[34m"
        }

        {
            raw = $0
            job = extract_job(raw)
            if (selected_job != "" && index(raw, selected_job) == 0) next
            if (selected_job == "" && job == "-") next

            service = extract_service(raw)
            message = extract_message(raw)
            stage = classify(message, service)
            message = clean_message(message)
            timestamp = extract_timestamp(raw)
            color = color_for(stage)
            reset = color == "" ? "" : "\033[0m"

            printf "%s[%s] [%-9s] [%-20s] [%-14s]%s %s\n", color, timestamp, service, job, stage, reset, message
            fflush()
        }
    '
}

if [[ -n "$INPUT_FILE" ]]; then
    if [[ "$INPUT_FILE" == "-" ]]; then
        format_logs
    else
        [[ -f "$INPUT_FILE" ]] || { echo "ERROR: input file not found: $INPUT_FILE" >&2; exit 1; }
        format_logs < "$INPUT_FILE"
    fi
    exit 0
fi

command -v docker >/dev/null 2>&1 || {
    echo "ERROR: docker executable was not found." >&2
    exit 1
}

# Docker timestamp가 있어야 여러 서비스의 과거 로그를 발생 시각 순서로 병합할 수 있습니다.
# formatter는 애플리케이션 JSON timestamp를 우선 사용하므로 사용자 출력 형식은 유지됩니다.
compose_args=(logs --timestamps "--tail=$TAIL_LINES")
if [[ "$FOLLOW" == true ]]; then
    compose_args+=(--follow)
fi
if [[ -n "$SINCE" ]]; then
    compose_args+=("--since=$SINCE")
fi

services=(backend analysis-worker analysis-engine video-llm-engine)

if [[ "$FOLLOW" == true && -n "$JOB_ID" ]]; then
    echo "분석 로그 추적 시작: jobId=$JOB_ID (종료: Ctrl+C)" >&2
elif [[ "$FOLLOW" == true ]]; then
    echo "모든 분석 작업 로그 추적 시작 (종료: Ctrl+C)" >&2
elif [[ -n "$JOB_ID" ]]; then
    echo "분석 로그 조회: jobId=$JOB_ID" >&2
else
    echo "모든 분석 작업 로그 조회" >&2
fi

if [[ "$FOLLOW" == true ]]; then
    docker compose "${compose_args[@]}" "${services[@]}" | format_logs
else
    # compose의 비-follow 출력은 컨테이너별로 묶일 수 있습니다. prefix 뒤 세 번째 필드인
    # RFC3339 Docker timestamp로 안정 정렬해 실제 분석 순서를 복원합니다.
    docker compose "${compose_args[@]}" "${services[@]}" \
        | LC_ALL=C sort -s -k3,3 \
        | format_logs
fi
