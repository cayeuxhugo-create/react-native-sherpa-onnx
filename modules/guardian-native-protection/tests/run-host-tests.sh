#!/usr/bin/env bash
set -euo pipefail
module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$module_dir/android/src/main/java/expo/modules/guardiannativeprotection"
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
sources=(
  "$source_dir/GuardianCaptureChannelFrame.kt"
  "$source_dir/GuardianChannelPreprocessor.kt"
  "$source_dir/GuardianChannelWindowHistory.kt"
  "$source_dir/GuardianWienerChannelBank.kt"
  "$source_dir/GuardianWienerEnhancer.kt"
  "$source_dir/GuardianWienerStudyStream.kt"
  "$module_dir/tests/host/WienerEnhancerTest.kt"
  "$module_dir/tests/host/WienerStudyTest.kt"
  "$module_dir/tests/host/WienerChannelBankTest.kt"
  "$module_dir/tests/host/CaptureChannelRevisionTest.kt"
  "$module_dir/tests/host/ChannelPreprocessorTest.kt"
  "$module_dir/tests/host/ChannelWindowHistoryTest.kt"
  "$source_dir/GuardianAdaptiveChannels.kt"
  "$module_dir/tests/host/AdaptiveChannelsTest.kt"
  "$source_dir/GuardianStudyBuffer.kt"
  "$module_dir/tests/host/ContinuousStudyTest.kt"
  "$source_dir/GuardianChannelQualityScores.kt"
  "$source_dir/GuardianSpeechSegmentGain.kt"
  "$source_dir/GuardianSpatialAudio.kt"
  "$source_dir/GuardianFireRedPolicy.kt"
  "$source_dir/GuardianFireRedStreaming.kt"
  "$module_dir/tests/host/FireRedStreamPolicyTest.kt"
  "$module_dir/tests/host/FireRedPolicyTest.kt"
  "$source_dir/GuardianDiagnosticOptions.kt"
  "$source_dir/GuardianNeuralInputGain.kt"
  "$source_dir/GuardianDiagnosticWavWriter.kt"
  "$source_dir/GuardianGtcrnComparison.kt"
  "$module_dir/tests/host/GtcrnComparisonTest.kt"
  "$source_dir/GuardianSpeechSegmenter.kt"
  "$source_dir/GuardianVoiceInputRouter.kt"
  "$source_dir/GuardianVoiceProfile.kt"
  "$source_dir/GuardianVoiceDiagnostics.kt"
  "$source_dir/GuardianQwenProgressiveDecoder.kt"
  "$module_dir/tests/host/SpeechControlTest.kt"
  "$module_dir/tests/host/VoiceCoreTest.kt"
  "$module_dir/tests/host/DecoderTest.kt"
  "$module_dir/tests/host/DiagnosticsTest.kt"
  "$module_dir/tests/host/WavWriterTest.kt"
  "$module_dir/tests/host/NeuralInputGainTest.kt"
  "$module_dir/tests/host/DiagnosticOptionsTest.kt"
  "$module_dir/tests/host/stubs/AndroidClock.kt"
  "$module_dir/tests/host/stubs/AndroidLog.kt"
  "$module_dir/tests/host/stubs/QwenRecognizer.kt"
)
if command -v kotlinc >/dev/null 2>&1; then
  kotlinc "${sources[@]}" -include-runtime -d "$build_dir/tests.jar"
  java -cp "$build_dir/tests.jar" expo.modules.guardiannativeprotection.VoiceCoreTestKt
else
  : "${KOTLIN_COMPILER_CP:?Install Kotlin or set KOTLIN_COMPILER_CP to the compiler jars classpath}"
  : "${KOTLIN_STDLIB:?Set KOTLIN_STDLIB to kotlin-stdlib.jar}"
  java -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$KOTLIN_STDLIB" -d "$build_dir/tests.jar" "${sources[@]}"
  java -cp "$build_dir/tests.jar:$KOTLIN_STDLIB" expo.modules.guardiannativeprotection.VoiceCoreTestKt
fi

java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.CaptureChannelRevisionTest
java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.ChannelPreprocessorTest
java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.ChannelWindowHistoryTest
