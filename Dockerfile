FROM eclipse-temurin:21-jre-alpine
RUN addgroup -S spring && adduser -S spring -G spring
ENV APP_BASE_DIR=/app
ENV PORT=8080
RUN mkdir -p $APP_BASE_DIR/logger/screener-service && chown -R spring:spring $APP_BASE_DIR
WORKDIR $APP_BASE_DIR
COPY --chown=spring:spring ./target/screener-service-0.0.1.jar app.jar
USER spring:spring
EXPOSE 8088
ENTRYPOINT ["sh", "-c", "java -XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+UseG1GC -XX:+ExitOnOutOfMemoryError -Djava.security.egd=file:/dev/./urandom -jar app.jar --server.port=$PORT"]