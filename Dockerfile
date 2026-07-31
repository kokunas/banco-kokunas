# syntax=docker/dockerfile:1
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:17-jre-jammy
LABEL org.opencontainers.image.title="banco-kokunas" \
      org.opencontainers.image.description="Banco Kokunas - mortgage & transfers demo (IBM Concert remediation lifecycle demo)" \
      org.opencontainers.image.source="https://github.com/kokunas/banco-kokunas" \
      app.kubernetes.io/name="banco-kokunas" \
      app.kubernetes.io/part-of="banco-kokunas"

RUN groupadd -r banco-kokunas && useradd -r -g banco-kokunas banco-kokunas
WORKDIR /app
COPY --from=build /build/target/banco-kokunas.jar app.jar
USER banco-kokunas

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
