#!/usr/bin/env bash
set -euo pipefail

BASE="${GRUBBY_URL%/}"
REPO_URL="${REPO_URL:-https://github.com/${GH_REPO}.git}"
BRANCH="${BRANCH:-${GITHUB_REF_NAME:-}}"
FORMAT="${OUTPUT_FORMAT:-md}"
TIMEOUT="${POLL_TIMEOUT:-600}"

echo "::group::Grubby — Authenticate"
LOGIN_RESP=$(curl -sf -X POST "${BASE}/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"email\":\"${GRUBBY_EMAIL}\",\"password\":\"${GRUBBY_PASSWORD}\"}")
TOKEN=$(echo "$LOGIN_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['token'])")
echo "Authenticated successfully"
echo "::endgroup::"

echo "::group::Grubby — Submit BRD job"
BODY=$(python3 -c "
import json, sys, os
req = {
  'repoUrl': os.environ.get('REPO_URL', ''),
  'branch':  os.environ.get('BRANCH', '') or None,
  'commitSha': os.environ.get('GH_SHA', '') or None,
}
fc = os.environ.get('FEATURE_CONTEXT', '').strip()
if fc:
    req['featureContext'] = fc
ai = os.environ.get('AI_MODEL', '').strip()
if ai:
    req['aiModel'] = ai
print(json.dumps({k: v for k, v in req.items() if v}))
")
SUBMIT_RESP=$(curl -sf -X POST "${BASE}/api/v1/brd/generate" \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer ${TOKEN}" \
  -d "$BODY")
BRD_ID=$(echo "$SUBMIT_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['requestId'])")
echo "BRD job submitted: ID=${BRD_ID}"
echo "::endgroup::"

echo "::group::Grubby — Poll for completion"
ELAPSED=0
STATUS=""
while true; do
  STATUS_RESP=$(curl -sf "${BASE}/api/v1/brd/${BRD_ID}/status" \
    -H "Authorization: Bearer ${TOKEN}")
  STATUS=$(echo "$STATUS_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin)['status'])")
  STAGE=$(echo "$STATUS_RESP"  | python3 -c "import sys,json; print(json.load(sys.stdin).get('stage',''))")
  PCT=$(echo "$STATUS_RESP"    | python3 -c "import sys,json; print(json.load(sys.stdin).get('progressPct',0))")
  echo "  Status: ${STATUS} | Stage: ${STAGE} | Progress: ${PCT}%"

  if [[ "$STATUS" == "COMPLETED" || "$STATUS" == "FAILED" ]]; then
    break
  fi

  if (( ELAPSED >= TIMEOUT )); then
    echo "::error::Timed out after ${TIMEOUT}s waiting for BRD to complete"
    exit 1
  fi

  sleep 15
  ELAPSED=$((ELAPSED + 15))
done

if [[ "$STATUS" != "COMPLETED" ]]; then
  ERROR=$(echo "$STATUS_RESP" | python3 -c "import sys,json; print(json.load(sys.stdin).get('errorMessage','unknown error'))")
  echo "::error::BRD generation failed: ${ERROR}"
  exit 1
fi
echo "BRD completed successfully"
echo "::endgroup::"

echo "::group::Grubby — Download artifact"
curl -sf "${BASE}/api/v1/brd/${BRD_ID}/download?format=${FORMAT}" \
  -H "Authorization: Bearer ${TOKEN}" \
  -o "brd-${BRD_ID}.${FORMAT}"
echo "Downloaded brd-${BRD_ID}.${FORMAT}"
echo "::endgroup::"

# ── Outputs ──────────────────────────────────────────────────────────────────
echo "brd-id=${BRD_ID}" >> "$GITHUB_OUTPUT"
echo "brd-url=${BASE}/brd/${BRD_ID}" >> "$GITHUB_OUTPUT"

# ── Optional: post a PR comment ──────────────────────────────────────────────
if [[ -n "${GITHUB_TOKEN_INPUT:-}" && -n "${GH_PR_NUMBER:-}" ]]; then
  echo "::group::Grubby — Post PR comment"
  PREVIEW=$(curl -sf "${BASE}/api/v1/brd/${BRD_ID}/preview?format=markdown" \
    -H "Authorization: Bearer ${TOKEN}" | \
    python3 -c "import sys,json; c=json.load(sys.stdin).get('content',''); print(c[:3000]+'...' if len(c)>3000 else c)")

  COMMENT_BODY=$(python3 -c "
import json, os
body = '''### Grubby BRD Generated

**Job ID:** {id}
**Model:** {model}

<details>
<summary>Preview (first 3000 chars)</summary>

\`\`\`markdown
{preview}
\`\`\`
</details>

[View full BRD]({url})
'''.format(
    id=os.environ['BRD_ID'],
    model=os.environ.get('AI_MODEL','server default'),
    preview=os.environ['PREVIEW'],
    url='{base}/brd/{id}'.format(base=os.environ['BASE'], id=os.environ['BRD_ID'])
)
print(json.dumps({'body': body}))
" BRD_ID="$BRD_ID" PREVIEW="$PREVIEW" BASE="$BASE")

  curl -sf -X POST \
    "https://api.github.com/repos/${GH_REPO}/issues/${GH_PR_NUMBER}/comments" \
    -H "Authorization: Bearer ${GITHUB_TOKEN_INPUT}" \
    -H "Content-Type: application/json" \
    -d "$COMMENT_BODY"
  echo "PR comment posted"
  echo "::endgroup::"
fi
