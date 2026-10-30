#!/usr/bin/env bash
# Opt-in, offline integration. Keep manifest and ARTIFACT_DIR outside the checkout.
# The FireRed/FSMN assets and host JNI libraries must already be present; nothing is downloaded.
set -euo pipefail
module_dir="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)"
source_dir="$module_dir/android/src/main/java/expo/modules/guardiannativeprotection"
: "${ASSETS:?Set ASSETS to the asset directory containing firered/}"
: "${JNI_DIR:?Set JNI_DIR to the host libguardian_firered_vad.so directory}"
: "${ARTIFACT_DIR:?Set ARTIFACT_DIR to a fresh private output directory}"
[[ $# == 1 && -f "$1" ]] || { printf 'Usage: ASSETS=... JNI_DIR=... ARTIFACT_DIR=... %s manifest.tsv\n' "$0" >&2; exit 2; }
[[ -f "$JNI_DIR/libguardian_firered_vad.so" && -d "$ASSETS/firered" ]] || { printf 'Missing local FireRed JNI library or assets\n' >&2; exit 2; }
require_fsmn="${REQUIRE_FSMN:-true}"
if [[ "$require_fsmn" == true ]]; then
  [[ -f "$JNI_DIR/libguardian_fsmn_vad.so" && -d "$ASSETS/fsmn" ]] || { printf 'Missing local FSMN JNI library or assets\n' >&2; exit 2; }
fi
build_dir="$(mktemp -d)"
trap 'rm -rf "$build_dir"' EXIT
cat > "$build_dir/GtcrnMustNotLoad.kt" <<'KOTLIN'
package expo.modules.guardiannativeprotection
/** Any accidental neural denoiser initialization fails this Wiener-only test. */
internal class GuardianGtcrnEnhancer(context: android.content.Context) {
    init { error("GTCRN must not be instantiated in a Wiener study") }
    val frameShiftInSamples: Int get() = error("GTCRN unavailable")
    fun process(pcm: FloatArray, count: Int, rate: Int): GuardianAudioEngine.EnhancementResult = error("GTCRN unavailable")
    fun release() {}
}
internal object GuardianTemporalGtcrn {
    fun loadView(context: android.content.Context): GuardianTemporalConfirmationView =
        error("Temporal GTCRN requires an explicit real backend in this host test")
}
KOTLIN
sources=(
  "$source_dir/GuardianTemporalSpeechCore.kt"
  "$source_dir/GuardianTemporalConfirmationView.kt"
  "$source_dir/GuardianQwenPrimaryAudioProcessor.kt"
  "$source_dir/GuardianQwenProgressiveDecoder.kt"
  "$source_dir/GuardianContinuousStudy.kt"
  "$source_dir/GuardianWienerEnhancer.kt"
  "$source_dir/GuardianWienerStudyStream.kt"
  "$source_dir/GuardianWienerChannelBank.kt"
  "$source_dir/GuardianCaptureChannelFrame.kt"
  "$source_dir/GuardianChannelPreprocessor.kt"
  "$source_dir/GuardianChannelWindowHistory.kt"
  "$source_dir/GuardianChannelQualityScores.kt"
  "$source_dir/GuardianAdaptiveChannels.kt"
  "$source_dir/GuardianStudyBuffer.kt"
  "$source_dir/GuardianFireRedStreaming.kt"
  "$source_dir/GuardianFireRedConfirmation.kt"
  "$source_dir/GuardianFireRedPolicy.kt"
  "$source_dir/GuardianAsrWindowPolicy.kt"
  "$source_dir/GuardianCalibratedReferenceView.kt"
  "$source_dir/GuardianReferenceNoiseEnhancer.kt"
  "$source_dir/GuardianReferenceNoiseStudy.kt"
  "$source_dir/GuardianFsmnConfirmation.kt"
  "$source_dir/GuardianFsmnPolicy.kt"
  "$source_dir/GuardianNeuralInputGain.kt"
  "$source_dir/GuardianSpeechSegmentGain.kt"
  "$source_dir/GuardianSpeechSegmenter.kt"
  "$source_dir/GuardianVoiceInputRouter.kt"
  "$source_dir/GuardianVoiceProfile.kt"
  "$source_dir/GuardianVoiceDiagnostics.kt"
  "$source_dir/GuardianDiagnosticWavWriter.kt"
  "$source_dir/GuardianGtcrnComparison.kt"
  "$module_dir/tests/host/WienerPrimaryIntegration.kt"
  "$module_dir/tests/host/FsmnIntegration.kt"
  "$module_dir/tests/host/stubs/AndroidContext.kt"
  "$module_dir/tests/host/stubs/AndroidAssets.kt"
  "$module_dir/tests/host/stubs/AndroidClock.kt"
  "$module_dir/tests/host/stubs/AndroidLog.kt"
  "$module_dir/tests/host/stubs/AudioEngineContract.kt"
  "$module_dir/tests/host/stubs/QwenRecognizer.kt"
  "$build_dir/GtcrnMustNotLoad.kt"
)
# A private replay can share these exact production classes and native checks
# without placing its corpus paths, expected phrases, or audio in the checkout.
if [[ -n "${WIENER_EXTRA_SOURCE:-}" ]]; then
  [[ -f "$WIENER_EXTRA_SOURCE" ]] || { printf 'Missing extra replay source\n' >&2; exit 2; }
  sources+=("$WIENER_EXTRA_SOURCE")
fi
if command -v kotlinc >/dev/null 2>&1; then
  kotlinc "${sources[@]}" -include-runtime -d "$build_dir/integration.jar"
  runtime_cp="$build_dir/integration.jar"
else
  : "${KOTLIN_COMPILER_CP:?Install Kotlin or set KOTLIN_COMPILER_CP to local compiler jars}"
  : "${KOTLIN_STDLIB:?Set KOTLIN_STDLIB to the local kotlin-stdlib.jar}"
  java -cp "$KOTLIN_COMPILER_CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler \
    -no-stdlib -no-reflect -classpath "$KOTLIN_STDLIB" -d "$build_dir/integration.jar" "${sources[@]}"
  runtime_cp="$build_dir/integration.jar:$KOTLIN_STDLIB"
fi
java -Dguardian.test.assets="$ASSETS" -Dguardian.test.requireFsmn="$require_fsmn" -Djava.library.path="$JNI_DIR" \
  -cp "$runtime_cp" "${WIENER_MAIN_CLASS:-expo.modules.guardiannativeprotection.WienerPrimaryIntegration}" "$1" "$ARTIFACT_DIR"
