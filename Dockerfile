# Build stage
FROM maven:3.9.6-eclipse-temurin-17 AS builder
WORKDIR /app
COPY . .
# Avoid surefire plugin resolution during build (CI/CD or flaky central)
# -DskipTests only skips execution; -Dmaven.test.skip=true also skips test compilation & surefire
RUN mvn -Dmaven.test.skip=true -DskipTests clean package

# Run stage
FROM eclipse-temurin:17-jdk
WORKDIR /app
COPY --from=builder /app/target/shift-0.0.1-SNAPSHOT.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
