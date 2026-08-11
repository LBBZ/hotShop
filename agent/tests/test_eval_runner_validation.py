from __future__ import annotations

import json
import subprocess
import sys
from pathlib import Path
from typing import Any

import pytest

from hotshop_agent.eval_runner import (
    DatasetValidationError,
    load_eval_dataset,
    run,
    select_suite_cases,
)

ROOT = Path(__file__).resolve().parents[1]
DATASET = ROOT / "evals" / "task17-v2.jsonl"
KNOWLEDGE = ROOT / "knowledge"
PRIVATE_QUERY = "private-query-must-not-appear-in-errors"


def read_cases() -> list[dict[str, Any]]:
    return [json.loads(line) for line in DATASET.read_text(encoding="utf-8").splitlines()]


def write_cases(path: Path, cases: list[dict[str, Any]]) -> Path:
    path.write_text(
        "\n".join(json.dumps(case, ensure_ascii=False) for case in cases) + "\n",
        encoding="utf-8",
    )
    return path


def route_case() -> dict[str, Any]:
    case = next(case for case in read_cases() if case["action"] == "route")
    case["query"] = PRIVATE_QUERY
    return case


def assert_dataset_error(path: Path, expected: str) -> None:
    with pytest.raises(DatasetValidationError, match=expected) as caught:
        load_eval_dataset(path)
    assert PRIVATE_QUERY not in str(caught.value)


def test_current_task17_v2_dataset_is_valid_and_covers_both_suites() -> None:
    cases = load_eval_dataset(DATASET)
    assert len(cases) == 29
    assert len(select_suite_cases(cases, "quick")) == 28
    assert len(select_suite_cases(cases, "full")) == 29


def test_empty_dataset_is_rejected(tmp_path: Path) -> None:
    path = tmp_path / "empty.jsonl"
    path.write_text("\n", encoding="utf-8")
    assert_dataset_error(path, "dataset is empty")


def test_duplicate_id_is_rejected(tmp_path: Path) -> None:
    case = route_case()
    assert_dataset_error(write_cases(tmp_path / "duplicate.jsonl", [case, case]), "duplicate id")


def test_missing_required_suite_category_is_rejected(tmp_path: Path) -> None:
    cases = [case for case in read_cases() if case["category"] != "retrieval"]
    with pytest.raises(DatasetValidationError, match="missing required categories: retrieval"):
        load_eval_dataset(write_cases(tmp_path / "missing-category.jsonl", cases))


def test_dataset_must_contain_cases_for_every_suite(tmp_path: Path) -> None:
    cases = [case for case in read_cases() if "quick" in case["suites"]]
    for case in cases:
        case["suites"] = ["quick"]
    with pytest.raises(DatasetValidationError, match="no cases for suite full"):
        load_eval_dataset(write_cases(tmp_path / "quick-only.jsonl", cases))


def test_unknown_category_is_rejected_without_query_leak(tmp_path: Path) -> None:
    case = route_case()
    case["category"] = "unknown"
    assert_dataset_error(write_cases(tmp_path / "unknown-category.jsonl", [case]), "category")


def test_unknown_action_is_rejected_without_query_leak(tmp_path: Path) -> None:
    case = route_case()
    case["action"] = "unknown"
    assert_dataset_error(write_cases(tmp_path / "unknown-action.jsonl", [case]), "action")


def test_unknown_suite_is_rejected_without_query_leak(tmp_path: Path) -> None:
    case = route_case()
    case["suites"] = ["quick", "nightly"]
    assert_dataset_error(write_cases(tmp_path / "unknown-suite.jsonl", [case]), "suites")


def test_action_specific_field_is_required(tmp_path: Path) -> None:
    case = route_case()
    del case["expectedTool"]
    assert_dataset_error(
        write_cases(tmp_path / "missing-action-field.jsonl", [case]),
        "missing action field\\(s\\): expectedTool",
    )


def test_extra_unknown_field_is_rejected_without_query_leak(tmp_path: Path) -> None:
    case = route_case()
    case["modelOverride"] = "attacker"
    assert_dataset_error(write_cases(tmp_path / "extra-field.jsonl", [case]), "modelOverride")


def test_invalid_json_is_rejected_without_raw_content(tmp_path: Path) -> None:
    path = tmp_path / "invalid.jsonl"
    path.write_text(f'{{"query":"{PRIVATE_QUERY}"', encoding="utf-8")
    assert_dataset_error(path, "not valid JSON")


@pytest.mark.parametrize("invalid_kind", ("empty", "duplicate", "missing_category"))
def test_cli_returns_nonzero_for_gate_bypass_datasets(
    invalid_kind: str,
    tmp_path: Path,
) -> None:
    dataset = tmp_path / f"{invalid_kind}.jsonl"
    if invalid_kind == "empty":
        dataset.write_text("", encoding="utf-8")
    elif invalid_kind == "duplicate":
        case = route_case()
        write_cases(dataset, [case, case])
    else:
        write_cases(
            dataset,
            [case for case in read_cases() if case["category"] != "retrieval"],
        )
    output = tmp_path / "result.json"
    completed = subprocess.run(  # noqa: S603
        [
            sys.executable,
            "-m",
            "hotshop_agent.eval_runner",
            "--suite",
            "quick",
            "--dataset",
            str(dataset),
            "--knowledge",
            str(KNOWLEDGE),
            "--output",
            str(output),
        ],
        cwd=ROOT,
        capture_output=True,
        text=True,
        check=False,
    )
    assert completed.returncode == 2
    assert "evaluation dataset validation failed" in completed.stderr
    assert PRIVATE_QUERY not in completed.stderr
    assert not output.exists()


@pytest.mark.asyncio
async def test_one_failed_case_forces_passed_thresholds_false(tmp_path: Path) -> None:
    cases = read_cases()
    failed_id = "dynamic-price-001"
    case = next(item for item in cases if item["id"] == failed_id)
    case["expectedTool"] = "list_my_orders"
    dataset = write_cases(tmp_path / "one-failure.jsonl", cases)

    result = await run("quick", dataset, KNOWLEDGE)

    assert result["total"] == 28
    assert result["failedCaseIds"] == [failed_id]
    assert result["categories"]["dynamic_routing"]["met"] is False
    assert result["passedThresholds"] is False
