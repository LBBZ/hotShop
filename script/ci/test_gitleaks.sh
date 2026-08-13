#!/bin/sh
set -eu

GITLEAKS_CONFIG=${GITLEAKS_CONFIG:-/repo/.gitleaks.toml}
fixture_dir=$(mktemp -d)
report_file=$(mktemp)
trap 'rm -rf "$fixture_dir" "$report_file"' EXIT HUP INT TERM

# Assemble a recognizable synthetic credential only at runtime. Keeping the pieces
# separate ensures this negative test does not add a complete key-like string to Git.
prefix='AK'
prefix="${prefix}IA"
body='Q7W6E2R4'
suffix='T5Y3U7I2'
synthetic="${prefix}${body}${suffix}"
mkdir -p "$fixture_dir/agent/src/hotshop_agent"
printf '%s\n' "$synthetic" > "$fixture_dir/synthetic.txt"
printf '%s\n' "$synthetic" > "$fixture_dir/agent/src/hotshop_agent/events.py"

if gitleaks dir --redact --config "$GITLEAKS_CONFIG" --no-banner \
  --report-format json --report-path "$report_file" "$fixture_dir" >/dev/null 2>&1; then
  printf '%s\n' 'gitleaks negative test failed: synthetic credential was not detected' >&2
  exit 1
fi

if grep -F "$synthetic" "$report_file" >/dev/null 2>&1; then
  printf '%s\n' 'gitleaks negative test failed: report was not redacted' >&2
  exit 1
fi

finding_count=$(grep -cF '"RuleID": "aws-access-token"' "$report_file" || true)
if [ "$finding_count" -ne 2 ]; then
  printf '%s\n' 'gitleaks negative test failed: expected default AWS rule did not fire' >&2
  exit 1
fi

if ! grep -F 'agent/src/hotshop_agent/events.py' "$report_file" >/dev/null 2>&1; then
  printf '%s\n' 'gitleaks negative test failed: production-path credential was hidden' >&2
  exit 1
fi

printf '%s\n' 'gitleaks synthetic negative test passed'
