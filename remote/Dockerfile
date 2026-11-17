FROM node:24-bookworm-slim AS trust
RUN node -e 'require("fs").writeFileSync("/ca-certificates.crt", require("tls").rootCertificates.join("\n") + "\n")'
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive PYTHONUNBUFFERED=1
COPY --from=trust /ca-certificates.crt /etc/ssl/certs/ca-certificates.crt
RUN sed -i -e 's|http://archive.ubuntu.com/ubuntu|https://mirrors.edge.kernel.org/ubuntu|g' \
    -e 's|http://security.ubuntu.com/ubuntu|https://mirrors.edge.kernel.org/ubuntu|g' /etc/apt/sources.list.d/ubuntu.sources \
    && apt-get update && apt-get install -y --no-install-recommends \
    python3 python3-aiohttp ca-certificates curl unzip ffmpeg \
    libnss3 libpulse0 libasound2t64 libx11-6 libxcb1 libxdamage1 libxext6 \
    libxfixes3 libxi6 libxtst6 libxrandr2 libxrender1 libxkbcommon0 libxcomposite1 \
    libdrm2 libgbm1 libgl1 libglu1-mesa \
    && rm -rf /var/lib/apt/lists/*
COPY server.py /app/server.py
EXPOSE 8879
CMD ["python3", "/app/server.py"]
