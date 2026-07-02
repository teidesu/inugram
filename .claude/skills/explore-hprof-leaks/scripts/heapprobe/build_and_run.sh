#!/usr/bin/env bash
# Build (once) + run the HeapProbe OSGi bundle inside Eclipse MAT.
#
# Usage:
#   MAT=/Applications/MemoryAnalyzer.app ./build_and_run.sh <dump.ec.hprof> <command> [args...]
#
# Examples:
#   ./build_and_run.sh dump.ec.hprof counts org.telegram.ui.ChatActivity
#   ./build_and_run.sh dump.ec.hprof tally org.telegram.ui.ChatActivity ChatBackgroundDrawable,LaunchActivity
#   ./build_and_run.sh dump.ec.hprof paths 'org.telegram.ui.Components.Paint.Views.LPhotoPaintView$2'
#   ./build_and_run.sh dump.ec.hprof observers org.telegram.ui.Adapters.MentionsAdapter
#
# Java is NOT available inside the agent sandbox: run this with the sandbox disabled.
# On a $-containing class name, single-quote the arg so the shell doesn't eat it.
set -euo pipefail

MAT="${MAT:-/Applications/MemoryAnalyzer.app}"
ECLIPSE="$MAT/Contents/Eclipse"
PLUGINS="$ECLIPSE/plugins"
BUNDLES="$ECLIPSE/configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
HERE="$(cd "$(dirname "$0")" && pwd)"
JAR="$PLUGINS/heapprobe_1.0.0.jar"

if [ $# -lt 2 ]; then
  echo "usage: build_and_run.sh <dump> <command> [args...]"; exit 2
fi
DUMP="$1"; shift

# Kill stray MAT processes first. A leftover MemoryAnalyzer from a prior run holds
# the equinox '-clean' config lock, and the next invocation silently blocks (near-zero
# CPU, tiny RSS, never progresses) instead of erroring. This is the #1 "it's stuck".
pkill -f "MemoryAnalyzer.*-application" 2>/dev/null || true

# (Re)build the jar if missing or the source changed.
if [ ! -f "$JAR" ] || [ "$HERE/HeapProbeApp.java" -nt "$JAR" ]; then
  echo ">> building heapprobe bundle"
  # All plugin jars on the compile classpath — the parser/report/equinox deps
  # (IProgressMonitor lives in equinox.common, not core.runtime) are easy to miss otherwise.
  CP=$(find "$PLUGINS" -name '*.jar' 2>/dev/null | tr '\n' ':')
  rm -rf "$HERE/bin"; mkdir -p "$HERE/bin"
  javac -cp "$CP" -d "$HERE/bin" "$HERE/HeapProbeApp.java"
  cp "$HERE/plugin.xml" "$HERE/bin/"
  ( cd "$HERE/bin" && jar cfm "$JAR" "$HERE/META-INF/MANIFEST.MF" HeapProbeApp*.class plugin.xml )
  echo ">> installed $JAR"
fi

# Register in bundles.info once (idempotent). Back it up the first time.
if ! grep -q '^heapprobe,' "$BUNDLES"; then
  cp "$BUNDLES" "$BUNDLES.bak"
  echo 'heapprobe,1.0.0,plugins/heapprobe_1.0.0.jar,4,true' >> "$BUNDLES"
  echo ">> registered heapprobe in bundles.info"
fi

exec "$MAT/Contents/MacOS/MemoryAnalyzer" -consolelog -nosplash -clean \
  -application heapprobe.app "$DUMP" "$@"
