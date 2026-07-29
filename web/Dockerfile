FROM mcr.microsoft.com/playwright:v1.55.0-noble

ARG PNPM_VERSION=10.15.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && npm install --global "pnpm@${PNPM_VERSION}"

WORKDIR /workspace/web

COPY package.json pnpm-lock.yaml pnpm-workspace.yaml openapitools.json ./
RUN pnpm install --frozen-lockfile

COPY . .

CMD ["pnpm", "test:e2e"]
