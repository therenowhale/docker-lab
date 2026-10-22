# syntax=docker/dockerfile:1
# Этап 1: зависимости лежат в отдельном кешируемом слое.
FROM maven:3.9.11-eclipse-temurin-21-alpine AS build
WORKDIR /build
COPY pom.xml ./
RUN mvn -B -q dependency:go-offline
COPY src ./src
RUN mvn -B -q package -DskipTests

# Этап 2: в runtime-образе нет Maven, исходников и root-пользователя.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S -G app -u 10001 app
COPY --from=build /build/target/container-lab.jar app.jar
USER app:app
EXPOSE 8080
HEALTHCHECK --interval=5s --timeout=2s --start-period=5s --retries=3 \
  CMD wget -q -O - http://127.0.0.1:8080/health >/dev/null || exit 1
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
