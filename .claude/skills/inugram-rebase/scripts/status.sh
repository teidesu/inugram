#!/usr/bin/env bash
# Read-only rebase status. Resolves cwd itself, so run from anywhere.
# Prints the conflicting patch + subject and every conflict-marker location
# (with `rg -a`, so files containing a stray null byte still get scanned).
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$REPO/worktree"

echo "== current patch + subject =="
stg show 2>/dev/null | head -6 || true
echo

echo "== conflicted files =="
files="$(git diff --name-only --diff-filter=U)"
if [ -z "$files" ]; then
  echo "(none — stack may be fully applied)"
  exit 0
fi
echo "$files"
echo

echo "== markers =="
while IFS= read -r f; do
  [ -z "$f" ] && continue
  echo "--- $f"
  rg -n -a '^(<<<<<<<|=======|>>>>>>>)' "$f" || echo "  (no markers — resolved?)"
done <<< "$files"
