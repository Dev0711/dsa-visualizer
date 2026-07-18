# ============================================================
# DSA Visualizer — Docker Build
#
# Two-stage build:
#   Stage 1: Maven build (produces the fat jar)
#   Stage 2: Runtime — uses full JDK 17 (NOT jre-only) because:
#     • JdiStepEngine needs `javac` to compile user code
#     • JdiStepEngine needs to spawn a child `java` process
#       via JDI's CommandLineLaunch connector
#     • The jdk.jdi module is only in a full JDK
# ============================================================

# ---- Stage 1: Build ----------------------------------------
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build

WORKDIR /app

# Copy pom first so Docker layer-caches the dependency download
# separately from the source code — rebuilds are much faster.
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Now copy source and build
COPY src ./src
RUN mvn clean package -DskipTests -q

# ---- Stage 2: Runtime --------------------------------------
# Must use full JDK (not eclipse-temurin:17-jre-alpine) because
# the app needs javac + jdk.jdi at runtime, not just java.
FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

# Copy the fat jar from the build stage
COPY --from=build /app/target/dsa-visualizer.jar app.jar

# Render injects $PORT at runtime; Spring Boot reads server.port=${PORT:8080}
EXPOSE 8080

# --add-modules jdk.jdi  — required; without this, JdiStepEngine throws
#                           ClassNotFoundException for com.sun.jdi.*
# -XX:MaxRAMPercentage=75 — prevents the JVM from over-claiming RAM on
#                           Render's 512MB free tier container
ENTRYPOINT ["java", \
  "--add-modules", "jdk.jdi", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
