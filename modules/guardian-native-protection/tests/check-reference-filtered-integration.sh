#!/usr/bin/env bash
# Real native VAD, frame-only worker replay. Corpus and artifacts stay private.
set -euo pipefail
here="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
export WIENER_EXTRA_SOURCE="$here/host/ReferenceFilteredWorkerIntegration.kt"
export WIENER_MAIN_CLASS=expo.modules.guardiannativeprotection.ReferenceFilteredWorkerIntegration
exec bash "$here/check-wiener-integration.sh" "$@"
