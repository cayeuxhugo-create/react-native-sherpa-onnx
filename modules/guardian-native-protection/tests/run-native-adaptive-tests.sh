#!/usr/bin/env bash
# Private TSV: label, primary.f32, reference.f32, expected admitted IDs,
# optional ROUTE_AT=sample or MISSING_AT=sample fault injection.
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export WIENER_EXTRA_SOURCE="$here/host/AdaptivePipelineReplay.kt"
export WIENER_MAIN_CLASS=expo.modules.guardiannativeprotection.AdaptivePipelineReplay
exec bash "$here/check-wiener-integration.sh" "$@"
