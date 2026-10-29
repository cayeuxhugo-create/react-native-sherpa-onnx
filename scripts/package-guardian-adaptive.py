"""Package only the public module and named delivery tools; never the private corpus."""
import argparse
import hashlib
import json
from pathlib import Path
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]
VERSION = 'guardian-voice-v3.5.3-adaptive-candidate'
BASE = '18fd88c3d1116d2eb0a627b8e4dd5af7c29b7dd8'
p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--output', type=Path, required=True)
a = p.parse_args()
summary_path = ROOT / 'modules/guardian-native-protection/tests/results/v3.5.3-adaptive-summary.json'
summary = json.loads(summary_path.read_text())
assert summary['version'] == VERSION
assert all(summary['validation'][key] for key in ['hostSuitePassed', 'adapterApiCompilationPassed',
    'collectorScenariosPassed', 'nativeCandidatePipelinePassed', 'realQwenCandidatePcmMatchesNativeInputs'])
names = subprocess.check_output(['git', 'ls-files', '--cached', '--others', '--exclude-standard', '--',
    'modules/guardian-native-protection'], cwd=ROOT, text=True).splitlines()
excluded = {'.wav', '.f32', '.pcm', '.log', '.jsonl', '.pyc'}
files = sorted({ROOT / name for name in names if '__pycache__' not in Path(name).parts
    and Path(name).suffix.lower() not in excluded})
assert len(files) > 50
for path in files:
    assert path.is_file() and not path.is_symlink()
    assert path.resolve().is_relative_to(ROOT / 'modules/guardian-native-protection')
    assert path.suffix.lower() not in {'.wav', '.f32', '.pcm', '.log', '.jsonl'}
    if '/assets/' in path.as_posix() or '/jniLibs/' in path.as_posix():
        original = subprocess.check_output(['git', 'show', BASE + ':' + path.relative_to(ROOT).as_posix()], cwd=ROOT)
        assert original == path.read_bytes(), 'Unexpected model or Android native-library change'
extras = [ROOT / name for name in ['LIVRAISON-ADAPTATIVE-V3.5.3.md', 'enregistrer-guardian-adaptatif.ps1', 'preparer-envoi-diagnostic.ps1']]
hashes = {path.relative_to(ROOT).as_posix(): hashlib.sha256(path.read_bytes()).hexdigest() for path in files}
manifest = dict(version=VERSION, baseCommit=BASE, baseForPatch='guardian-voice-v3.5.1-independent-filtered-study',
    validation=summary, files=hashes,
    tools={path.name: hashlib.sha256(path.read_bytes()).hexdigest() for path in extras},
    moduleContentSha256=hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest())
a.output.mkdir(parents=True, exist_ok=True)
archive = a.output / 'modules-voix-v3.5.3-adaptive-candidate.zip'
with zipfile.ZipFile(archive, 'w', compression=zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for path in files + extras:
        info = zipfile.ZipInfo(path.relative_to(ROOT).as_posix(), date_time=(2026, 10, 29, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED; info.external_attr = 0o100644 << 16
        z.writestr(info, path.read_bytes())
    info = zipfile.ZipInfo('MANIFESTE.json', date_time=(2026, 10, 29, 0, 0, 0))
    info.compress_type = zipfile.ZIP_DEFLATED; info.external_attr = 0o100644 << 16
    z.writestr(info, json.dumps(manifest, ensure_ascii=False, indent=2).encode())
print(json.dumps(dict(moduleFiles=len(files), archiveBytes=archive.stat().st_size,
    sha256=hashlib.sha256(archive.read_bytes()).hexdigest())))
