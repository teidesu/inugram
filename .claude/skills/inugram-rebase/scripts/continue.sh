#!/usr/bin/env bash
# Advance the rebase: stage resolved conflicts, refresh the patch, push the rest.
# Bundles the error-prone loop tail and guards it:
#   - refuses if any conflict markers remain (so you never refresh markers in)
#   - stages with `git add -f` (the worktree java dir is gitignored)
#   - resolves cwd itself
# Stops at the next conflicting patch, so you still review each conflict in turn.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../../.." && pwd)"
cd "$REPO/worktree"

files="$(git diff --name-only --diff-filter=U)"
if [ -n "$files" ]; then
  bad=""
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    # only <<<<<<< / >>>>>>> are unambiguous; ======= alone can be real code
    if rg -q -a '^(<<<<<<<|>>>>>>>)' "$f"; then bad="$bad $f"; fi
  done <<< "$files"
  if [ -n "$bad" ]; then
    echo "ERROR: unresolved conflict markers still in:$bad" >&2
    echo "resolve them first, then re-run." >&2
    exit 1
  fi
  while IFS= read -r f; do
    [ -z "$f" ] && continue
    git add -f "$f"
  done <<< "$files"
  stg refresh
fi

stg push -a
