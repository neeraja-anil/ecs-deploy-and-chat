# Multi-stage build:
#   Stage 1 ("build") has Maven + JDK and compiles the app into app.jar.
#   Stage 2 has only the Java runtime and the jar. Only stage 2 becomes the final image,
#   so the image stays small and contains no build tools.
#
# Build:  docker build --platform linux/amd64 -t ecs-health-check-app .
# Run:    docker run -p 8081:8080 ecs-health-check-app

# ---------- Stage 1: build ----------
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /build

# Copy pom.xml first and download dependencies. Docker caches this step, so
# later builds skip the download unless pom.xml changes.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

# Now copy the source code and build the jar (tests run here too).
COPY src ./src
RUN mvn -B -q package

# ---------- Stage 2: run ----------
FROM eclipse-temurin:17-jre
WORKDIR /app

# Run as a normal user instead of root (safer if the app is ever compromised).
RUN useradd --system appuser
USER appuser

# Take only the built jar from stage 1.
COPY --from=build /build/target/app.jar app.jar

# The app listens on 8080 (ECS task definition and load balancer use this port).
EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
