ARG RABBITMQ_IMAGE=rabbitmq:4.2.9-management-alpine@sha256:009a0e55d269a643930f797016d48aebe100986bc5ea836fea195199b26633ac
FROM ${RABBITMQ_IMAGE}

# Keep this image as a reproducible, project-owned wrapper around the official
# management image. Fixed order delays use RabbitMQ TTL queues and DLX; no
# third-party delayed-message plugin is installed here.

USER root
RUN apk add --no-cache --upgrade libcrypto3=3.5.8-r0 libssl3=3.5.8-r0
RUN rabbitmq-plugins enable --offline rabbitmq_prometheus
USER rabbitmq
