FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build
COPY pom.xml .
COPY src src
RUN mvn -B -DskipTests package

FROM eclipse-temurin:21-jre
WORKDIR /app
ADD --chmod=644 --checksum=sha256:53b34ae7a9ac9497ac16607fc6c74f10bb3cf818dc241789a067c47b0bdc2ea0 https://github.com/open-telemetry/opentelemetry-java-instrumentation/releases/download/v2.22.0/opentelemetry-javaagent.jar /app/opentelemetry-javaagent.jar
COPY --from=build /build/target/autocare-billing-*.jar app.jar
USER 10001
ENV OTEL_SERVICE_NAME=autocare-billing
ENV OTEL_METRICS_EXPORTER=none
ENV OTEL_TRACES_EXPORTER=none
ENV OTEL_LOGS_EXPORTER=none
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -javaagent:/app/opentelemetry-javaagent.jar"
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
