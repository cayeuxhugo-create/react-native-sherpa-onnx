"""Regression: installer without ArchivePath, including an empty PSScriptRoot.

Uses real PowerShell on disposable projects. Set PWSH to its executable.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--installer', type=Path, required=True)
p.add_argument('--archive', type=Path, required=True)
a = p.parse_args()
with zipfile.ZipFile(a.archive) as z:
    manifest = json.loads(z.read('MANIFESTE.json'))
prefix = 'modules/guardian-native-protection/'
expected = {k[len(prefix):]: v for k, v in manifest['files'].items()}
source = a.installer.read_text(encoding='utf-8-sig')
assert '[string]$ArchivePath = ""' in source
with tempfile.TemporaryDirectory(prefix='guardian-script-default-') as temporary:
    for empty_root in (False, True):
        root = Path(temporary)/str(empty_root)
        scripts = root/'tools with spaces'; scripts.mkdir(parents=True)
        installer = scripts/'installer.ps1'
        text = source.replace('$ErrorActionPreference = "Stop"',
                              '$ErrorActionPreference = "Stop"\n$PSScriptRoot = ""', 1) if empty_root else source
        installer.write_text(text, encoding='utf-8-sig')
        shutil.copyfile(a.archive, scripts/'modules-voix-v3.5.3-adaptive-candidate.zip')
        project = root/'application'; module = project/'modules/guardian-native-protection'
        module.mkdir(parents=True); (module/'old.txt').write_text('old module')
        (project/'package.json').write_text('{}')
        result = subprocess.run([os.environ.get('PWSH', 'pwsh'), '-NoProfile', '-NonInteractive',
            '-ExecutionPolicy', 'Bypass', '-File', str(installer), '-ProjectDirectory', str(project)],
            text=True, capture_output=True)
        assert result.returncode == 0, result.stdout+result.stderr
        actual = {f.relative_to(module).as_posix(): hashlib.sha256(f.read_bytes()).hexdigest()
                  for f in module.rglob('*') if f.is_file()}
        assert actual == expected
        backups = list(root.glob('application-guardian-sauvegarde-*'))
        assert len(backups) == 1 and (backups[0]/'guardian-native-protection/old.txt').read_text() == 'old module'
        print('PASS default archive resolution; empty script root:', empty_root, flush=True)
