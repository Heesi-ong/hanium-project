ARG MINIO_MC_IMAGE=minio/mc:RELEASE.2025-08-13T08-35-41Z
FROM ${MINIO_MC_IMAGE} AS minio-client

FROM mysql:8.4

COPY --from=minio-client /usr/bin/mc /usr/local/bin/mc
COPY scripts/backup-mysql.sh /scripts/backup-mysql.sh
COPY infra/backup/backup-loop.sh /usr/local/bin/backup-loop.sh

RUN chmod +x /scripts/backup-mysql.sh /usr/local/bin/backup-loop.sh \
    && /usr/local/bin/mc --version \
    && /bin/bash -n /scripts/backup-mysql.sh /usr/local/bin/backup-loop.sh

# mysqldump/mc/openssl 실행과 바인드 마운트된 /backups·/logs·/metrics 쓰기에는 root 권한이
# 필요 없습니다. 베이스 이미지에 이미 있는 저권한 mysql 시스템 계정(mysqld 실행용, uid 999)을
# 그대로 재사용해 이 컨테이너도 root로 실행되지 않게 합니다.
USER mysql

ENTRYPOINT ["/usr/local/bin/backup-loop.sh"]
