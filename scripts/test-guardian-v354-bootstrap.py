"""Exercise pinned delivery downloads in real PowerShell with offline fixtures."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

repo=Path(__file__).resolve().parents[1]
source_ref='a'*40
tool_names=['enregistrer-guardian-adaptatif.ps1','tester-guardian-secours-temporel.ps1',
           'preparer-envoi-diagnostic.ps1','LIVRAISON-SECOURS-TEMPOREL-V3.5.4.md']
installer_name='installer-moteur-v3.5.4-temporal-rescue.ps1'
archive_name='modules-voix-v3.5.4-temporal-rescue.zip'
with tempfile.TemporaryDirectory(prefix='guardian-v354-download-') as temporary:
    for mode in ['ok','download-failure','zip-tamper','tool-tamper','installer-error']:
        root=Path(temporary)/mode;fixtures=root/'remote';fixtures.mkdir(parents=True)
        project=root/'app with spaces';project.mkdir();(project/'package.json').write_text('{}')
        destination=root/'tools with spaces';destination.mkdir()
        previous=destination/tool_names[0];previous.write_text('previous collector')
        for name in tool_names:(fixtures/name).write_text('fixture '+name)
        manifest=dict(version='guardian-voice-v3.5.4-temporal-rescue',tools={
            name:hashlib.sha256((fixtures/name).read_bytes()).hexdigest() for name in tool_names})
        with zipfile.ZipFile(fixtures/archive_name,'w') as z:z.writestr('MANIFESTE.json',json.dumps(manifest))
        zip_hash=hashlib.sha256((fixtures/archive_name).read_bytes()).hexdigest()
        (fixtures/installer_name).write_text('''param($ProjectDirectory,$ArchivePath)
$expectedZipHash = "'''+zip_hash+'''"
if ($env:BOOTSTRAP_FIXTURE -eq 'installer-error') { exit 1 }
if (!(Test-Path -LiteralPath $ArchivePath)) { throw 'archive absent' }
Set-Content -LiteralPath (Join-Path $ProjectDirectory 'installed.txt') -Value $ArchivePath
''',encoding='utf-8-sig')
        bootstrap=root/'bootstrap.ps1';shutil.copyfile(repo/'installer-guardian-v354.ps1',bootstrap)
        wrapper=root/'run.ps1'
        wrapper.write_text('''param($Bootstrap,$Project,$Destination,$Ref)
$ErrorActionPreference='Stop'
function Invoke-WebRequest {
 param($Uri,$OutFile,[switch]$UseBasicParsing)
 if (!$Uri.StartsWith('https://raw.githubusercontent.com/cayeuxhugo-create/react-native-sherpa-onnx/'+$Ref+'/')) { throw 'unpinned URL' }
 $name=([uri]$Uri).Segments[-1]
 if ($env:BOOTSTRAP_FIXTURE -eq 'download-failure' -and $name -eq 'enregistrer-guardian-adaptatif.ps1') { throw 'synthetic download failure' }
 Copy-Item -LiteralPath (Join-Path $env:BOOTSTRAP_REMOTE $name) -Destination $OutFile
 if (($env:BOOTSTRAP_FIXTURE -eq 'zip-tamper' -and $name.EndsWith('.zip')) -or ($env:BOOTSTRAP_FIXTURE -eq 'tool-tamper' -and $name -eq 'enregistrer-guardian-adaptatif.ps1')) { Add-Content -LiteralPath $OutFile -Value 'tampered' }
}
& $Bootstrap -SourceRef $Ref -ProjectDirectory $Project -DestinationDirectory $Destination
''',encoding='utf-8-sig')
        env=dict(os.environ,BOOTSTRAP_FIXTURE=mode,BOOTSTRAP_REMOTE=str(fixtures))
        for key in ['XDG_CONFIG_HOME','XDG_CACHE_HOME','XDG_DATA_HOME']:
            env[key]=str(root/key);Path(env[key]).mkdir()
        run=subprocess.run([os.environ.get('PWSH','pwsh'),'-NoProfile','-File',str(wrapper),
            '-Bootstrap',str(bootstrap),'-Project',str(project),'-Destination',str(destination),'-Ref',source_ref],
            env=env,capture_output=True,text=True)
        assert (run.returncode==0)==(mode=='ok'),mode+'\n'+run.stdout+run.stderr
        assert (project/'installed.txt').exists()==(mode=='ok')
        if mode in ['download-failure','zip-tamper','tool-tamper']:
            assert previous.read_text()=='previous collector'
            assert list(destination.iterdir())==[previous]
        else:
            for name in [installer_name,archive_name]+tool_names:
                assert (destination/name).read_bytes()==(fixtures/name).read_bytes()
        print('PASS pinned v354 bootstrap',mode,flush=True)
