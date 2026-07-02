# 1단계: 정적 파일 빌드
FROM node:22-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .

# 빌드 시점에 백엔드 주소를 주입합니다. (docker-compose에서 오버라이드 가능)
ARG VITE_API_BASE_URL=http://localhost:8080
ENV VITE_API_BASE_URL=$VITE_API_BASE_URL

RUN npm run build

# 2단계: nginx로 정적 파일 서빙
FROM nginx:1.27-alpine AS runtime
COPY --from=build /app/dist /usr/share/nginx/html
COPY nginx.conf /etc/nginx/conf.d/default.conf

EXPOSE 80
