"""Integration test of the real PowerShell export script. No production source is edited.
Run with PWSH_BIN set when pwsh is not on PATH. No downloads occur here.
"""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def main():
    repo = Path(__file__).resolve().parents[2]
    pwsh = os.environ.get('PWSH_BIN') or shutil.which('pwsh')
    if not pwsh:
        raise SystemExit('Set PWSH_BIN to run the real PowerShell integration test')
    with tempfile.TemporaryDirectory(prefix='guardian-export-') as temp:
        root = Path(temp)
        project = root / 'project with spaces'
        (project / 'src').mkdir(parents=True)
        (project / 'node_modules/large-package').mkdir(parents=True)
        (project / 'android/app/build').mkdir(parents=True)
        (project / 'package.json').write_text('{"name":"guardian-fixture"}')
        (project / 'src/écran.ts').write_text('export const screen = "test";')
        (project / '.env').write_text('TOKEN=NEVER_EXPORT_SECRET')
        (project / 'credentials.json').write_text('{"token":"NEVER_EXPORT_SECRET"}')
        (project / 'node_modules/large-package/index.js').write_text('DEPENDENCY_NOT_EXPORTED')
        (project / 'android/app/build/app.apk').write_bytes(b'APK_NOT_EXPORTED')
        (project / 'android/gradle.properties').write_text(
            'org.gradle.jvmargs=-Xmx2048m\nMYAPP_UPLOAD_STORE_PASSWORD=NEVER_EXPORT_SECRET\n'
            'API_KEY=NEVER_EXPORT_SECRET\nreactNativeArchitectures=arm64-v8a\n'
        )
        for i in range(5):
            (project / f'src/asset-{i}.dat').write_bytes(os.urandom(500_000))
        link_created = False
        try:
            (project / 'src/loop').symlink_to(project, target_is_directory=True)
            link_created = True
        except OSError:
            pass
        originals = {str(p.relative_to(project)): digest(p) for p in project.rglob('*') if p.is_file() and not p.is_symlink()}
        output = root / 'output'
        command = [pwsh, '-NoProfile', '-File', str(repo / 'preparer-sources-guardian.ps1'),
                   '-ProjectDirectory', str(project), '-OutputDirectory', str(output), '-PartMiB', '1']
        run = subprocess.run(command, capture_output=True, text=True)
        assert run.returncode == 0, run.stdout + run.stderr
        manifest = json.loads((output / 'MANIFESTE-AUDIT.json').read_text(encoding='utf-8-sig'))
        assert len(manifest['parts']) >= 3
        extracted = {}
        for part in manifest['parts']:
            archive = output / part['archive']
            assert archive.stat().st_size < 32 * 1024 * 1024
            assert digest(archive) == part['sha256']
            with zipfile.ZipFile(archive) as z:
                assert z.testzip() is None
                for name in z.namelist():
                    assert not name.startswith('/') and '\\' not in name and '..' not in Path(name).parts
                    assert name not in extracted
                    extracted[name] = z.read(name)
        assert set(extracted) == {row['path'] for row in manifest['included']}
        assert 'src/écran.ts' in extracted and 'package.json' in extracted
        assert not any(p.startswith('node_modules/') or p.endswith('.apk') or p == '.env' or p == 'credentials.json' for p in extracted)
        assert all(b'NEVER_EXPORT_SECRET' not in data for data in extracted.values())
        gradle = extracted['android/gradle.properties'].decode()
        assert 'org.gradle.jvmargs=-Xmx2048m' in gradle
        assert 'reactNativeArchitectures=arm64-v8a' in gradle
        assert gradle.count('<REDACTED_BY_EXPORT>') == 2
        if link_created:
            assert any(row['path'] == 'src/loop' for row in manifest['excluded'])
        for relative, expected in originals.items():
            assert digest(project / relative) == expected, relative
        print('PASS real export: split ZIPs, manifests/hashes, Unicode paths, excluded credentials/dependencies/builds, redacted copy, untouched originals')
        again = subprocess.run(command, capture_output=True, text=True)
        assert again.returncode != 0
        inside = subprocess.run(command[:-4] + ['-OutputDirectory', str(project / 'out'), '-PartMiB', '1'], capture_output=True, text=True)
        assert inside.returncode != 0 and not (project / 'out').exists()
        print('PASS existing output and recursive in-project output rejected')


if __name__ == '__main__':
    main()
