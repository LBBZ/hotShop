#!/usr/bin/env python3
"""Docker-only, isolated MySQL 8.0 -> 8.4 logical backup/restore rehearsal.

Run inside a Python + Docker CLI container with the repository and Docker socket
mounted, for example:
  python /workspace/script/tests/test_mysql84_restore.py

Only synthetic fixtures are used. No published ports, existing databases, or
existing volumes are accepted. Reports and the SQL backup are retained under
 .local/verification/modernization-20260930/mysql-upgrade/<unique-run-id>/.
This checks logical recovery, not an in-place data-directory or production upgrade.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import secrets
import shutil
import subprocess
import time
import uuid
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

TASK_LABEL = "com.hotshop.modernization.task"
RUN_LABEL = "com.hotshop.modernization.run"
TASK = "mysql84-restore"
DATABASE = "hotshop_restore_test"


class Rehearsal:
    def __init__(self, args: argparse.Namespace) -> None:
        self.root = Path(__file__).resolve().parents[2]
        self.docker_cli = shutil.which("docker") or "/usr/local/bin/docker"
        self.run_id = datetime.now(UTC).strftime("%Y%m%dT%H%M%SZ") + "-" + uuid.uuid4().hex[:8]
        self.prefix = "hotshop-modernize-mysql-restore-" + self.run_id.lower()
        self.output = args.report_dir / self.run_id
        self.output.mkdir(parents=True, exist_ok=False)
        self.images = {
            "source": args.source_image,
            "target": args.target_image,
            "flyway": args.flyway_image,
        }
        self.password = secrets.token_urlsafe(32)
        self.environment = dict(
            os.environ,
            MYSQL_ROOT_PASSWORD=self.password,
            MYSQL_PWD=self.password,
            FLYWAY_PASSWORD=self.password,
        )
        self.containers: list[str] = []
        self.network: str | None = None
        self.report: dict[str, Any] = {
            "run_id": self.run_id,
            "status": "running",
            "started_at": datetime.now(UTC).isoformat(),
            "mode": "isolated logical dump/restore; not an in-place upgrade",
            "database": DATABASE,
            "images": {},
            "checks": [],
            "cleanup": [],
            "boundaries": [
                "synthetic fixture data only",
                "no concurrent application writers or DDL",
                "no production traffic or large-volume timing test",
                "does not migrate MySQL users/grants or system schemas",
                "no replication, binlog/PITR, or physical data-directory upgrade tested",
            ],
        }

    def redact(self, value: str) -> str:
        return value.replace(self.password, "[REDACTED]")

    def docker(
        self, *args: str, data: bytes | None = None, timeout: int = 180, check: bool = True
    ) -> subprocess.CompletedProcess[bytes]:
        # Arguments are internal Docker commands or explicit CLI image options, never shell code.
        result = subprocess.run(  # noqa: S603
            [self.docker_cli, *args],
            input=data,
            capture_output=True,
            env=self.environment,
            timeout=timeout,
        )
        if check and result.returncode:
            detail = self.redact((result.stderr + result.stdout).decode(errors="replace"))
            raise RuntimeError(f"Docker {args[0]} failed ({result.returncode}): {detail[-6000:]}")
        return result

    def check(self, name: str, condition: bool, detail: Any) -> None:
        self.report["checks"].append({"name": name, "passed": bool(condition), "detail": detail})
        if not condition:
            raise AssertionError(name + ": " + json.dumps(detail, ensure_ascii=False))
        print("PASS: " + name, flush=True)

    def labelled(self) -> list[str]:
        return ["--label", f"{TASK_LABEL}={TASK}", "--label", f"{RUN_LABEL}={self.run_id}"]

    def inspect(self, kind: str, name: str) -> dict[str, Any]:
        return json.loads(self.docker(kind, "inspect", name).stdout)[0]

    def create_mysql(self, role: str) -> str:
        name = self.prefix + "-" + role
        self.containers.append(name)
        self.docker(
            "run",
            "--detach",
            "--name",
            name,
            *self.labelled(),
            "--network",
            str(self.network),
            "--network-alias",
            role,
            "--memory",
            "768m",
            "--cpus",
            "1",
            "-e",
            "MYSQL_ROOT_PASSWORD",
            "-e",
            "MYSQL_ROOT_HOST=%",
            "-e",
            f"MYSQL_DATABASE={DATABASE}",
            self.images[role],
            "--innodb-buffer-pool-size=134217728",
            "--max-connections=30",
            "--performance-schema=OFF",
        )
        config = self.inspect("container", name)
        self.check(
            role + " has no published ports",
            not config["HostConfig"]["PortBindings"],
            config["HostConfig"]["PortBindings"],
        )
        self.check(
            role + " owns fresh anonymous storage",
            all(m["Type"] == "volume" for m in config["Mounts"]),
            [{"type": m["Type"], "destination": m["Destination"]} for m in config["Mounts"]],
        )
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            probe = self.sql(name, "SELECT 1;", check=False)
            if probe.returncode == 0:
                version = self.sql(name, "SELECT VERSION();").stdout.decode().strip()
                expected = "8.0.46" if role == "source" else "8.4.11"
                self.check(role + " server version", version == expected, version)
                return name
            time.sleep(2)
        raise TimeoutError(role + " MySQL did not become ready within 180 seconds")

    def sql(
        self, container: str, statement: str | bytes, check: bool = True
    ) -> subprocess.CompletedProcess[bytes]:
        data = statement.encode() if isinstance(statement, str) else statement
        return self.docker(
            "exec",
            "--interactive",
            "-e",
            "MYSQL_PWD",
            container,
            "mysql",
            "--user=root",
            "--protocol=TCP",
            "--host=127.0.0.1",
            "--batch",
            "--skip-column-names",
            "--default-character-set=utf8mb4",
            DATABASE,
            data=data,
            check=check,
        )

    def flyway(self, database: str, stage: str, command: str, target: str) -> str:
        name = self.prefix + "-" + stage
        self.containers.append(name)
        hostname = database.rsplit("-", 1)[-1]
        self.docker(
            "create",
            "--name",
            name,
            *self.labelled(),
            "--network",
            str(self.network),
            "--memory",
            "384m",
            "-e",
            "FLYWAY_PASSWORD",
            "-e",
            "JAVA_TOOL_OPTIONS=-Xmx192m",
            self.images["flyway"],
            (
                f"-url=jdbc:mysql://{hostname}:3306/{DATABASE}"
                "?useSSL=false&allowPublicKeyRetrieval=true"
            ),
            "-connectRetries=10",
            "-user=root",
            "-locations=filesystem:/flyway/sql",
            "-baselineOnMigrate=true",
            "-baselineVersion=0",
            f"-target={target}",
            command,
        )
        migration_directory = self.root / "database/src/main/resources/db/migration"
        self.docker("cp", str(migration_directory) + "/.", name + ":/flyway/sql")
        result = self.docker("start", "--attach", name, check=False)
        log = self.redact((result.stdout + result.stderr).decode(errors="replace"))
        (self.output / (stage + ".log")).write_text(log, encoding="utf-8")
        state = self.inspect("container", name)["State"]
        self.check(
            stage + " succeeded",
            result.returncode == 0 and state["ExitCode"] == 0,
            {"cli_exit": result.returncode, "container_exit": state["ExitCode"]},
        )
        return log

    def fingerprints(self, container: str) -> dict[str, Any]:
        tables = (
            self.sql(
                container,
                (
                    "SELECT table_name FROM information_schema.tables WHERE "
                    "table_schema=DATABASE() AND table_type='BASE TABLE' ORDER BY "
                    "table_name;"
                ),
            )
            .stdout.decode()
            .splitlines()
        )
        result = {}
        for table in tables:
            # This metadata comes only from the synthetic database created by this run.
            if not table.isascii() or not table.replace("_", "").isalnum():
                raise AssertionError("Unexpected fixture table identifier: " + table)
            quoted = "`" + table.replace("`", "``") + "`"
            primary = (
                self.sql(
                    container,
                    (  # noqa: S608 - table name validated above.
                        "SELECT column_name FROM information_schema.statistics WHERE "
                        "table_schema=DATABASE() AND table_name='"
                    )
                    + table
                    + "' AND index_name='PRIMARY' ORDER BY seq_in_index;",
                )
                .stdout.decode()
                .splitlines()
            )
            if not primary:
                raise AssertionError("Fingerprint requires stable primary key: " + table)
            order = ",".join("`" + column.replace("`", "``") + "`" for column in primary)
            rows = self.sql(
                container,
                f"SELECT * FROM {quoted} ORDER BY {order};",  # noqa: S608 - quoted metadata.
            ).stdout
            count = int(
                self.sql(container, f"SELECT COUNT(*) FROM {quoted};").stdout  # noqa: S608
            )
            result[table] = {"rows": count, "sha256": hashlib.sha256(rows).hexdigest()}
        return result

    def migration_version(self, container: str) -> str:
        return (
            self.sql(
                container,
                (
                    "SELECT version FROM flyway_schema_history WHERE success=1 ORDER "
                    "BY installed_rank DESC LIMIT 1;"
                ),
            )
            .stdout.decode()
            .strip()
        )

    def run(self) -> None:
        for role, image in self.images.items():
            details = self.inspect("image", image)
            self.report["images"][role] = {
                "tag": image,
                "id": details["Id"],
                "repo_digests": details.get("RepoDigests", []),
            }
        self.network = self.prefix + "-network"
        self.docker("network", "create", "--internal", *self.labelled(), self.network)
        source = self.create_mysql("source")
        self.sql(
            source,
            (self.root / "database/src/test/resources/legacy/legacy-schema.sql").read_bytes(),
        )
        self.sql(
            source,
            """
INSERT INTO `user` (user_id,username,password,email,role,created_at)
VALUES (2,'restore-fixture','not-a-real-password-hash','fixture@example.invalid',
'ROLE_USER','2025-01-02 03:04:05');
INSERT INTO product (product_id,name,price,stock,category,description,created_at)
VALUES (2,'历史中文商品 🛒',12345.67,23,'恢复演练','UTF-8 supplementary-plane fixture',
'2025-01-02 03:04:05');
INSERT INTO `order` (order_id,user_id,total_amount,status,created_at)
VALUES ('restore-paid-order',2,24691.34,'PAID','2025-01-03 04:05:06');
INSERT INTO order_item (order_item_id,order_id,product_id,quantity,price)
VALUES (2,'restore-paid-order',2,2,12345.67);
""",
        )
        self.flyway(source, "source-migrate", "migrate", "1.10")
        self.flyway(source, "source-validate", "validate", "1.10")
        self.check(
            "source migration version is 1.10",
            self.migration_version(source) == "1.10",
            self.migration_version(source),
        )
        nontransactional = (
            self.sql(
                source,
                (
                    "SELECT table_name,engine FROM information_schema.tables WHERE "
                    "table_schema=DATABASE() AND table_type='BASE TABLE' AND engine <> "
                    "'InnoDB';"
                ),
            )
            .stdout.decode()
            .strip()
        )
        self.check(
            "all source tables support consistent snapshot", not nontransactional, nontransactional
        )
        source_fingerprints = self.fingerprints(source)
        self.report["source_tables"] = source_fingerprints
        backup = self.docker(
            "exec",
            "-e",
            "MYSQL_PWD",
            source,
            "mysqldump",
            "--user=root",
            "--single-transaction",
            "--quick",
            "--routines",
            "--events",
            "--triggers",
            "--set-gtid-purged=OFF",
            "--no-tablespaces",
            "--hex-blob",
            "--default-character-set=utf8mb4",
            "--databases",
            DATABASE,
        ).stdout
        (self.output / "mysql80-consistent-backup.sql").write_bytes(backup)
        self.report["backup"] = {
            "file": "mysql80-consistent-backup.sql",
            "bytes": len(backup),
            "sha256": hashlib.sha256(backup).hexdigest(),
            "options": [
                "--single-transaction",
                "--quick",
                "--routines",
                "--events",
                "--triggers",
                "--set-gtid-purged=OFF",
                "--no-tablespaces",
                "--hex-blob",
                "--default-character-set=utf8mb4",
            ],
            "contains": (
                "synthetic fixture application schema and Flyway history; no mysql system schema"
            ),
        }
        self.docker("stop", "--time", "30", source)
        target = self.create_mysql("target")
        self.sql(target, backup)
        restored_fingerprints = self.fingerprints(target)
        self.report["restored_tables_before_migration"] = restored_fingerprints
        self.check(
            "all source table rows survive logical restore exactly",
            source_fingerprints == restored_fingerprints,
            {
                "tables_compared": len(source_fingerprints),
                "rows_compared": sum(t["rows"] for t in source_fingerprints.values()),
            },
        )
        self.check(
            "restored migration version remains 1.10",
            self.migration_version(target) == "1.10",
            self.migration_version(target),
        )
        self.flyway(target, "target-validate-before", "validate", "1.10")
        self.flyway(target, "target-migrate", "migrate", "1.11")
        self.flyway(target, "target-validate-after", "validate", "1.11")
        self.check(
            "target migration version is 1.11",
            self.migration_version(target) == "1.11",
            self.migration_version(target),
        )
        migrated_fingerprints = self.fingerprints(target)
        self.report["target_tables_after_migration"] = migrated_fingerprints
        preserved = {
            key: value
            for key, value in source_fingerprints.items()
            if key != "flyway_schema_history"
        }
        self.check(
            "1.11 preserves all existing application table rows",
            all(migrated_fingerprints.get(key) == value for key, value in preserved.items()),
            {"tables_compared": len(preserved)},
        )
        self.check(
            "1.11 adds only the expected marker table",
            set(migrated_fingerprints) - set(source_fingerprints) == {"security_token_marker"},
            sorted(set(migrated_fingerprints) - set(source_fingerprints)),
        )
        facts = (
            self.sql(
                target,
                (
                    "SELECT "
                    "p.name,p.price,p.stock,p.expected_stock,o.total_amount,o.status,"
                    "i.quantity,i.line_amount FROM catalog_product p JOIN "
                    "sales_order_item i ON i.product_id=p.product_id JOIN sales_order "
                    "o ON o.order_id=i.order_id WHERE o.order_id='restore-paid-order';"
                ),
            )
            .stdout.decode()
            .strip()
        )
        self.check(
            "historical Unicode, decimals, stock and paid order preserved",
            facts == "历史中文商品 🛒\t12345.67\t23\t23\t24691.34\tPAID\t2\t24691.34",
            facts,
        )
        insert = (
            "INSERT INTO "
            "security_token_marker(marker_type,token_hash,expires_at) "
            "VALUES('REVOKED_ACCESS',REPEAT('a',64),UTC_TIMESTAMP(6)+INTERVAL "
            "1 HOUR);"
        )
        self.sql(target, insert)
        duplicate = self.sql(target, insert, check=False)
        self.check(
            "marker primary key rejects duplicate revocation",
            duplicate.returncode != 0 and b"ERROR 1062" in duplicate.stderr,
            self.redact(duplicate.stderr.decode().strip()),
        )
        self.sql(
            target,
            (
                "INSERT INTO "
                "security_token_marker(marker_type,token_hash,expires_at) "
                "VALUES('CLIENT_ASSERTION',REPEAT('a',64),UTC_TIMESTAMP(6)+INTERV"
                "AL 1 HOUR);"
            ),
        )
        invalid = self.sql(
            target,
            (
                "INSERT INTO "
                "security_token_marker(marker_type,token_hash,expires_at) "
                "VALUES('INVALID',REPEAT('b',64),UTC_TIMESTAMP(6)+INTERVAL 1 "
                "HOUR);"
            ),
            check=False,
        )
        self.check(
            "marker constraint rejects unknown type",
            invalid.returncode != 0 and b"chk_security_token_marker_type" in invalid.stderr,
            self.redact(invalid.stderr.decode().strip()),
        )
        count = int(self.sql(target, "SELECT COUNT(*) FROM security_token_marker;").stdout)
        self.check("marker types occupy separate namespaces", count == 2, count)
        index = (
            self.sql(
                target,
                (
                    "SELECT column_name FROM information_schema.statistics WHERE "
                    "table_schema=DATABASE() AND table_name='security_token_marker' "
                    "AND index_name='idx_security_token_marker_expiry';"
                ),
            )
            .stdout.decode()
            .strip()
        )
        self.check("marker expiry cleanup index exists", index == "expires_at", index)
        self.report["status"] = "passed"

    def cleanup(self) -> None:
        for name in reversed(self.containers):
            try:
                inspection = self.docker("container", "inspect", name, check=False)
                if inspection.returncode:
                    continue
                config = json.loads(inspection.stdout)[0]
                labels = config["Config"].get("Labels") or {}
                if labels.get(RUN_LABEL) != self.run_id or labels.get(TASK_LABEL) != TASK:
                    raise RuntimeError(
                        "Refusing to clean container with mismatched ownership labels: " + name
                    )
                volumes = [mount["Name"] for mount in config["Mounts"] if mount["Type"] == "volume"]
                self.docker("rm", "--force", "--volumes", config["Id"])
                remaining = [
                    volume
                    for volume in volumes
                    if self.docker("volume", "inspect", volume, check=False).returncode == 0
                ]
                if remaining:
                    raise RuntimeError(
                        "Anonymous volumes remained after removing owned container: "
                        + repr(remaining)
                    )
                self.report["cleanup"].append(
                    {"resource": name, "removed": True, "anonymous_volumes_removed": len(volumes)}
                )
            except Exception as error:
                self.report["cleanup"].append(
                    {"resource": name, "removed": False, "error": self.redact(str(error))}
                )
        if self.network:
            try:
                inspection = self.docker("network", "inspect", self.network, check=False)
                if inspection.returncode == 0:
                    labels = json.loads(inspection.stdout)[0].get("Labels") or {}
                    if labels.get(RUN_LABEL) != self.run_id or labels.get(TASK_LABEL) != TASK:
                        raise RuntimeError(
                            "Refusing to clean network with mismatched ownership labels"
                        )
                    self.docker("network", "rm", self.network)
                    self.report["cleanup"].append({"resource": self.network, "removed": True})
            except Exception as error:
                self.report["cleanup"].append(
                    {"resource": self.network, "removed": False, "error": self.redact(str(error))}
                )
        if any(not entry["removed"] for entry in self.report["cleanup"]):
            self.report["status"] = "failed"


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--source-image", default="mysql:8.0.46")
    parser.add_argument("--target-image", default="mysql:8.4.11")
    parser.add_argument("--flyway-image", default="flyway/flyway:11.20.3-alpine")
    parser.add_argument(
        "--report-dir",
        "--output",
        type=Path,
        default=Path(__file__).resolve().parents[2]
        / ".local/verification/modernization-20260930/mysql-upgrade",
    )
    args = parser.parse_args()
    if not Path("/.dockerenv").exists() or shutil.which("docker") is None:
        parser.error(
            "Run inside a Docker container with Python, Docker CLI, and a mounted Docker socket."
        )
    test = Rehearsal(args)
    try:
        test.run()
    except (Exception, KeyboardInterrupt) as error:
        test.report["status"] = "failed"
        test.report["error"] = test.redact(str(error))
        print("FAIL: " + test.redact(str(error)), flush=True)
    finally:
        test.cleanup()
        test.report["finished_at"] = datetime.now(UTC).isoformat()
        report_path = test.output / "report.json"
        report_path.write_text(
            json.dumps(test.report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
        )
        print("Report: " + str(report_path), flush=True)
    return 0 if test.report["status"] == "passed" else 1


if __name__ == "__main__":
    raise SystemExit(main())
