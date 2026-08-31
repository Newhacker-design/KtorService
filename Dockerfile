# ================================
# BUILD STAGE
# ================================
FROM eclipse-temurin:25-jdk AS builder

WORKDIR /app

COPY . .

RUN chmod +x gradlew

RUN ./gradlew buildFatJar --no-daemon


# ================================
# RUNTIME STAGE
# ================================
FROM eclipse-temurin:25-jdk

WORKDIR /app

# ================================
# Install Tailscale + tools
# ================================
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        curl \
        ca-certificates \
        netcat-openbsd \
    && curl -fsSL https://pkgs.tailscale.com/stable/debian/bookworm.noarmor.gpg \
        -o /usr/share/keyrings/tailscale-archive-keyring.gpg \
    && curl -fsSL https://pkgs.tailscale.com/stable/debian/bookworm.tailscale-keyring.list \
        -o /etc/apt/sources.list.d/tailscale.list \
    && apt-get update \
    && apt-get install -y --no-install-recommends tailscale \
    && rm -rf /var/lib/apt/lists/*

RUN apt-get update \
    && apt-get install -y curl python3 postgresql-client netcat-openbsd \
    && rm -rf /var/lib/apt/lists/*
# ================================
# Copy Ktor JAR
# ================================
COPY --from=builder \
    /app/build/libs/*-all.jar \
    /app/build/libs/KtorService-all.jar


# ================================
# Copy startup script
# ================================
COPY start.sh /app/start.sh

RUN chmod +x /app/start.sh


# ================================
# Render port
# ================================
EXPOSE 10000


# ================================
# IMPORTANT
# ================================
ENTRYPOINT ["/app/start.sh"]
