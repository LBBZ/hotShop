from __future__ import annotations

import hashlib
import os
import shutil
import subprocess
import time
import uuid
from collections.abc import Iterator
from pathlib import Path

import pytest

IMAGE = os.environ.get("AGENT_CONTAINER_IMAGE", "")
DOCKER = shutil.which("docker") or "docker"
pytestmark = pytest.mark.skipif(
    not IMAGE,
    reason="set AGENT_CONTAINER_IMAGE to run the Docker runtime security tests",
)

PRIVATE_SENTINEL = b"TASK16_RECONCILE_PRIVATE_SENTINEL_8f3567e5\n"  # noqa: S105
PUBLIC_SENTINEL = b"TASK16_RECONCILE_PUBLIC_TEST_VALUE\n"


def docker(*arguments: str, input_bytes: bytes | None = None, check: bool = True) -> str:
    completed = subprocess.run(  # noqa: S603
        [DOCKER, *arguments],
        input=input_bytes,
        capture_output=True,
        check=False,
    )
    if check and completed.returncode != 0:
        raise AssertionError(
            f"docker command failed with exit {completed.returncode}: "
            f"{completed.stderr.decode(errors='replace')[:2000]}"
        )
    return completed.stdout.decode(errors="replace").strip()


def populate_volume(volume: str, filename: str, content: bytes, mode: str) -> None:
    docker(
        "run",
        "--rm",
        "--entrypoint",
        "sh",
        "-i",
        "--mount",
        f"type=volume,source={volume},target=/material",
        IMAGE,
        "-c",
        f"umask 077; cat > /material/{filename}; chmod {mode} /material/{filename}",
        input_bytes=content,
    )


def volume_metadata(volume: str, filename: str) -> str:
    return docker(
        "run",
        "--rm",
        "--entrypoint",
        "sh",
        "--mount",
        f"type=volume,source={volume},target=/material,readonly",
        IMAGE,
        "-c",
        f"stat -c '%u:%g:%a' /material/{filename}; sha256sum /material/{filename}",
    )


@pytest.fixture
def runtime_container() -> Iterator[dict[str, str]]:
    suffix = uuid.uuid4().hex[:12]
    name = f"hotshop-agent-security-{suffix}"
    private_volume = f"hotshop-agent-private-{suffix}"
    public_volume = f"hotshop-agent-public-{suffix}"
    docker("volume", "create", private_volume)
    docker("volume", "create", public_volume)
    populate_volume(private_volume, "agent-service-private.pem", PRIVATE_SENTINEL, "0400")
    populate_volume(public_volume, "verification-public.pem", PUBLIC_SENTINEL, "0444")
    source_before = volume_metadata(private_volume, "agent-service-private.pem")
    docker(
        "run",
        "--detach",
        "--name",
        name,
        "--tmpfs",
        "/run/hotshop-agent:rw,noexec,nosuid,nodev,mode=0700",
        "--mount",
        f"type=volume,source={private_volume},target=/run/key-source,readonly",
        "--mount",
        f"type=volume,source={public_volume},target=/run/public-keys,readonly",
        "--env",
        "AGENT_ASSERTION_PRIVATE_KEY_SOURCE_PATH=/run/key-source/agent-service-private.pem",
        "--env",
        "AGENT_ENVIRONMENT=test",
        "--env",
        "AGENT_STATE_BACKEND=memory",
        "--env",
        "AGENT_TRACE_SAMPLE_RATIO=0",
        IMAGE,
    )
    try:
        deadline = time.monotonic() + 90
        while time.monotonic() < deadline:
            status = docker(
                "inspect",
                "--format",
                "{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}",
                name,
            )
            if status == "healthy":
                break
            if status in {"exited", "dead"}:
                raise AssertionError(f"agent container did not become healthy: {status}")
            time.sleep(0.5)
        else:
            raise AssertionError("agent container health check timed out")
        yield {
            "name": name,
            "private_volume": private_volume,
            "source_before": source_before,
        }
    finally:
        docker("rm", "--force", name, check=False)
        docker("volume", "rm", "--force", private_volume, check=False)
        docker("volume", "rm", "--force", public_volume, check=False)


def test_runtime_private_key_permissions_and_process_identity(
    runtime_container: dict[str, str],
) -> None:
    name = runtime_container["name"]
    process_status = docker(
        "exec",
        "--user",
        "0:0",
        name,
        "cat",
        "/proc/1/status",
    )
    identity_rows = [
        line.split()[1:]
        for line in process_status.splitlines()
        if line.startswith(("Uid:", "Gid:"))
    ]
    assert identity_rows == [["10001"] * 4, ["10001"] * 4]
    process_security = {
        line.split(":", maxsplit=1)[0]: line.split(":", maxsplit=1)[1].strip()
        for line in process_status.splitlines()
        if line.startswith(("CapEff:", "CapPrm:", "CapAmb:", "NoNewPrivs:", "Groups:"))
    }
    assert process_security == {
        "Groups": "",
        "CapPrm": "0000000000000000",
        "CapEff": "0000000000000000",
        "CapAmb": "0000000000000000",
        "NoNewPrivs": "1",
    }
    assert (
        docker(
            "exec",
            "--user",
            "0:0",
            name,
            "stat",
            "-c",
            "%u:%g:%a",
            "/run/hotshop-agent/agent-service-private.pem",
        )
        == "10001:10001:400"
    )
    docker(
        "exec",
        "--user",
        "10001:10001",
        name,
        "test",
        "-r",
        "/run/hotshop-agent/agent-service-private.pem",
    )
    docker(
        "exec",
        "--user",
        "10001:10001",
        name,
        "test",
        "!",
        "-r",
        "/run/key-source/agent-service-private.pem",
    )
    docker(
        "exec",
        "--user",
        "10002:10002",
        name,
        "test",
        "!",
        "-r",
        "/run/hotshop-agent/agent-service-private.pem",
    )
    docker(
        "exec",
        "--user",
        "10001:10001",
        name,
        "test",
        "!",
        "-w",
        "/run/public-keys/verification-public.pem",
    )
    assert volume_metadata(
        runtime_container["private_volume"], "agent-service-private.pem"
    ) == runtime_container["source_before"]


def test_runtime_logs_and_api_do_not_expose_private_key(
    runtime_container: dict[str, str],
) -> None:
    name = runtime_container["name"]
    response = docker(
        "exec",
        "--user",
        "10001:10001",
        name,
        "python",
        "-c",
        (
            "import urllib.request;"
            "print(urllib.request.urlopen('http://127.0.0.1:8090/health/live').read().decode())"
        ),
    )
    logs = docker("logs", name)
    assert '"status":"UP"' in response.replace(" ", "")
    assert PRIVATE_SENTINEL.decode().strip() not in response
    assert PRIVATE_SENTINEL.decode().strip() not in logs
    assert "PRIVATE KEY" not in response
    assert "PRIVATE KEY" not in logs


@pytest.mark.parametrize("case", ("missing", "empty", "nonroot"))
def test_entrypoint_fails_fast_without_usable_root_only_source(case: str) -> None:
    suffix = uuid.uuid4().hex[:12]
    volume = f"hotshop-agent-empty-{suffix}"
    arguments = ["run", "--rm"]
    try:
        if case == "empty":
            docker("volume", "create", volume)
            populate_volume(volume, "empty.pem", b"", "0400")
            arguments.extend(
                [
                    "--mount",
                    f"type=volume,source={volume},target=/run/key-source,readonly",
                    "--env",
                    "AGENT_ASSERTION_PRIVATE_KEY_SOURCE_PATH=/run/key-source/empty.pem",
                ]
            )
        elif case == "missing":
            arguments.extend(
                [
                    "--env",
                    "AGENT_ASSERTION_PRIVATE_KEY_SOURCE_PATH=/run/key-source/missing.pem",
                ]
            )
        else:
            arguments.extend(["--user", "10001:10001"])
        completed = subprocess.run(  # noqa: S603
            [DOCKER, *arguments, IMAGE, "true"],
            capture_output=True,
            check=False,
        )
        combined = completed.stdout + completed.stderr
        assert completed.returncode == 70
        assert b"private key initialization failed" in combined
        assert PRIVATE_SENTINEL not in combined
    finally:
        if case == "empty":
            docker("volume", "rm", "--force", volume, check=False)


def test_runtime_image_layers_do_not_contain_private_key(tmp_path: Path) -> None:
    inspect = docker("image", "inspect", IMAGE)
    history = docker("history", "--no-trunc", IMAGE)
    assert PRIVATE_SENTINEL.decode().strip() not in inspect
    assert PRIVATE_SENTINEL.decode().strip() not in history
    archive = tmp_path / "agent-image.tar"
    docker("save", "--output", str(archive), IMAGE)
    digest = hashlib.sha256()
    found = False
    with archive.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
            if PRIVATE_SENTINEL in chunk:
                found = True
    assert digest.digest()
    assert not found
