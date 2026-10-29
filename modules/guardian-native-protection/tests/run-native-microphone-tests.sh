#!/usr/bin/env bash
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export WIENER_EXTRA_SOURCE="$here/host/MicrophonePipelineReplay.kt"
export WIENER_MAIN_CLASS=expo.modules.guardiannativeprotection.MicrophonePipelineReplay
exec bash "$here/check-wiener-integration.sh" "$@"
