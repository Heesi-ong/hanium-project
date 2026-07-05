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

RUN npm run build

# 2단계: nginx로 정적 파일 서빙
FROM nginx:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
