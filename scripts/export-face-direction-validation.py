#!/usr/bin/env python3
# 얼굴 방향 검증용 예측 결과를 CSV로 내보내는 운영 보조 스크립트다.
import argparse
import csv
import json
import sys
from contextlib import nullcontext
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from Back.app.services.face_direction_validation import build_face_direction_comparison

CSV_FIELDS = [
    "result_id",
    "algorithm_version",
    "time_sec",
    "face_detected",
    "gaze_score",
    "head_direction_score",
    "yaw_degrees",
    "pitch_degrees",
    "roll_degrees",
]


def load_comparisons(paths):
    comparisons = []
    for path in paths:
        with path.open(encoding="utf-8") as file:
            comparisons.append(build_face_direction_comparison(json.load(file)))
    return comparisons


def write_json(comparisons, stream):
    json.dump({"results": comparisons}, stream, ensure_ascii=False, indent=2)
    stream.write("\n")


def write_csv(comparisons, stream):
    writer = csv.DictWriter(stream, fieldnames=CSV_FIELDS, extrasaction="ignore")
    writer.writeheader()
    for comparison in comparisons:
        writer.writerows(comparison["rows"])


def build_parser():
    parser = argparse.ArgumentParser(
        description="기존 gaze 점수와 실험용 MediaPipe 얼굴 방향 지표를 비교해 내보냅니다."
    )
    parser.add_argument("results", nargs="+", type=Path, help="분석 결과 JSON 파일 경로")
    parser.add_argument("--format", choices=("json", "csv"), default="json")
    parser.add_argument("--output", type=Path, help="생략하면 표준 출력으로 내보냅니다.")
    return parser


def main():
    args = build_parser().parse_args()
    missing = [str(path) for path in args.results if not path.is_file()]
    if missing:
        print(f"결과 파일을 찾을 수 없습니다: {', '.join(missing)}", file=sys.stderr)
        return 1

    comparisons = load_comparisons(args.results)
    output_context = (
        args.output.open("w", encoding="utf-8", newline="")
        if args.output
        else nullcontext(sys.stdout)
    )
    with output_context as stream:
        if args.format == "csv":
            write_csv(comparisons, stream)
        else:
            write_json(comparisons, stream)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
