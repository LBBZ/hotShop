FROM docker:28.3.3-cli@sha256:0135662b510037ea581d99c2e5929c5e01185139c0b86986a418bd4da0b98a44 AS docker-cli

FROM mcr.microsoft.com/playwright:v1.57.0-noble@sha256:3bed4b1a12f2338642f3d8cba28e291deef3c66bd4a964bbeb3e57bbff511dbd AS test

ARG PNPM_VERSION=10.15.0

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/* \
    && npm install --global "pnpm@${PNPM_VERSION}"

COPY --from=docker-cli /usr/local/bin/docker /usr/local/bin/docker
COPY --from=docker-cli /usr/local/libexec/docker/cli-plugins/docker-compose /usr/local/libexec/docker/cli-plugins/docker-compose

WORKDIR /workspace/web
RUN chown pwuser:pwuser /workspace/web

COPY --chown=pwuser:pwuser package.json pnpm-lock.yaml pnpm-workspace.yaml openapitools.json ./
RUN test -s package.json && test -s pnpm-lock.yaml
USER pwuser
RUN pnpm install --frozen-lockfile
RUN pnpm exec openapi-generator-cli version

COPY --chown=pwuser:pwuser . .
RUN test -s package.json && test -s src/main.tsx

CMD ["pnpm", "test:e2e"]

FROM test AS build

RUN pnpm build

FROM nginxinc/nginx-unprivileged:1.29-alpine@sha256:0c79d56aee561a1d81c63f00eee5fb5fe29279560cdc55e91425133104c7fbe6 AS runtime

USER 0:0
RUN apk add --no-cache --upgrade \
        c-ares=1.34.8-r0 \
        curl=8.22.0-r0 \
        libcrypto3=3.5.8-r0 \
        libcurl=8.22.0-r0 \
        libexpat=2.8.4-r0 \
        libssl3=3.5.8-r0 \
        libuuid=2.41.6-r1 \
        libxml2=2.13.9-r1
COPY --from=build --chown=101:101 /workspace/web/dist /usr/share/nginx/html
COPY --chown=101:101 nginx.conf /etc/nginx/conf.d/default.conf

USER 101:101
EXPOSE 8080
