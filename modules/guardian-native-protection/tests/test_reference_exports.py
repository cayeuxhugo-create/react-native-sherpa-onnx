"""Exercise the actual PowerShell reference-export validator with synthetic PCM.
No Android device, private recording, network or recognition model is needed.
"""
import copy,csv,json,os,shutil,struct,subprocess,tempfile,wave
from pathlib import Path

root=Path(__file__).resolve().parents[3]
script=(root/'enregistrer-guardian-reference.ps1').read_text(encoding='utf-8-sig')
block=script.split('# BEGIN REFERENCE EXPORT VALIDATION',1)[1].split('# END REFERENCE EXPORT VALIDATION',1)[0]
pwsh=os.environ.get('PWSH','pwsh')
def fixture(path,mode):
 path.mkdir(); rows=[];timeline=[]
 if mode=='mono':
  headers(path,rows,timeline);return
 for i,lane in enumerate(['primary','reference','short','long']):
  stage=['truth','raw','pregain','asr'][i];data=struct.pack('<512f',*[float((n+i)%21)/100 for n in range(512)])
  def write(relative,content):
   q=path/relative;q.parent.mkdir(parents=True,exist_ok=True);q.write_bytes(content)
  full='epoch-1/guardian-voice-full-'+stage
  pair='epoch-1/segment-1/EXACT/guardian-voice-pair-'+stage
  for name,content,start,samples,kind in [(full,data,800,512,'full'),(pair,data[128*4:384*4],928,256,'pair')]:
   write(name+'.f32',content)
   with wave.open(str(path/(name+'.wav')),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(b'\0'*(samples*2))
   for extension in ['f32','wav']:rows.append(dict(path=name+'.'+extension,kind=kind,epoch=1,segment=0 if kind=='full' else 1,variant='FULL' if kind=='full' else 'EXACT',lane=lane,globalStart=start,samples=samples))
  timeline.append(dict(epoch=1,lane=lane,routeRevision=1,originSample=800,globalStart=800,globalEnd=1312,fileSampleStart=0,sampleCount=512,sequence=2,capturedAtNs=100))
 if mode=='pair-corrupt':
  q=path/rows[2]['path'];b=bytearray(q.read_bytes());b[0]^=1;q.write_bytes(b)
 elif mode=='missing-file':(path/rows[2]['path']).unlink()
 elif mode=='pair-outside':
  for r in rows:
   if r['kind']=='pair':r['globalStart']=1200
 elif mode=='timeline-gap':timeline[0]['globalStart']+=1;timeline[0]['globalEnd']+=1
 elif mode=='timeline-offset':timeline[0]['fileSampleStart']=1
 elif mode=='duplicate-path':rows.append(copy.deepcopy(rows[0]))
 elif mode=='unknown-lane':rows[0]['lane']='invented'
 elif mode=='bad-path':rows[0]['path']='../outside.f32'
 elif mode=='incomplete-timeline':timeline.pop()
 elif mode=='missing-pair-lane':rows=[r for r in rows if not(r['kind']=='pair' and r['lane']=='long')]
 elif mode=='cross-epoch':
  for r in rows:
   if r['kind']=='pair':r['epoch']=2
 elif mode=='oversized-window':
  for r in rows:
   if r['kind']=='pair':r['samples']=128001
 elif mode=='timeline-length':timeline[0]['globalEnd']-=1;timeline[0]['sampleCount']-=1
 elif mode=='mixed-pair-interval':
  for r in rows:
   if r['kind']=='pair' and r['lane']=='long':r['globalStart']=929
 headers(path,rows,timeline)

def headers(path,rows,timeline):
 for name,data,fields in [('reference-files.csv',rows,['path','kind','epoch','segment','variant','lane','globalStart','samples']),('reference-timeline.csv',timeline,['epoch','lane','routeRevision','originSample','globalStart','globalEnd','fileSampleStart','sampleCount','sequence','capturedAtNs'])]:
  with (path/name).open('w',newline='') as out:w=csv.DictWriter(out,fieldnames=fields);w.writeheader();w.writerows(data)

with tempfile.TemporaryDirectory(prefix='guardian-reference-exports-') as tmp:
 tmp=Path(tmp);runner=tmp/'validate.ps1';runner.write_text('param([string]$Directory)\n$ErrorActionPreference="Stop"\n'+block+'\nAssert-GuardianReferenceExport $Directory | ConvertTo-Json -Depth 5\n',encoding='utf-8-sig')
 env=os.environ.copy()
 for key in ['XDG_CONFIG_HOME','XDG_CACHE_HOME','XDG_DATA_HOME']:env[key]=str(tmp/key);Path(env[key]).mkdir()
 modes=['good','mono','pair-corrupt','missing-file','pair-outside','timeline-gap','timeline-offset','duplicate-path','unknown-lane','bad-path','incomplete-timeline','missing-pair-lane','cross-epoch','oversized-window','timeline-length','mixed-pair-interval']
 for mode in modes:
  directory=tmp/mode;fixture(directory,mode)
  result=subprocess.run([pwsh,'-NoProfile','-File',str(runner),str(directory)],capture_output=True,text=True,env=env)
  expected=mode in ['good','mono']
  assert (result.returncode==0)==expected,(mode,result.stdout,result.stderr)
  if expected:
   data=json.loads(result.stdout);assert data['byteExactPairs'] and data['pairedWindows']==(1 if mode=='good' else 0)
  print('PASS reference export',mode)
 print('PASS',len(modes),'reference export validation scenarios')
