from __future__ import annotations

import argparse
import json
from datetime import UTC, datetime, timedelta
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

import jwt
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric import rsa


def generate_keys(directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    for name in ("user", "administrator", "agent-delegation", "agent-service"):
        private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
        (directory / f"{name}-private.pem").write_bytes(
            private.private_bytes(
                serialization.Encoding.PEM,
                serialization.PrivateFormat.PKCS8,
                serialization.NoEncryption(),
            )
        )
        (directory / f"{name}-public.pem").write_bytes(
            private.public_key().public_bytes(
                serialization.Encoding.PEM,
                serialization.PublicFormat.SubjectPublicKeyInfo,
            )
        )


def issue_user(directory: Path) -> str:
    now = datetime.now(UTC).replace(microsecond=0)
    return jwt.encode(
        {
            "iss": "https://auth.hotshop.local/user",
            "aud": "hotshop-portal-api",
            "sub": "42",
            "iat": now,
            "nbf": now,
            "exp": now + timedelta(minutes=15),
            "jti": "task17-compose-user",
            "token_use": "user_access",
            "preferred_username": "task17-user",
            "authorities": ["ROLE_USER"],
        },
        (directory / "user-private.pem").read_bytes(),
        algorithm="RS256",
        headers={"kid": "user-local-1", "typ": "user-access+jwt"},
    )


class FixtureHandler(BaseHTTPRequestHandler):
    key_directory: Path

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/agent/api/v1/auth/token-exchange":
            self._json(404, {"code": "NOT_FOUND"})
            return
        length = min(int(self.headers.get("Content-Length", "0")), 65536)
        body = json.loads(self.rfile.read(length))
        scopes = body.get("scopes")
        if not isinstance(scopes, list) or not all(isinstance(item, str) for item in scopes):
            self._json(400, {"code": "INVALID"})
            return
        now = datetime.now(UTC).replace(microsecond=0)
        token = jwt.encode(
            {
                "iss": "https://auth.hotshop.local/agent-delegation",
                "aud": "hotshop-agent-api",
                "sub": "42",
                "iat": now,
                "nbf": now,
                "exp": now + timedelta(minutes=5),
                "jti": "task17-compose-delegation",
                "token_use": "agent_delegation",
                "preferred_username": "task17-user",
                "azp": "hotshop-agent-service",
                "scope": " ".join(sorted(scopes)),
            },
            (self.key_directory / "agent-delegation-private.pem").read_bytes(),
            algorithm="RS256",
            headers={
                "kid": "agent-delegation-local-1",
                "typ": "agent-delegation+jwt",
            },
        )
        self._json(
            200,
            {
                "tokenType": "Bearer",
                "accessToken": token,
                "expiresAt": (now + timedelta(minutes=5)).isoformat(),
                "scopes": scopes,
            },
        )

    def do_GET(self) -> None:  # noqa: N802
        if self.path == "/health":
            self._json(200, {"status": "UP"})
            return
        if self.path == "/agent/api/v1/tools/products/101":
            self._json(
                200,
                {
                    "productId": "101",
                    "name": "Compose Live Product",
                    "price": "88.00",
                    "available": True,
                    "inventorySummary": "AVAILABLE",
                },
            )
            return
        self._json(404, {"code": "NOT_FOUND"})

    def log_message(self, _format: str, *_args: Any) -> None:
        return

    def _json(self, status: int, body: dict[str, Any]) -> None:
        encoded = json.dumps(body, separators=(",", ":")).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(encoded)))
        self.end_headers()
        self.wfile.write(encoded)


def serve(directory: Path) -> None:
    FixtureHandler.key_directory = directory
    ThreadingHTTPServer(("0.0.0.0", 8080), FixtureHandler).serve_forever()  # noqa: S104


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("generate-keys", "issue-user", "serve"))
    parser.add_argument("--directory", type=Path, required=True)
    args = parser.parse_args()
    if args.command == "generate-keys":
        generate_keys(args.directory)
    elif args.command == "issue-user":
        print(issue_user(args.directory))
    else:
        serve(args.directory)


if __name__ == "__main__":
    main()
