# ============================================================
# Dockerfile — Student Meet-Up Event Booking System
# NTU ISYS40061 Coursework
#
# Multi-stage build:
#   Stage 1 (builder) — compiles the Maven project into a WAR
#   Stage 2 (runtime) — copies the WAR into Tomcat 9 and serves it
#
# Why Tomcat 9 (not 10)?
#   Tomcat 9 implements the Java EE 8 Servlet API (javax.ws.rs namespace).
#   Tomcat 10+ migrated to Jakarta EE 9+ (jakarta.ws.rs namespace).
#   Jersey 2.x uses javax.ws.rs — matching Dr Taha's HigherRates.java example —
#   so Tomcat 9 is the correct runtime choice for this project.
#
# Build and run:
#   docker build -t student-event-booking .
#   docker run -p 8080:8080 \
#     -e FIREBASE_CREDENTIALS=/app/serviceAccountKey.json \
#     -e SKIDDLE_API_KEY=your_skiddle_key \
#     -e OPENWEATHER_API_KEY=your_openweather_key \
#     -v /absolute/path/to/serviceAccountKey.json:/app/serviceAccountKey.json \
#     student-event-booking
#
# Then open: http://localhost:8080/
# API base:  http://localhost:8080/api/
# ============================================================

# ---- Stage 1: Build ----
FROM maven:3.9-eclipse-temurin-11 AS builder

WORKDIR /app

# Copy pom.xml first and download dependencies separately.
# Docker layer caches this step — subsequent builds only re-download
# if pom.xml changes, not on every source file edit.
COPY pom.xml .
RUN mvn dependency:go-offline --no-transfer-progress

# Copy source and build the WAR (skip tests for speed; run them locally)
COPY src ./src
RUN mvn clean package -DskipTests --no-transfer-progress

# ---- Stage 2: Runtime ----
FROM tomcat:9.0-jre11-temurin

# Remove the default Tomcat ROOT webapp to avoid any routing conflicts
RUN rm -rf /usr/local/tomcat/webapps/ROOT

# Copy the compiled WAR as ROOT.war so it deploys to /
# i.e. http://localhost:8080/ serves the frontend
#      http://localhost:8080/api/events serves the REST API
COPY --from=builder /app/target/student-event-booking-1.0-SNAPSHOT.war \
     /usr/local/tomcat/webapps/ROOT.war

# Expose port 8080 (Tomcat default)
EXPOSE 8080

# Start Tomcat in foreground (required for Docker to keep the container alive)
CMD ["catalina.sh", "run"]
