"""Package the complete v3.4 module with per-file hashes and measured validation.
Run after tests; never includes private audio or local test-output directories.
"""
from pathlib import Path
import hashlib,json,zipfile
root=Path(__file__).resolve().parents[1];module=root/'modules/guardian-native-protection'
files=sorted(p for p in module.rglob('*') if p.is_file() and '__pycache__' not in p.parts and p.suffix!='.pyc')
validation=json.loads((module/'tests/results/v3.4-streaming-summary.json').read_text())
assert validation['decisionMismatches']==0 and validation['byteIdenticalToReference']==27
manifest=dict(version='guardian-voice-v3.4-firered-stream',baseForPatch='guardian-voice-v3.3-firered',validation=validation,files={p.relative_to(root).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in files})
archive=root/'modules-voix-v3.4-firered-stream.zip'
with zipfile.ZipFile(archive,'w',compression=zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for p in files+[root/'LIVRAISON-FIRERED-STREAM-V3.4.md',root/'enregistrer-firered-stream.ps1',root/'recuperer-guardian-wav.ps1']:
  info=zipfile.ZipInfo(p.relative_to(root).as_posix(),date_time=(2026,10,23,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;z.writestr(info,p.read_bytes())
 info=zipfile.ZipInfo('MANIFESTE.json',date_time=(2026,10,23,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o100644<<16;z.writestr(info,json.dumps(manifest,ensure_ascii=False,indent=2).encode())
print(len(files),'module files;',archive.stat().st_size,'bytes; SHA256',hashlib.sha256(archive.read_bytes()).hexdigest())
