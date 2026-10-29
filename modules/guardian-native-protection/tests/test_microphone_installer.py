"""Exercise the actual local-archive installer on a disposable project, never a device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile

p = argparse.ArgumentParser(description=__doc__)
p.add_argument('--installer', type=Path, required=True)
p.add_argument('--archive', type=Path, required=True)
a = p.parse_args()
with tempfile.TemporaryDirectory(prefix='guardian-installer-') as folder:
    root = Path(folder)
    def project(name):
        target = root / name
        module = target / 'modules/guardian-native-protection'
        module.mkdir(parents=True)
        (target / 'package.json').write_text('{}')
        (target / 'user-file.txt').write_text('must remain unchanged')
        (module / 'old-user-file.txt').write_text('preserve old module outside project')
        return target, module
    def install(target, archive):
        return subprocess.run([os.environ.get('PWSH','pwsh'), '-NoLogo', '-NoProfile', '-NonInteractive',
            '-ExecutionPolicy', 'Bypass', '-File', str(a.installer.resolve()),
            '-ProjectDirectory', str(target), '-ArchivePath', str(archive.resolve())],
            capture_output=True, text=True)
    target, module = project('application')
    result = install(target, a.archive)
    if result.returncode: raise AssertionError(result.stdout + result.stderr)
    with zipfile.ZipFile(a.archive) as z:
        assert z.testzip() is None
        manifest = json.loads(z.read('MANIFESTE.json'))
    prefix = 'modules/guardian-native-protection/'
    expected = {key[len(prefix):]:value for key,value in manifest['files'].items()}
    actual = {file.relative_to(module).as_posix():hashlib.sha256(file.read_bytes()).hexdigest()
        for file in module.rglob('*') if file.is_file()}
    assert actual == expected
    assert (target/'user-file.txt').read_text() == 'must remain unchanged'
    backups = list(root.glob('application-guardian-sauvegarde-*'))
    assert len(backups)==1 and (backups[0]/'guardian-native-protection/old-user-file.txt').exists()
    print('PASS installer: local archive, all module hashes, user files unchanged, old module backed up outside project')
    target, module = project('rejected')
    broken = root/'corrupt.zip'
    data = bytearray(a.archive.read_bytes()); data[len(data)//2] ^= 1; broken.write_bytes(data)
    result = install(target, broken)
    assert result.returncode != 0
    assert (module/'old-user-file.txt').read_text() == 'preserve old module outside project'
    assert len(list(module.rglob('*')))==1
    assert not list(root.glob('rejected-guardian-sauvegarde-*'))
    assert (target/'user-file.txt').read_text() == 'must remain unchanged'
    print('PASS installer: altered archive rejected before any project replacement')
