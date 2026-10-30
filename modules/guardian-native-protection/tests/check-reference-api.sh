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
  "$source_dir/GuardianTemporalSpeechCore.kt"
  "$source_dir/GuardianTemporalConfirmationView.kt"
  "$source_dir/GuardianTemporalGtcrn.kt"
  "$source_dir/GuardianAsrWindowPolicy.kt"
  "$source_dir/GuardianCalibratedReferenceView.kt"
  "$source_dir/GuardianReferenceNoiseEnhancer.kt"
  "$source_dir/GuardianReferenceNoiseStudy.kt"
  "$source_dir/GuardianFsmnConfirmation.kt"
  "$source_dir/GuardianFsmnPolicy.kt"
  "$source_dir/GuardianCaptureChannelFrame.kt"
  "$source_dir/GuardianChannelWindowHistory.kt"
  "$source_dir/GuardianWienerChannelBank.kt"
  "$source_dir/GuardianWienerEnhancer.kt"
  "$source_dir/GuardianWienerStudyStream.kt"
  "$source_dir/GuardianContinuousStudy.kt"
  "$source_dir/GuardianStudyBuffer.kt"
  "$source_dir/GuardianChannelQualityScores.kt"
  "$source_dir/GuardianSpeechSegmentGain.kt"
  "$source_dir/GuardianSpatialAudio.kt"
  "$source_dir/GuardianFireRedPolicy.kt"
  "$source_dir/GuardianFireRedStreaming.kt"
  "$source_dir/GuardianFireRedConfirmation.kt"
  "$source_dir/GuardianGtcrnEnhancer.kt"
  "$source_dir/GuardianGtcrnComparison.kt"
  "$source_dir/GuardianDiagnosticOptions.kt"
  "$source_dir/GuardianNeuralInputGain.kt"
  "$source_dir/GuardianDiagnosticWavWriter.kt"
  "$source_dir/GuardianSpeechSegmenter.kt" "$source_dir/GuardianVoiceInputRouter.kt"
  "$source_dir/GuardianVoiceProfile.kt" "$source_dir/GuardianNeuralVad.kt"
  "$source_dir/GuardianQwenPrimaryAudioProcessor.kt" "$source_dir/GuardianVoiceDiagnostics.kt" "$source_dir/GuardianQwenProgressiveDecoder.kt"
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
