# ================================
# BUILD STAGE
# ================================
FROM gradle:8.14-jdk25 AS builder

WORKDIR /app

COPY . .

RUN chmod +x gradlew

RUN ./gradlew buildFatJar --no-daemon


# ================================
# RUNTIME STAGE
# ================================
FROM eclipse-temurin:25-jdk

WORKDIR /app

# Copy JAR được Gradle tạo ra
COPY --from=builder /app/build/libs/*-all.jar /app/build/libs/KtorService-all.jar

# Copy Tailscale startup script
COPY start.sh /app/start.sh

RUN chmod +x /app/start.sh

EXPOSE 10000

ENTRYPOINT ["/app/start.sh"]