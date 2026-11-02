#!/usr/bin/env bash
set -euo pipefail
module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source "$module_dir/tests/kotlin-sources.sh"
: "${KOTLIN_COMPILER_CP:?Set Kotlin compiler jars classpath}"
: "${KOTLIN_STDLIB:?Set Kotlin stdlib jar}"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
for name in GuardianLexicalV31Simulation GuardianSpeechRescueRingSimulation; do
  sources=("$module_dir/tests/host/legacy/$name.kt")
  main="$name"Kt
  if [[ "$name" == GuardianLexicalV31Simulation ]]; then
    sources+=("$(guardian_source GuardianWordTimelineEngine.kt)")
    main="expo.modules.guardiannativeprotection.$main"
  fi
  java -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
    -classpath "$KOTLIN_STDLIB" -d "$build/$name.jar" "${sources[@]}"
  java -cp "$build/$name.jar:$KOTLIN_STDLIB" "$main"
done
