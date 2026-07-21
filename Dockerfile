# ============================================================
# DSA Visualizer — Docker Build
#
# Two-stage build:
#   Stage 1: Maven build (produces the fat jar)
#   Stage 2: Runtime
#
# We still need a full JDK (not just a JRE) at runtime because:
#   • CompilerService uses javax.tools.JavaCompiler (javac)
#     to compile user-submitted code in memory.
#   • InstrumentedRunner spawns a child `java` process to run it.
# We do NOT need jdk.jdi / JDWP any more — tracing is done via
# source instrumentation (JavaParser), not a debugger attachment.
# ============================================================

# ---- Stage 1: Build ----------------------------------------
FROM maven:3.9.6-eclipse-temurin-17-alpine AS build

WORKDIR /app

# Cache the dependency download layer separately from source code
COPY pom.xml .
RUN mvn dependency:go-offline -q

COPY src ./src
RUN mvn clean package -DskipTests -q

# ---- Stage 2: Runtime --------------------------------------
# Full JDK required for javac (CompilerService) and child java
# process (InstrumentedRunner). JRE-only images are not enough.
FROM eclipse-temurin:17-jdk-alpine

WORKDIR /app

COPY --from=build /app/target/dsa-visualizer.jar app.jar

# Render injects $PORT at runtime; Spring Boot reads server.port=${PORT:8080}
EXPOSE 8080

# -XX:MaxRAMPercentage=75 — prevent the JVM from over-claiming RAM on
# Render's 512MB free-tier container.
# No --add-modules jdk.jdi needed any more.
ENTRYPOINT ["java", \
  "-XX:MaxRAMPercentage=75.0", \
  "-jar", "app.jar"]
