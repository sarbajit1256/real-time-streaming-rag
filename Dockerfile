# Stage 1: Build the application with restricted Maven heap
FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
ENV MAVEN_OPTS="-Xms64m -Xmx256m"

COPY pom.xml .
COPY src ./src
RUN mvn clean package -DskipTests

# Stage 2: Minimal, lean Java 21 runtime
FROM eclipse-temurin:21-jre-jammy
WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

# JVM parameters optimized for Render's 512MB RAM cap
ENV JAVA_OPTS="-Xms64m -Xmx220m -XX:MaxMetaspaceSize=96m -XX:ReservedCodeCacheSize=32m -Xss256k -XX:+UseSerialGC -Djava.net.preferIPv4Stack=true -Djdk.httpclient.version=HTTP_1_1"

EXPOSE 8080
ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]