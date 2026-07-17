ARG MINIO_MC_IMAGE=minio/mc:RELEASE.2025-08-13T08-35-41Z
FROM ${MINIO_MC_IMAGE} AS minio-client

FROM mysql:8.4

COPY --from=minio-client /usr/bin/mc /usr/local/bin/mc
COPY scripts/backup-mysql.sh /scripts/backup-mysql.sh
COPY infra/backup/backup-loop.sh /usr/local/bin/backup-loop.sh

RUN chmod +x /scripts/backup-mysql.sh /usr/local/bin/backup-loop.sh \
    && /usr/local/bin/mc --version \
    && /bin/bash -n /scripts/backup-mysql.sh /usr/local/bin/backup-loop.sh

ENTRYPOINT ["/usr/local/bin/backup-loop.sh"]
