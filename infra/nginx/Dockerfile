FROM nginx:1.27-alpine

# docker-compose.prod.yml의 첫 기동 부트스트랩은 빈 letsencrypt 볼륨에 임시
# self-signed 인증서를 만들어 nginx가 먼저 뜨게 합니다. 공식 nginx alpine 이미지에는
# openssl이 없으므로 빌드 시점에 포함시켜 운영 기동이 런타임 네트워크에 의존하지 않게 합니다.
RUN apk add --no-cache openssl
