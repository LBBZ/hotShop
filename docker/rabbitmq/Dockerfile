ARG RABBITMQ_IMAGE=rabbitmq:4.3.6-management-alpine@sha256:95312f53d5b1115d410f1a56fba03a6325e3e03b4c60a845237bd345f341a097
FROM ${RABBITMQ_IMAGE}

# Keep this image as a reproducible, project-owned wrapper around the official
# management image. Fixed order delays use RabbitMQ TTL queues and DLX; no
# third-party delayed-message plugin is installed here.

USER root
RUN apk add --no-cache --upgrade libcrypto3=3.5.8-r0 libssl3=3.5.8-r0
RUN rabbitmq-plugins enable --offline rabbitmq_prometheus
USER rabbitmq
