ARG RABBITMQ_IMAGE=rabbitmq:4.0.7-management-alpine@sha256:011e8d48c9d4e9e0bc94d709e2b1b68fccf51de6680bcda302566dff0465764f
FROM ${RABBITMQ_IMAGE}

# Keep this image as a reproducible, project-owned wrapper around the official
# management image. Fixed order delays use RabbitMQ TTL queues and DLX; no
# third-party delayed-message plugin is installed here.
