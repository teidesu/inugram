#!/usr/bin/env bash
# Checks sdk/types/android.notification-center.d.ts against the rebased stock NotificationCenter.
# Run after the stack is fully pushed, before `upstream-commit` is committed.
set -euo pipefail

root=$(git -C "$(dirname "$0")" rev-parse --show-toplevel)
centre="$root/worktree/TMessagesProj/src/main/java/org/telegram/messenger/NotificationCenter.java"
typings="$root/sdk/types/android.notification-center.d.ts"
old=$(git -C "$root" show HEAD:upstream-commit)
new=$(cat "$root/upstream-commit")

echo "== event names (< stock only, > typings only)"
diff \
  <(rg -o 'public static final int (\w+) = totalEvents\+\+' -r '$1' "$centre" | sort) \
  <(rg -o '^      (\w+)\(' -r '$1' "$typings" | sort) \
  && echo "in sync"

echo
echo "== events referenced on lines upstream changed ($old..$new)"
git -C "$root/worktree" diff -U0 "$old" "$new" -- TMessagesProj/src/main/java \
  | rg '^[+-]' \
  | rg -o 'NotificationCenter\.(\w+)' -r '$1' \
  | rg -v '^(getInstance|getGlobalInstance|NotificationCenterDelegate|totalEvents)$' \
  | sort -u
