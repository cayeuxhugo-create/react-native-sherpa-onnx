#!/usr/bin/env bash
set -euo pipefail
module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$module_dir/android/src/main/java/expo/modules/guardiannativeprotection"
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
sources=(
  "$source_dir/voice/delivery/GuardianEventDelivery.kt"
  "$module_dir/tests/host/ResilienceTest.kt"
  "$source_dir/voice/asr/GuardianAsrWindowPolicy.kt"
  "$source_dir/voice/vad/GuardianTemporalSpeechCore.kt"
  "$source_dir/voice/enhancement/GuardianTemporalConfirmationView.kt"
  "$source_dir/voice/enhancement/GuardianCalibratedReferenceView.kt"
  "$source_dir/voice/enhancement/GuardianReferenceNoiseEnhancer.kt"
  "$module_dir/tests/host/ReferenceNoiseEnhancerTest.kt"
  "$module_dir/tests/host/AdaptivePolicyTest.kt"
  "$module_dir/tests/host/TemporalSpeechCoreTest.kt"
  "$module_dir/tests/host/TemporalConfirmationViewTest.kt"
  "$source_dir/voice/vad/GuardianFsmnConfirmation.kt"
  "$source_dir/voice/vad/GuardianFsmnPolicy.kt"
  "$module_dir/tests/host/FsmnConditioningTest.kt"
  "$module_dir/tests/host/FsmnPolicyTest.kt"
  "$source_dir/voice/capture/GuardianCaptureChannelFrame.kt"
  "$source_dir/voice/capture/GuardianChannelPreprocessor.kt"
  "$source_dir/voice/capture/GuardianChannelWindowHistory.kt"
  "$source_dir/voice/enhancement/GuardianWienerChannelBank.kt"
  "$source_dir/voice/enhancement/GuardianWienerEnhancer.kt"
  "$source_dir/voice/diagnostics/GuardianWienerStudyStream.kt"
  "$module_dir/tests/host/WienerEnhancerTest.kt"
  "$module_dir/tests/host/WienerStudyTest.kt"
  "$module_dir/tests/host/WienerChannelBankTest.kt"
  "$module_dir/tests/host/CaptureChannelRevisionTest.kt"
  "$module_dir/tests/host/ChannelPreprocessorTest.kt"
  "$module_dir/tests/host/ChannelWindowHistoryTest.kt"
  "$source_dir/voice/capture/GuardianAdaptiveChannels.kt"
  "$module_dir/tests/host/AdaptiveChannelsTest.kt"
  "$source_dir/voice/diagnostics/GuardianStudyBuffer.kt"
  "$module_dir/tests/host/ContinuousStudyTest.kt"
  "$source_dir/voice/capture/GuardianChannelQualityScores.kt"
  "$module_dir/tests/host/MicrophoneSelectionTest.kt"
  "$source_dir/voice/enhancement/GuardianSpeechSegmentGain.kt"
  "$source_dir/voice/capture/GuardianSpatialAudio.kt"
  "$source_dir/voice/vad/GuardianFireRedPolicy.kt"
  "$source_dir/voice/vad/GuardianFireRedStreaming.kt"
  "$module_dir/tests/host/FireRedStreamPolicyTest.kt"
  "$module_dir/tests/host/FireRedPolicyTest.kt"
  "$source_dir/voice/diagnostics/GuardianDiagnosticOptions.kt"
  "$source_dir/voice/vad/GuardianNeuralInputGain.kt"
  "$source_dir/voice/diagnostics/GuardianDiagnosticWavWriter.kt"
  "$source_dir/voice/diagnostics/GuardianGtcrnComparison.kt"
  "$module_dir/tests/host/GtcrnComparisonTest.kt"
  "$source_dir/voice/segmentation/GuardianSpeechSegmenter.kt"
  "$module_dir/tests/host/SpeechSegmenterContinuityTest.kt"
  "$source_dir/voice/asr/GuardianVoiceInputRouter.kt"
  "$source_dir/voice/segmentation/GuardianVoiceProfile.kt"
  "$source_dir/voice/diagnostics/GuardianVoiceDiagnostics.kt"
  "$source_dir/voice/asr/GuardianQwenProgressiveDecoder.kt"
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
java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.FsmnConditioningTest
java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.FsmnPolicyTest

java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.ReferenceNoiseEnhancerTest
java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.SpeechSegmenterContinuityTest
java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.MicrophoneSelectionTest
java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.AdaptivePolicyTest
java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.TemporalSpeechCoreTest
java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.TemporalConfirmationViewTest

java -cp "$build_dir/tests.jar:${KOTLIN_STDLIB:-}" expo.modules.guardiannativeprotection.ResilienceTest
