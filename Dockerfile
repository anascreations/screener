# ═══════════════════════════════════════════════════════════════════
# Stage 1 — Build
# Uses full JDK + Maven to compile and package the fat jar
# ═══════════════════════════════════════════════════════════════════
FROM maven:3.9.9-eclipse-temurin-21 AS build

WORKDIR /build

# Copy pom first — lets Docker cache the dependency layer separately.
# Re-downloaded only when pom.xml changes, not on every source change.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -q

# ═══════════════════════════════════════════════════════════════════
# Stage 2 — Runtime
#
# IMPORTANT: Must use Ubuntu/Debian (jammy), NOT Alpine.
# tesseract-platform bundles glibc native binaries (.so files).
# Alpine uses musl libc — those .so files will fail to load at
# runtime with "cannot open shared object file" errors.
# ═══════════════════════════════════════════════════════════════════
FROM eclipse-temurin:21-jre-jammy

# Create non-root user for security
RUN groupadd -r spring && useradd -r -g spring spring

ENV APP_BASE_DIR=/app
ENV PORT=8080
ENV LOG_PATH=/app/logs

# Create required directories with correct ownership
RUN mkdir -p $APP_BASE_DIR/data \
             $APP_BASE_DIR/logs \
  && chown -R spring:spring $APP_BASE_DIR

WORKDIR $APP_BASE_DIR

# Copy jar from build stage
COPY --from=build --chown=spring:spring /build/target/screener-service-0.0.1.jar app.jar

USER spring:spring

EXPOSE 8080

ENTRYPOINT ["sh", "-c", \
  "java \
   -XX:+UseContainerSupport \
   -XX:MaxRAMPercentage=75.0 \
   -XX:+UseG1GC \
   -XX:+ExitOnOutOfMemoryError \
   -Djava.security.egd=file:/dev/./urandom \
   -jar app.jar \
   --server.port=$PORT"]