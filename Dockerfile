# syntax=docker/dockerfile:1.6

############################
# Build stage
############################
FROM eclipse-temurin:21-jdk AS build

WORKDIR /workspace
ENV GRADLE_OPTS="-Dorg.gradle.daemon=false -Dorg.gradle.jvmargs='-Xmx2g -Dfile.encoding=UTF-8'"

# Кэширование зависимостей
COPY gradle gradle
COPY gradlew ./
COPY settings.gradle.kts ./
COPY build.gradle.kts ./
COPY gradle/libs.versions.toml gradle/libs.versions.toml

# Копируем весь проект (без build/, см. .dockerignore)
COPY . .

# Сборка дистрибутива только для :app
RUN --mount=type=cache,target=/root/.gradle \
    ./gradlew :app:installDist --no-daemon --console=plain

############################
# Runtime stage
############################
FROM eclipse-temurin:21-jre-jammy AS runtime

# Безопасные локали/часовой пояс
ENV TZ=UTC \
    LANG=C.UTF-8 \
    LANGUAGE=C.UTF-8 \
    LC_ALL=C.UTF-8

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl ca-certificates \
    && rm -rf /var/lib/apt/lists/*

# Непривилегированный пользователь
RUN useradd -ms /bin/bash appuser
USER appuser

WORKDIR /opt/app

# копируем установленный дистрибутив
COPY --from=build /workspace/app/build/install/app ./app

# Порт приложения
EXPOSE 8080

# ENV (примерные имена; реальные значения задаются в compose/.env)
ENV APP_PORT=8080 \
    APP_PROFILE=prod

# Здоровье: curl к /health/db
HEALTHCHECK --interval=10s --timeout=3s --retries=12 CMD \
  curl -fsS http://127.0.0.1:8080/health/db || exit 1

# Старт
ENTRYPOINT ["./app/bin/app"]
