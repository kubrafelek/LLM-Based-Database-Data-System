FROM maven:3.9-eclipse-temurin-21 AS build

WORKDIR /build
# Dependencies first, so source changes don't re-download them.
COPY pom.xml .
RUN mvn -q -B dependency:go-offline
COPY src ./src
RUN mvn -q -B package -DskipTests


# Test image: sources + dependency cache. Used by `docker compose --profile test run --rm tests`.
FROM maven:3.9-eclipse-temurin-21 AS test

WORKDIR /build
COPY --from=build /root/.m2 /root/.m2
COPY pom.xml .
COPY src ./src
CMD ["mvn", "-B", "-q", "test"]


FROM eclipse-temurin:21-jre AS runtime

RUN useradd --create-home --uid 1001 appuser
WORKDIR /app
COPY --from=build /build/target/llm-data-discovery.jar app.jar

USER appuser
EXPOSE 8080

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=5 \
  CMD curl -fsS http://localhost:8080/health || exit 1

# The compose "migrate" service runs this image with MIGRATE_ONLY=true (Flyway migrates, then exits);
# the app only checks that no migration is pending, then seeds the admin user from .env.
# MIGRATE_ON_START=true migrates on start instead (single instance without compose).
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "app.jar"]
