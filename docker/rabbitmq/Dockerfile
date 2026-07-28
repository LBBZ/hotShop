ARG RABBITMQ_IMAGE=rabbitmq:4.0.7-management-alpine
FROM ${RABBITMQ_IMAGE}

# Keep this image as a reproducible, project-owned wrapper around the official
# management image. Fixed order delays use RabbitMQ TTL queues and DLX; no
# third-party delayed-message plugin is installed here.
