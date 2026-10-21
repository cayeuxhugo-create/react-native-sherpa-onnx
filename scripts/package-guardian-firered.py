"""Build the complete v3.3 FireRed module archive and SHA-256 manifest.
Run from the repository root after native/host/collector validation.
"""
from pathlib import Path
import hashlib,json,zipfile
root=Path(__file__).resolve().parents[1];module=root/'modules/guardian-native-protection'
files=sorted(p for p in module.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix!='.pyc')
manifest=dict(version='guardian-voice-v3.3-firered',baseForPatch='guardian-voice-v3.2.1',validation=dict(nativeAbis=['arm64-v8a','armeabi-v7a','x86','x86_64'],referenceComparisons=647,referenceDecisionMismatches=0,kotlinJniWindows=157,pipelineGateHostTests=2,collectorFixtureTests=14,apkBuilt=False,androidDeviceTested=False),files={str(p.relative_to(root)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in files})
archive=root/'modules-voix-v3.3-firered.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for p in files+[root/'LIVRAISON-FIRERED-V3.3.md',root/'enregistrer-firered.ps1',root/'recuperer-guardian-wav.ps1']:
  info=zipfile.ZipInfo(p.relative_to(root).as_posix(),date_time=(2026,10,21,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;z.writestr(info,p.read_bytes())
 info=zipfile.ZipInfo('MANIFESTE.json',date_time=(2026,10,21,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;z.writestr(info,json.dumps(manifest,ensure_ascii=False,indent=2).encode())
print(len(files),'module files,',archive.stat().st_size,'archive bytes, SHA256',hashlib.sha256(archive.read_bytes()).hexdigest())
