# 1단계: Gradle로 실행 가능한 jar 빌드
FROM eclipse-temurin:25-jdk AS build
WORKDIR /app

COPY gradlew ./
COPY gradle ./gradle
COPY build.gradle settings.gradle ./
RUN chmod +x gradlew

COPY src ./src
RUN ./gradlew clean bootJar --no-daemon -x test

# 2단계: 빌드된 jar만 담은 실행용 이미지 (JDK가 아닌 JRE만 사용해 이미지 크기를 줄임)
FROM eclipse-temurin:25-jre AS runtime
WORKDIR /app

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ffmpeg \
    && rm -rf /var/lib/apt/lists/*

RUN useradd --system --create-home --shell /usr/sbin/nologin appuser
COPY --from=build /app/build/libs/*-SNAPSHOT.jar app.jar

# storage 볼륨 마운트 지점 (docker-compose에서 실제 경로를 연결합니다)
RUN mkdir -p /storage && chown -R appuser:appuser /app /storage
USER appuser

ENV SPRING_PROFILES_ACTIVE=prod
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/app/app.jar"]
