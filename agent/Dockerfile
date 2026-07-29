FROM python:3.12.11-slim-bookworm@sha256:519591d6871b7bc437060736b9f7456b8731f1499a57e22e6c285135ae657bf7

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1 \
    PIP_NO_CACHE_DIR=1 \
    PYTHONPATH=/opt/hotshop-agent/src

WORKDIR /opt/hotshop-agent

RUN groupadd --system --gid 10001 hotshop \
    && useradd --system --uid 10001 --gid hotshop --home-dir /nonexistent hotshop

COPY requirements.lock ./
RUN python -m pip install --requirement requirements.lock

COPY src ./src

USER 10001:10001
EXPOSE 8090

HEALTHCHECK --interval=10s --timeout=3s --start-period=10s --retries=3 \
  CMD ["python", "-c", "import urllib.request; urllib.request.urlopen('http://127.0.0.1:8090/health/live', timeout=2)"]

CMD ["uvicorn", "hotshop_agent.main:app", "--host", "0.0.0.0", "--port", "8090", "--no-access-log"]
