#!/usr/bin/env bash
# T11: "CI drift check fails when generated spec/client out of sync." This half checks that
# src/schema.d.ts (committed) is exactly what openapi-typescript produces from the committed
# openapi.json right now — i.e. nobody hand-edited the generated file or forgot to regenerate it
# after editing openapi.json. The spec-vs-backend half of the drift check is
# services/api's OpenApiSpecDriftTest.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

tmp_file="$(mktemp)"
trap 'rm -f "$tmp_file"' EXIT

npx --yes openapi-typescript@7.13.0 openapi.json -o "$tmp_file" >/dev/null

if ! diff -u src/schema.d.ts "$tmp_file" >/tmp/schema-drift.diff; then
  echo "packages/contracts/src/schema.d.ts is out of date relative to openapi.json." >&2
  echo "Run \`pnpm --dir packages/contracts generate\` and commit the result. Diff:" >&2
  cat /tmp/schema-drift.diff >&2
  exit 1
fi

echo "packages/contracts/src/schema.d.ts matches openapi.json."
