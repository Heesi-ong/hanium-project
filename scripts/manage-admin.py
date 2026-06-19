#!/usr/bin/env python3
# 초기 관리자 생성과 관리자 권한 부여·회수를 수행하는 운영 CLI 스크립트다.
import argparse
import getpass
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from Back.app.services.admin_management import (
    AdminManagementError,
    create_initial_admin,
    get_user_by_email,
    promote_user_to_admin,
)


def _confirm_target(email):
    confirmation = input(f"변경 대상 이메일을 다시 입력하세요 ({email}): ").strip().lower()
    if confirmation != email:
        raise AdminManagementError("확인 이메일이 일치하지 않아 변경을 취소했습니다.")


def _actor_identifier():
    return f"manage-admin:{getpass.getuser()}"


def create_command(args):
    email = args.email.strip().lower()
    print("최초 관리자 계정을 생성합니다.")
    print(f"이메일: {email}")
    print(f"표시 이름: {args.display_name.strip()}")
    _confirm_target(email)
    password = getpass.getpass("새 관리자 비밀번호: ")
    password_confirmation = getpass.getpass("새 관리자 비밀번호 확인: ")
    if password != password_confirmation:
        raise AdminManagementError("비밀번호 확인이 일치하지 않습니다.")
    user = create_initial_admin(email, args.display_name, password, _actor_identifier())
    print(f"관리자 계정을 생성했습니다: {user['email']}")


def promote_command(args):
    email = args.email.strip().lower()
    user = get_user_by_email(email)
    if not user:
        raise AdminManagementError("해당 이메일의 사용자를 찾을 수 없습니다.")
    print("기존 사용자를 관리자로 승격합니다.")
    print(f"이메일: {user['email']}")
    print(f"표시 이름: {user['display_name']}")
    print(f"현재 권한: {user['role']}")
    print(f"현재 상태: {user['status']}")
    _confirm_target(email)
    updated = promote_user_to_admin(email, _actor_identifier())
    print(f"관리자 승격을 완료했습니다: {updated['email']}")
    print("기존 로그인 세션을 로그아웃한 뒤 다시 로그인하세요.")


def build_parser():
    parser = argparse.ArgumentParser(
        description="공개 웹 API 없이 최초 관리자를 생성하거나 기존 사용자를 승격합니다."
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    create_parser = subparsers.add_parser(
        "create-initial",
        help="관리자가 한 명도 없을 때 최초 관리자 계정을 생성합니다.",
    )
    create_parser.add_argument("--email", required=True)
    create_parser.add_argument("--display-name", required=True)
    create_parser.set_defaults(handler=create_command)

    promote_parser = subparsers.add_parser(
        "promote",
        help="활성 상태의 기존 사용자를 관리자로 승격합니다.",
    )
    promote_parser.add_argument("--email", required=True)
    promote_parser.set_defaults(handler=promote_command)
    return parser


def main():
    args = build_parser().parse_args()
    try:
        args.handler(args)
    except AdminManagementError as error:
        print(f"관리자 변경 실패: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
