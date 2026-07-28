#!/usr/bin/env python3
"""Write runtime OpenAPI JSON with deterministic key ordering and whitespace."""

from __future__ import annotations

import json
import sys
from pathlib import Path


def main() -> int:
    if len(sys.argv) != 2:
        print("usage: canonicalize_openapi.py <openapi.json>", file=sys.stderr)
        return 2
    path = Path(sys.argv[1])
    document = json.loads(path.read_text(encoding="utf-8"))
    path.write_text(
        json.dumps(
            document,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
