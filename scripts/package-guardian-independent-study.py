"""Package the v3.5.1 diagnostic cross-channel reference study after checks.

Only module sources/assets/binaries and named public delivery files are included.
Private recordings, transcripts and workspace artifacts must remain outside it.
"""
from pathlib import Path
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parents[1]
module = root / 'modules/guardian-native-protection'
validation = json.loads((module / 'tests/results/v3.5.1-independent-filtered-study-summary.json').read_text())
assert validation['version'] == 'guardian-voice-v3.5.1-independent-filtered-study'
assert validation['validation']['hostSuitePassed']
assert validation['validation']['independentCollectorScenarios'] >= 7
assert validation['validation']['filteredExportScenarios'] >= 5
assert validation['validation']['independentWorkerFullReplays'] == 2
assert validation['validation']['originalPcmAndCallbacksUnchanged']
assert validation['validation']['automaticNoisyDistinctExpectedPhrasesRecovered'] == 3
assert validation['validation']['upstreamKotlinApiCompilationPassed']
assert validation['implementation']['nativeAssetsUnchanged']
files = sorted(p for p in module.rglob('*') if p.is_file()
               and '__pycache__' not in p.parts and p.suffix != '.pyc')
manifest = dict(version=validation['version'], baseForPatch='guardian-voice-v3.5.0-reference-noise-study',
                validation=validation,
                files={p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files})
archive = root / 'modules-voix-v3.5.1-independent-filtered-study.zip'
extras = [root / 'LIVRAISON-ECOUTE-INDEPENDANTE-V3.5.1.md', root / 'ARCHITECTURE-MOTEUR-VOIX.md',
          root / 'enregistrer-guardian-independant.ps1', root / 'preparer-envoi-diagnostic.ps1']
with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for path in files + extras:
        info = zipfile.ZipInfo(path.relative_to(root).as_posix(), date_time=(2026, 10, 29, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        z.writestr(info, path.read_bytes())
    info = zipfile.ZipInfo('MANIFESTE.json', date_time=(2026, 10, 29, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    z.writestr(info, json.dumps(manifest, ensure_ascii=False, indent=2).encode())
print(json.dumps(dict(moduleFiles=len(files), archiveBytes=archive.stat().st_size,
                      sha256=hashlib.sha256(archive.read_bytes()).hexdigest())))
