운영 환경 적용 전 확인사항

1. Back/.env의 DB_PASSWORD를 운영 전용 비밀번호로 교체하고 파일 권한을 제한한다.
2. HTTPS 환경에서는 COOKIE_SECURE=true로 설정한다.
3. ALLOWED_ORIGINS를 실제 프론트엔드 주소만 허용하도록 제한한다.
4. Ollama는 외부 인터넷에 직접 노출하지 않고 백엔드에서만 접근한다.
5. OLLAMA_MODEL 값과 gpt_models의 provider/model_key가 일치해야 한다.
6. MySQL과 Ollama 장애 감시, 백업, 로그 보존 정책을 별도로 구성한다.
7. 운영 로그에 비밀번호, 세션 쿠키, API 키를 기록하지 않는다.
8. 다중 백엔드 인스턴스 운영 시 MySQL GET_LOCK 대기시간과 요청 제한 정책을 검토한다.
9. 현재 작업 상태는 MySQL 큐로 복구되지만 분석 실행은 백엔드 프로세스의 워커 스레드에서 수행되므로 다중 인스턴스 운영 전 Celery/RQ 같은 별도 워커 프로세스로 분리한다.
10. 현재 요청 제한은 단일 인스턴스 메모리 기준이므로 다중 인스턴스 운영 시 Redis 기반 제한으로 교체한다.
11. 분석 결과와 업로드 임시 파일의 보존 기간, 자동 삭제, 사용자 탈퇴 시 삭제 정책을 정한다.
12. `Back/migrations`의 모든 SQL이 번호 순서대로 적용되었는지 확인한다.
13. `ANALYSIS_SOURCE_RETENTION_HOURS`와 결과 파일 보존 기간을 개인정보 정책에 맞게 설정한다.
14. CI의 MediaPipe·Whisper 설치 시간과 캐시 정책을 확인한다.
15. 클라이언트의 AI 응답 중단은 HTTP 대기만 종료하므로 운영 환경에서는 Ollama 스트리밍 취소 API를 별도로 구현한다.
16. `ORPHAN_FRAME_MIN_AGE_MINUTES`를 최대 분석 작업 재시작 시간보다 충분히 크게 설정한다.
17. 완료 결과 자동 삭제는 기본 비활성화다. 사용자 고지와 백업 정책 확정 후 `ANALYSIS_RESULT_RETENTION_DAYS`를 설정한다.
18. `/health`는 프로세스와 DB의 기본 생존 확인에 사용하고, 트래픽 연결 전에는 `/readiness`가 200인지 확인한다.
19. `/readiness`에서 DB, 분석 워커, 유지보수 스레드, Ollama 모델, 큐, 디스크 여유 공간을 확인한다.
20. `MIN_FREE_DISK_MB`는 최대 업로드 크기와 프레임 추출 공간을 고려해 설정한다.
21. 운영 배포 전 DB와 `Back/results`를 함께 백업하고 실제 복원 절차를 검증한다.
22. 관리자 계정으로 `/api/admin/status`를 확인하고 일반 사용자에게는 403이 반환되는지 검증한다.
23. `CHAT_PENDING_TIMEOUT_MINUTES`가 Ollama 최대 응답 시간보다 충분히 큰지 확인한다.
24. 배포 전 `./scripts/db-backup.sh` 결과에 `Dump completed`가 있는지 확인한다.
25. `.venv/bin/python scripts/migrate.py`를 두 번 실행해 두 번째 결과가 `none`인지 확인한다.
26. `USER_STORAGE_QUOTA_MB`, `USER_MAX_ACTIVE_ANALYSES`, `MAX_UPLOAD_MB`를 운영 용량에 맞게 설정한다.
