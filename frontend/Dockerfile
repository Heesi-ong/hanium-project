# 1단계: 정적 파일 빌드
FROM node:22-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .

# 빈 문자열이면 같은 origin의 상대경로(/api)를 사용합니다.
# 로컬에서 docker 없이 frontend만 따로 띄워 별도 포트의 백엔드를 호출하려면 이 값을 명시적으로 설정하세요.
ARG VITE_API_BASE_URL=""
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

# 약관/개인정보처리방침 사업자 정보(P0-02). 비어 있으면 화면에 기존과 동일한
# placeholder 문구가 그대로 노출됩니다 — 이 값들은 정적 빌드에 내장되므로 실제 값이
# 준비되면 이미지를 다시 빌드해야 반영됩니다.
ARG VITE_BUSINESS_NAME=""
ARG VITE_BUSINESS_REPRESENTATIVE=""
ARG VITE_BUSINESS_REGISTRATION_NUMBER=""
ARG VITE_BUSINESS_ADDRESS=""
ARG VITE_BUSINESS_PHONE=""
ARG VITE_BUSINESS_EMAIL=""
ARG VITE_PRIVACY_OFFICER_NAME=""
ARG VITE_PRIVACY_OFFICER_EMAIL=""
ARG VITE_PRIVACY_OFFICER_PHONE=""
ENV VITE_BUSINESS_NAME=$VITE_BUSINESS_NAME
ENV VITE_BUSINESS_REPRESENTATIVE=$VITE_BUSINESS_REPRESENTATIVE
ENV VITE_BUSINESS_REGISTRATION_NUMBER=$VITE_BUSINESS_REGISTRATION_NUMBER
ENV VITE_BUSINESS_ADDRESS=$VITE_BUSINESS_ADDRESS
ENV VITE_BUSINESS_PHONE=$VITE_BUSINESS_PHONE
ENV VITE_BUSINESS_EMAIL=$VITE_BUSINESS_EMAIL
ENV VITE_PRIVACY_OFFICER_NAME=$VITE_PRIVACY_OFFICER_NAME
ENV VITE_PRIVACY_OFFICER_EMAIL=$VITE_PRIVACY_OFFICER_EMAIL
ENV VITE_PRIVACY_OFFICER_PHONE=$VITE_PRIVACY_OFFICER_PHONE

RUN npm run build

# 2단계: nginx로 정적 파일 서빙
FROM nginx:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html

# nginx 공식 이미지는 컨테이너 시작 시 /etc/nginx/templates/*.template 파일을 envsubst로
# 치환해 /etc/nginx/conf.d/에 생성합니다(infra/nginx/nginx.conf와 같은 방식). 이렇게 하면
# BACKEND_API_UPSTREAM을 빌드가 아니라 컨테이너 실행 시점(docker-compose environment)에
# 설정할 수 있습니다.
COPY nginx.conf.template /etc/nginx/templates/default.conf.template

# frontend 컨테이너만 단독으로 실행하거나(예: docker run, 로컬 스모크 테스트) backend가
# 아직 같은 네트워크에 없을 때도 nginx가 기동에 실패하지 않도록 하는 기본값입니다.
# 값을 채우면(예: backend:8080) 이 컨테이너가 자체적으로 /api를 그 주소로 프록시합니다.
ENV BACKEND_API_UPSTREAM=""

EXPOSE 80
