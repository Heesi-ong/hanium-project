# nginx TLS 리버스 프록시 운영 스캐폴딩

이 디렉터리는 운영 서버에서 nginx가 TLS를 종단하고 `backend`/`frontend` 컨테이너로 요청을 프록시하기 위한 기본 구성입니다. 실제 Let's Encrypt 인증서 발급은 공개 도메인과 외부에서 접근 가능한 80/443 포트가 있는 서버에서만 가능합니다. 로컬 개발 환경이나 Docker 데몬이 없는 환경에서는 인증서 발급과 HTTPS 접속을 검증할 수 없습니다.

## 구성 개요

- `infra/nginx/nginx.conf`: nginx 공식 이미지의 envsubst 템플릿으로 사용됩니다. `DOMAIN` 환경변수가 실제 도메인으로 치환됩니다.
- `docker-compose.prod.yml`: 기본 `docker-compose.yml` 위에 얹는 운영 오버레이입니다.
- `nginx`: 호스트 80/443만 외부에 공개하고 `/api/`는 `backend:8080`, 나머지는 `frontend:80`으로 프록시합니다.
- `certbot`: `certbot renew`를 12시간마다 반복 실행합니다.

## 최초 인증서 발급

1. DNS에서 `DOMAIN`이 운영 서버의 공인 IP를 가리키게 설정합니다.
2. 서버 방화벽과 클라우드 보안그룹에서 80/443 포트를 외부에 엽니다.
3. 프로젝트 루트의 `.env`에 실제 도메인을 설정합니다.

```bash
DOMAIN=example.com
```

4. nginx를 띄우기 전에 webroot 방식으로 최초 인증서를 발급합니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml run --rm \
  --entrypoint certbot certbot \
  certonly --webroot -w /var/www/certbot -d "${DOMAIN}" \
  --email "admin@${DOMAIN}" --agree-tos --no-eff-email
```

5. 인증서가 발급되면 전체 스택을 실행합니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml up --build -d
```

## 자동 갱신

`docker-compose.prod.yml`의 `certbot` 서비스는 12시간마다 다음 명령을 반복 실행합니다.

```bash
certbot renew --webroot -w /var/www/certbot --quiet
```

인증서 갱신 후 nginx가 새 인증서를 읽으려면 reload가 필요할 수 있습니다. 운영 서버에서는 갱신 후 다음 명령을 cron이나 배포 스크립트에 함께 넣는 방식을 권장합니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml exec nginx nginx -s reload
```

## 포트 노출 주의

기본 `docker-compose.yml`은 로컬 개발 편의를 위해 `backend`와 `frontend` 포트를 직접 공개합니다. 운영에서는 nginx만 외부에 노출되어야 합니다. `docker-compose.prod.yml`에는 Docker Compose 전용 태그인 `ports: !reset []`를 사용해 기본 파일의 직접 포트 공개를 제거합니다. 이 태그는 일반 YAML 파서에서는 해석되지 않을 수 있으므로, 실제 검증은 `docker compose config`로 수행해야 합니다.

```bash
docker compose -f docker-compose.yml -f docker-compose.prod.yml config
```

병합 결과에서 외부 공개 포트가 nginx의 80/443만 남지 않는다면, Docker Compose 버전이 `!reset`을 지원하는지 확인하고, 방화벽/보안그룹에서도 backend 8080과 frontend 5173 직접 접근을 차단해야 합니다.

## 로컬 검증 한계

이 구성은 실제 도메인, 공인 IP, 외부에서 접근 가능한 80/443 포트가 있어야 완전히 검증됩니다. 로컬에서는 YAML/Compose 병합 문법과 nginx 설정의 정적 검토까지만 가능합니다.
