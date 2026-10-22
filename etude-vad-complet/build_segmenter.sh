#!/usr/bin/env bash
# Compile the production segmenter or a research-only 1200ms-tail copy.
# KOTLIN_COMPILER_CP and KOTLIN_STDLIB point to Kotlin compiler/runtime jars.
set -euo pipefail
: "${KOTLIN_COMPILER_CP:?Set Kotlin compiler classpath}"
: "${KOTLIN_STDLIB:?Set Kotlin stdlib jar}"
repo_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
output_directory="${1:?Provide a new output directory}"
tail_ms="${2:-800}"
mkdir -p "$output_directory"
python - "$repo_dir" "$output_directory" "$tail_ms" <<'PY'
import sys
from pathlib import Path
repo,out=map(Path,sys.argv[1:3]);tail=int(sys.argv[3]);assert tail in [800,1200]
source=repo/'modules/guardian-native-protection/android/src/main/java/expo/modules/guardiannativeprotection/GuardianSpeechSegmenter.kt'
s=source.read_text();assert s.count('const val TAIL_MS = 800')==1
p=out/'GuardianSpeechSegmenterStudy.kt';assert not p.exists()
p.write_text(s.replace('const val TAIL_MS = 800',f'const val TAIL_MS = {tail}'))
PY
java -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
  -no-stdlib -no-reflect -classpath "$KOTLIN_STDLIB" \
  "$output_directory/GuardianSpeechSegmenterStudy.kt" "$repo_dir/etude-vad-complet/ReplayStudy.kt" \
  -d "$output_directory/segmenter.jar"
