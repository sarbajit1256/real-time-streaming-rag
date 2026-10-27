# Stage 1: Build the Maven application
FROM maven:3.9-eclipse-temurin-21-jammy AS build
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Production runtime with Java 21, Tesseract OCR, and ChromaDB
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

# Install native Tesseract OCR and Python for built-in ChromaDB
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-eng \
    libtesseract-dev \
    python3 \
    python3-pip \
    curl \
    && rm -rf /var/lib/apt/lists/*

# Install ChromaDB
RUN pip3 install --no-cache-dir chromadb

# Create non-root user (UID 1000) required by Hugging Face Spaces
RUN useradd -m -u 1000 appuser && \
    mkdir -p /data/chroma /app && \
    chown -R appuser:appuser /data /app /tmp

COPY --from=build --chown=appuser:appuser /app/target/*.jar app.jar
COPY --chown=appuser:appuser docker-entrypoint.sh docker-entrypoint.sh
RUN chmod +x docker-entrypoint.sh

USER appuser

# JVM parameters optimized for Hugging Face Spaces (16 GB available RAM)
ENV JAVA_OPTS="-Xms512m -Xmx4g -XX:+UseG1GC -Djava.net.preferIPv4Stack=true"
ENV PORT=7860
EXPOSE 7860

ENTRYPOINT ["/app/docker-entrypoint.sh"]