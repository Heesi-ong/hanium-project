운영 환경 적용 전 확인사항

1. Back/.env의 DB_PASSWORD를 운영 전용 비밀번호로 교체하고 파일 권한을 제한한다.
2. HTTPS 환경에서는 COOKIE_SECURE=true로 설정한다.
3. ALLOWED_ORIGINS를 실제 프론트엔드 주소만 허용하도록 제한한다.
4. Ollama는 외부 인터넷에 직접 노출하지 않고 백엔드에서만 접근한다.
5. OLLAMA_MODEL 값과 gpt_models의 provider/model_key가 일치해야 한다.
6. MySQL과 Ollama 장애 감시, 백업, 로그 보존 정책을 별도로 구성한다.
7. 운영 로그에 비밀번호, 세션 쿠키, API 키를 기록하지 않는다.
8. 다중 백엔드 인스턴스 운영 시 MySQL GET_LOCK 대기시간과 요청 제한 정책을 검토한다.
9. 현재 단일 서버에서는 백엔드 내부 워커를 유지한다. 분리 전환 절차는 `docs/WORKER_SEPARATION.md`를 따른다.
10. 현재 요청 제한은 단일 인스턴스 메모리 기준이므로 다중 인스턴스 운영 시 Redis 기반 제한으로 교체한다.
11. 확정 정책은 성공 원본 즉시 삭제, 실패·취소 원본 24시간, 분석 결과 90일, 감사 로그 365일이다.
12. `Back/migrations`의 모든 SQL이 번호 순서대로 적용되었는지 확인한다.
13. `ANALYSIS_SOURCE_RETENTION_HOURS`와 결과 파일 보존 기간을 개인정보 정책에 맞게 설정한다.
14. CI의 MediaPipe·Whisper 설치 시간과 캐시 정책을 확인한다.
15. 클라이언트의 AI 응답 중단은 HTTP 대기만 종료하므로 운영 환경에서는 Ollama 스트리밍 취소 API를 별도로 구현한다.
16. `ORPHAN_FRAME_MIN_AGE_MINUTES`를 최대 분석 작업 재시작 시간보다 충분히 크게 설정한다.
17. `ANALYSIS_RESULT_RETENTION_DAYS=90`, `ADMIN_AUDIT_RETENTION_DAYS=365`가 적용됐는지 확인한다.
18. `/health`는 프로세스와 DB의 기본 생존 확인에 사용하고, 트래픽 연결 전에는 `/readiness`가 200인지 확인한다.
19. `/readiness`에서 DB, 분석 워커, 유지보수 스레드, Ollama 모델, 큐, 디스크 여유 공간을 확인한다.
20. `MIN_FREE_DISK_MB`는 최대 업로드 크기와 프레임 추출 공간을 고려해 설정한다.
21. 운영 배포 전 DB와 `Back/storage/results`를 함께 백업하고 실제 복원 절차를 검증한다.
22. `scripts/check-runtime-artifacts.py`로 구 경로가 포함된 런타임 JSON을 점검한다. 발견된 파일은 자동 삭제하지 말고 백업·보존 필요성을 확인한 뒤 승인된 절차로 정리한다.
23. 관리자 계정으로 `/api/admin/status`를 확인하고 일반 사용자에게는 403이 반환되는지 검증한다.
24. `CHAT_PENDING_TIMEOUT_MINUTES`가 Ollama 최대 응답 시간보다 충분히 큰지 확인한다.
25. 배포 전 `./scripts/db-backup.sh` 결과에 `Dump completed`가 있는지 확인한다.
26. `.venv/bin/python scripts/migrate.py`를 두 번 실행해 두 번째 결과가 `none`인지 확인한다.
27. `USER_STORAGE_QUOTA_MB`, `USER_MAX_ACTIVE_ANALYSES`, `MAX_UPLOAD_MB`를 운영 용량에 맞게 설정한다.
28. 최초 관리자 생성 또는 기존 사용자 승격은 `scripts/manage-admin.py`만 사용하고,
    변경 결과가 `admin_audit_logs`에 기록되었는지 확인한다.
29. `.venv/bin/python scripts/verify-config.py --mode production`이 오류 없이 통과하는지
    확인한다. 이 검사는 비밀값 자체를 출력하지 않는다.
30. 관리자 전용 `/api/admin/metrics`의 성공률, 실패율, 평균 완료 처리 시간이 실제
    작업 상태와 일치하는지 확인한다.
31. Nginx 등 외부 프록시에서도 `MAX_UPLOAD_MB` 이하로 요청 본문 크기를 제한한다.
    백엔드는 `Content-Length`가 없는 스트리밍 요청도 실제 수신 바이트 기준으로 차단한다.
32. 결과·계정 삭제는 `Back/storage/.deletion_staging`으로 파일을 먼저 격리한 뒤 DB를 삭제한다.
    `.committed`가 있는 오래된 격리 디렉터리만 유지보수 워커가 자동 제거한다.
    마커 없는 디렉터리가 남으면 롤백 실패 가능성이 있으므로 수동 복구 전 삭제하지 않는다.
32. `.venv/bin/python scripts/check-deletion-staging.py --fail-on-uncommitted`로 미커밋 격리를 점검한다.
33. 운영 DB 계정은 `docs/mysql-least-privilege.sql.example`을 기반으로 분리한다.
34. 운영 Nginx 설정은 `docs/nginx.speakinsight.conf.example`을 기반으로 실제 HTTPS 도메인을 적용한다.
