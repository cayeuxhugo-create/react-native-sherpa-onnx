#!/usr/bin/env bash
set -euo pipefail
module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
: "${ANDROID_JAR:?Set ANDROID_JAR to an Android API 35 or newer android.jar}"
: "${KOTLIN_COMPILER_CP:?Set Kotlin compiler classpath}"
: "${KOTLIN_STDLIB:?Set Kotlin stdlib path}"
build="$(mktemp -d)"
trap 'rm -rf "$build"' EXIT
sources=()
for name in GuardianAudioEngine GuardianCaptureChannelFrame GuardianChannelPreprocessor GuardianAudioCapabilities GuardianSpatialAudio GuardianAdaptiveChannels GuardianChannelDiagnostics GuardianDiagnosticWavWriter GuardianVoiceDiagnostics GuardianChannelQualityScores GuardianSpeechSegmentGain; do
  sources+=("$module_dir/android/src/main/java/expo/modules/guardiannativeprotection/$name.kt")
done
sources+=("$module_dir/tests/host/CaptureRouteGenerationTest.kt")
sources+=("$module_dir/tests/host/ChannelPreprocessorTest.kt")
java -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
  -classpath "$ANDROID_JAR:$KOTLIN_STDLIB" -d "$build/capture.jar" "${sources[@]}"
printf '%s\n' 'Capture and discovery Kotlin compilation passed against Android framework signatures (no APK/JNI/device test).'
java -cp "$build/capture.jar:$ANDROID_JAR:$KOTLIN_STDLIB" expo.modules.guardiannativeprotection.CaptureRouteGenerationTest
java -cp "$build/capture.jar:$ANDROID_JAR:$KOTLIN_STDLIB" expo.modules.guardiannativeprotection.ChannelPreprocessorTest
