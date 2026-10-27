# Stage 1: Build the Spring Boot application
FROM maven:3.9-eclipse-temurin-21-jammy AS build

WORKDIR /app

COPY pom.xml .
COPY src ./src

RUN mvn clean package -DskipTests

# Stage 2: Runtime
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Install Tesseract OCR and English language data
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-eng && rm -rf /var/lib/apt/lists/*
# Create a non-root user
RUN useradd --system --uid 10001 --create-home appuser

# Copy the Spring Boot application JAR
COPY --from=build /app/target/*.jar /app/app.jar

RUN chown -R appuser:appuser /app

USER appuser

# JVM memory settings
ENV JAVA_OPTS="-XX:MaxRAMPercentage=60.0"

# Render provides the PORT environment variable
EXPOSE 8080

ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -Djava.net.preferIPv4Stack=true -jar /app/app.jar"]