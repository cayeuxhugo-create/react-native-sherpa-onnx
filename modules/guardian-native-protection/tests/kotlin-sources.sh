#!/usr/bin/env bash
# Resolve a production Kotlin source independently of its organisational folder.
# Caller provides module_dir. Ambiguous/missing class filenames are errors.
guardian_source() {
  local name="$1" found="" candidate
  while IFS= read -r candidate; do
    [[ -z "$found" ]] || { printf 'Ambiguous Kotlin source: %s\n' "$name" >&2; return 1; }
    found="$candidate"
  done < <(find "$module_dir/android/src/main/java/expo/modules/guardiannativeprotection" -type f -name "$name")
  [[ -n "$found" ]] || { printf 'Missing Kotlin source: %s\n' "$name" >&2; return 1; }
  printf '%s\n' "$found"
}
