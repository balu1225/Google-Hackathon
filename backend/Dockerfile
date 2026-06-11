# ── FraudShield Backend ──
# Multi-stage build for Spring Boot + Maven

FROM maven:3.9-eclipse-temurin-21-alpine AS build
WORKDIR /app
COPY pom.xml .
# Cache dependencies
RUN mvn dependency:go-offline -B
COPY src ./src
RUN mvn package -DskipTests -B

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=build /app/target/*.jar app.jar

# Default environment variables
ENV SPRING_DATA_MONGODB_URI=mongodb://admin:password@mongodb:27017/fraudshield?authSource=admin
ENV GCP_PROJECT_ID=""
ENV GCP_REGION=us-central1

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
