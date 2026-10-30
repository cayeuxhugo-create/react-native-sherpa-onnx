#!/usr/bin/env bash
# Offline full input pipeline: actual GTCRN CPU + native FireRed/FSMN, test-double
# recognizer. Private manifest: label, primary.f32, reference.f32, expected IDs,
# optional ROUTE_AT=sample / MISSING_AT=sample / GTCRN_ERROR_AT=sample.
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export GUARDIAN_GTCRN_SERVER="$here/gtcrn-test-server.py"
export WIENER_EXTRA_SOURCE="$here/host/TemporalRescuePipelineReplay.kt"
export WIENER_MAIN_CLASS=expo.modules.guardiannativeprotection.TemporalRescuePipelineReplay
exec bash "$here/check-wiener-integration.sh" "$@"
