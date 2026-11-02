"""Verify source-only moves and the stable Android/Expo naming contracts."""
import hashlib
import json
import re
from pathlib import Path

module = Path(__file__).resolve().parents[1]
layout = json.loads((module / 'SOURCE-LAYOUT.json').read_text())
main = module / 'android/src/main/java/expo/modules/guardiannativeprotection'
for move in layout['moves']:
    source, destination = module / move['from'], module / move['to']
    assert not source.exists(), f'Old source still present: {source}'
    assert hashlib.sha256(destination.read_bytes()).hexdigest() == move['sha256'], destination
    if '/src/main/' in move['to']:
        assert re.search(r'^package\s+' + re.escape(layout['package']) + r'\s*$', destination.read_text(encoding='utf-8-sig'), re.MULTILINE), destination
assert len(list(main.glob('*.kt'))) == 2
assert len(list(main.rglob('*.kt'))) == layout['productionKotlinFiles']
assert not list(main.rglob('*Simulation.kt'))
assert json.loads((module / 'expo-module.config.json').read_text())['android']['modules'] == [
    'expo.modules.guardiannativeprotection.GuardianNativeProtectionModule'
]
for removed in layout['removed']:
    assert removed['sha256'] == hashlib.sha256(b'').hexdigest()
    assert not (module / removed['path']).exists()
for name in ['GuardianNativeProtectionModule.kt', 'GuardianNativeProtectionView.kt']:
    assert (main / name).is_file()
print(f'PASS {len(layout["moves"])} byte-identical moves; {layout["productionKotlinFiles"]} production Kotlin files; Expo naming unchanged')
