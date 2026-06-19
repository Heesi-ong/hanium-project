#!/usr/bin/env python3
# .env 파일의 운영 필수 설정과 위험한 기본값을 검사하는 CLI 스크립트다.
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from Back.app.services.config_validation import validate_environment_file

ROOT_DIR = Path(__file__).resolve().parent.parent


def build_parser():
    parser = argparse.ArgumentParser(
        description="비밀값을 출력하지 않고 백엔드 환경 설정의 운영 안전 조건을 검사합니다."
    )
    parser.add_argument("--mode", choices=("development", "production"), default="development")
    parser.add_argument(
        "--env-file",
        type=Path,
        default=ROOT_DIR / "Back" / ".env",
        help="기본값: Back/.env",
    )
    return parser


def main():
    args = build_parser().parse_args()
    result = validate_environment_file(args.env_file, args.mode)
    print(f"환경 설정 검사: {result['path']} ({result['mode']})")
    for warning in result["warnings"]:
        print(f"경고: {warning}")
    for error in result["errors"]:
        print(f"오류: {error}", file=sys.stderr)
    if result["errors"]:
        print(f"검사 실패: 오류 {len(result['errors'])}개, 경고 {len(result['warnings'])}개")
        return 1
    print(f"검사 통과: 오류 0개, 경고 {len(result['warnings'])}개")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
