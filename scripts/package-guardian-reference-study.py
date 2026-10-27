"""Package the v3.5.0 diagnostic cross-channel reference study after checks.

Only module sources/assets/binaries and named public delivery files are included.
Private recordings, transcripts and workspace artifacts must remain outside it.
"""
from pathlib import Path
import hashlib
import json
import zipfile

root = Path(__file__).resolve().parents[1]
module = root / 'modules/guardian-native-protection'
validation = json.loads((module / 'tests/results/v3.5.0-reference-noise-study-summary.json').read_text())
assert validation['version'] == 'guardian-voice-v3.5.0-reference-noise-study'
assert validation['validation']['hostSuitePassed']
assert validation['validation']['collectorSimulatedScenarios'] >= 5
assert validation['validation']['referenceExportValidationScenarios'] >= 16
assert validation['validation']['realVadIntegrationReplays'] == 2
assert validation['validation']['originalPcmAndCallbacksUnchanged']
assert validation['validation']['dspPrototypeComparedStreams'] == 6
assert validation['validation']['upstreamKotlinApiCompilationPassed']
assert validation['validation']['nativeAssetsUnchangedFromBase']
files = sorted(p for p in module.rglob('*') if p.is_file()
               and '__pycache__' not in p.parts and p.suffix != '.pyc')
manifest = dict(version=validation['version'], baseForPatch='guardian-voice-v3.4.9-fsmn-restart',
                validation=validation,
                files={p.relative_to(root).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest() for p in files})
archive = root / 'modules-voix-v3.5.0-reference-noise-study.zip'
extras = [root / 'LIVRAISON-REFERENCE-INTERCANAUX-V3.5.0.md', root / 'ARCHITECTURE-MOTEUR-VOIX.md',
          root / 'enregistrer-guardian-reference.ps1', root / 'preparer-envoi-diagnostic.ps1']
with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for path in files + extras:
        info = zipfile.ZipInfo(path.relative_to(root).as_posix(), date_time=(2026, 10, 27, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = 0o100644 << 16
        z.writestr(info, path.read_bytes())
    info = zipfile.ZipInfo('MANIFESTE.json', date_time=(2026, 10, 27, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED
    info.external_attr = 0o100644 << 16
    z.writestr(info, json.dumps(manifest, ensure_ascii=False, indent=2).encode())
print(json.dumps(dict(moduleFiles=len(files), archiveBytes=archive.stat().st_size,
                      sha256=hashlib.sha256(archive.read_bytes()).hexdigest())))
