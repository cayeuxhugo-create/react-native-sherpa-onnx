"""75-second annotated protocol integration with fake ADB; requires pwsh on PATH."""
from pathlib import Path
import os,subprocess,json,zipfile,shutil,tempfile
r=Path(tempfile.mkdtemp(prefix='guardian-float-protocol-'));p=r/'protocol';p.mkdir();(p/'bin').mkdir();remote=p/'remote';(remote/'files').mkdir(parents=True);app=p/'Application avec espaces';app.mkdir();(app/'package.json').write_text('{}')
repo=Path(__file__).resolve().parents[1];shutil.copyfile(repo/'etude-silence/fake_adb.py',p/'bin/adb');(p/'bin/adb').chmod(0o755)
env=dict(os.environ,PATH=str(p/'bin')+':'+os.environ['PATH'],FAKE_ADB_ROOT=str(remote),FAKE_MODE='ok',FAKE_FIRERED='4',FAKE_BLOCKS='10000',XDG_CACHE_HOME=str(p/'cache'),XDG_CONFIG_HOME=str(p/'config'),XDG_DATA_HOME=str(p/'data'))
a=[shutil.which('pwsh') or 'pwsh','-NoProfile','-File',str(repo/'enregistrer-guardian-float.ps1'),'-ProjectDirectory',str(app),'-PackageName','com.example.test','-DurationSeconds','75','-TestCase','WORDS','-DistanceMeters','3','-VoiceLevel','SOFT','-NoiseDescription','ventilateur stable']
rn=subprocess.run(a,input='test protocole\n\n\nconforme\n',text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,env=env,timeout=110);(p/'output.log').write_text(rn.stdout);assert rn.returncode==0,rn.stdout
with zipfile.ZipFile(next(app.rglob('*.zip'))) as z:
 m=json.loads(z.read('session.json').decode('utf-8-sig'));assert m['completed'] and m['distanceMeters']==3 and m['captureConfig']['audioSource']==6 and m['voiceLevel']=='SOFT' and m['actualObservations']=='conforme'
 markers=json.loads(z.read('test-markers.json').decode('utf-8-sig'));assert [x['label'] for x in markers]==['BEGIN']+['PROMPT_'+str(i) for i in range(1,7)]+['END']
 for mark,t in zip(markers[1:-1],[15,23,31,39,47,55]):assert t<=mark['elapsedSeconds']<t+3 and mark['plannedSeconds']==t
 assert markers[-1]['elapsedSeconds']>=75
 assert len([x for x in z.namelist() if x.endswith('.f32')])==7
print('PASS real 75-second WORDS protocol: six ordered timed prompts, fixed route, soft voice/distance/noise metadata, actual observations, BEGIN/END and seven float exports')

shutil.rmtree(r)
