#!/usr/bin/env bash
set -euo pipefail
module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
src="$module_dir/android/src/main/java/expo/modules/guardiannativeprotection"
: "${KOTLIN_COMPILER_CP:?Set Kotlin compiler jars classpath}"
: "${KOTLIN_STDLIB:?Set Kotlin stdlib jar}"
: "${JSON_JAR:?Set org.json jar for host tests (Android supplies org.json at runtime)}"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
sources=()
for name in GuardianVoiceEngine GuardianVoiceUnderstandingEngine GuardianVoiceFusionEngine GuardianEventDelivery GuardianVoiceEventJournal GuardianVoiceEvent GuardianNativeEvent GuardianNativeConfig GuardianAsrContract GuardianWordTimelineEngine; do
  sources+=("$src/$name.kt")
done
sources+=("$module_dir/tests/host/VoiceRuntimeTest.kt" "$module_dir/tests/host/stubs/AndroidLog.kt" "$module_dir/tests/host/stubs/AndroidClock.kt")
sources+=("$module_dir"/tests/host/runtime-stubs/*.kt)
java -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$KOTLIN_STDLIB:$JSON_JAR" -d "$build/tests.jar" "${sources[@]}"
java -cp "$build/tests.jar:$KOTLIN_STDLIB:$JSON_JAR" expo.modules.guardiannativeprotection.VoiceRuntimeTest
