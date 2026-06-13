# MCPHUB Docker Image — BL-07
# Multi-stage build: build everything, then create minimal runtime image.
#
# Build:  docker build -t mcphub .
# Run:    docker run --rm -it -v /tmp/mcphub:/data mcphub
# Bridge: docker run --rm -i mcphub bridge

# ──────────────────────────────────────────────────
# Stage 1: Build
# ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jdk AS java-build
WORKDIR /build/java
COPY java/ .
RUN chmod +x gradlew && ./gradlew jar --no-daemon

FROM golang:1.25-bookworm AS go-build
WORKDIR /build
COPY go.mod ./
COPY cmd/ cmd/
RUN go build -o mcphub ./cmd/mcphub

FROM node:20-slim AS ts-build
WORKDIR /build/adapters
COPY adapters/package.json adapters/package-lock.json* ./
RUN npm install --silent
COPY adapters/ .
RUN npx tsc --build

# ──────────────────────────────────────────────────
# Stage 2: Runtime
# ──────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-noble

RUN apt-get update && apt-get install -y --no-install-recommends \
    nodejs \
    && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN useradd -m -s /bin/bash mcphub

# Distribution layout: bin/ lib/ adapters/
WORKDIR /opt/mcphub

COPY --from=go-build /build/mcphub bin/mcphub
COPY --from=java-build /build/java/build/libs/mcphub-core-*.jar lib/mcphub-core.jar
COPY --from=ts-build /build/adapters/dist/ adapters/
COPY README.md LICENSE ./

RUN chmod +x bin/mcphub && chown -R mcphub:mcphub /opt/mcphub

USER mcphub

# Data directory
RUN mkdir -p /home/mcphub/.local/share/mcphub

ENV PATH="/opt/mcphub/bin:${PATH}"
ENV MCPHUB_DATA_DIR="/home/mcphub/.local/share/mcphub"
ENV MCPHUB_ADAPTER_DIR="/opt/mcphub/adapters"

ENTRYPOINT ["mcphub"]
CMD ["_daemon"]
