#!/usr/bin/env bash
# Compile the new audio adapter against either the supplied AAR or upstream API.
# This checks Kotlin signatures, not an APK or JNI execution on Android.
set -euo pipefail
module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$module_dir/android/src/main/java/expo/modules/guardiannativeprotection"
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
reference=()
extra_cp=""
if [ -n "${SHERPA_AAR:-}" ]; then
  python - "$SHERPA_AAR" "$build_dir/classes.jar" <<'PY'
import sys, zipfile
from pathlib import Path
with zipfile.ZipFile(sys.argv[1]) as archive:
    Path(sys.argv[2]).write_bytes(archive.read('classes.jar'))
PY
  extra_cp=":$build_dir/classes.jar"
else
  curl --fail --silent --show-error --location \
    https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/Vad.kt \
    -o "$build_dir/Vad.kt"
  (cd "$build_dir" && printf '%s\n' 'dc3fe3b63cea2becd4780a9cc8ef2aaee552eed9533d4c0f178db1172a53662c  Vad.kt' | sha256sum --check --status)
  reference+=("$build_dir/Vad.kt")
  for api in OnlineSpeechDenoiser OfflineSpeechDenoiser DenoisedAudio; do
    curl --fail --silent --show-error --location \
      "https://raw.githubusercontent.com/k2-fsa/sherpa-onnx/v1.13.8/sherpa-onnx/kotlin-api/$api.kt" \
      -o "$build_dir/$api.kt"
    reference+=("$build_dir/$api.kt")
  done
  (cd "$build_dir" && printf '%s\n' \
    'c270d1b192cd9c380f0cd14befd4b9f13ef6abd90fe009ec428f2c303ad0ab5e  OnlineSpeechDenoiser.kt' \
    'cccf76cad45910971050635b09b16db3218316fbdea78edce7b19f6ba7c756fc  OfflineSpeechDenoiser.kt' \
    '0a426f3f12edb5c2dff486ddc7accb6675a666bf6c852f48d28ce37f49afc69f  DenoisedAudio.kt' | sha256sum --check --status)
fi
sources=(
  "$source_dir/voice/vad/GuardianTemporalSpeechCore.kt"
  "$source_dir/voice/enhancement/GuardianTemporalConfirmationView.kt"
  "$source_dir/voice/enhancement/GuardianTemporalGtcrn.kt"
  "$source_dir/voice/asr/GuardianAsrWindowPolicy.kt"
  "$source_dir/voice/enhancement/GuardianCalibratedReferenceView.kt"
  "$source_dir/voice/enhancement/GuardianReferenceNoiseEnhancer.kt"
  "$source_dir/voice/diagnostics/GuardianReferenceNoiseStudy.kt"
  "$source_dir/voice/vad/GuardianFsmnConfirmation.kt"
  "$source_dir/voice/vad/GuardianFsmnPolicy.kt"
  "$source_dir/voice/capture/GuardianCaptureChannelFrame.kt"
  "$source_dir/voice/capture/GuardianChannelWindowHistory.kt"
  "$source_dir/voice/enhancement/GuardianWienerChannelBank.kt"
  "$source_dir/voice/enhancement/GuardianWienerEnhancer.kt"
  "$source_dir/voice/diagnostics/GuardianWienerStudyStream.kt"
  "$source_dir/voice/diagnostics/GuardianContinuousStudy.kt"
  "$source_dir/voice/diagnostics/GuardianStudyBuffer.kt"
  "$source_dir/voice/capture/GuardianChannelQualityScores.kt"
  "$source_dir/voice/enhancement/GuardianSpeechSegmentGain.kt"
  "$source_dir/voice/capture/GuardianSpatialAudio.kt"
  "$source_dir/voice/vad/GuardianFireRedPolicy.kt"
  "$source_dir/voice/vad/GuardianFireRedStreaming.kt"
  "$source_dir/voice/vad/GuardianFireRedConfirmation.kt"
  "$source_dir/voice/enhancement/GuardianGtcrnEnhancer.kt"
  "$source_dir/voice/diagnostics/GuardianGtcrnComparison.kt"
  "$source_dir/voice/diagnostics/GuardianDiagnosticOptions.kt"
  "$source_dir/voice/vad/GuardianNeuralInputGain.kt"
  "$source_dir/voice/diagnostics/GuardianDiagnosticWavWriter.kt"
  "$source_dir/voice/segmentation/GuardianSpeechSegmenter.kt" "$source_dir/voice/asr/GuardianVoiceInputRouter.kt"
  "$source_dir/voice/segmentation/GuardianVoiceProfile.kt" "$source_dir/voice/vad/GuardianNeuralVad.kt"
  "$source_dir/voice/asr/GuardianQwenPrimaryAudioProcessor.kt" "$source_dir/voice/diagnostics/GuardianVoiceDiagnostics.kt" "$source_dir/voice/asr/GuardianQwenProgressiveDecoder.kt"
  "$module_dir/tests/host/stubs/AndroidClock.kt" "$module_dir/tests/host/stubs/AndroidLog.kt"
  "$module_dir/tests/host/stubs/AndroidContext.kt" "$module_dir/tests/host/stubs/AndroidAssets.kt"
  "$module_dir/tests/host/stubs/AudioEngineContract.kt" "$module_dir/tests/host/stubs/QwenRecognizer.kt"
)
if command -v kotlinc >/dev/null 2>&1; then
  kotlinc -classpath "${extra_cp#:}" "${reference[@]}" "${sources[@]}" -d "$build_dir/api.jar"
else
  : "${KOTLIN_COMPILER_CP:?Install Kotlin or set KOTLIN_COMPILER_CP}"
  : "${KOTLIN_STDLIB:?Set KOTLIN_STDLIB to kotlin-stdlib.jar}"
  java -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$KOTLIN_STDLIB$extra_cp" \
    "${reference[@]}" "${sources[@]}" -d "$build_dir/api.jar"
fi
printf '%s\n' 'Audio adapter Kotlin API compilation passed (Android framework replaced with test stubs).'
