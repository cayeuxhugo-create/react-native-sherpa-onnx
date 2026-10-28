"""Exercise the actual PowerShell reference-export validator with synthetic PCM.
The runner reproduces Windows PowerShell 5's Select-Object dictionary behavior,
which PowerShell 7 otherwise masks. No device, private audio or model is needed.
"""
import copy,csv,hashlib,json,os,re,shutil,struct,subprocess,tempfile,wave
from pathlib import Path

root=Path(__file__).resolve().parents[3]
script=(root/'enregistrer-guardian-reference.ps1').read_text(encoding='utf-8-sig')
block=script.split('# BEGIN REFERENCE EXPORT VALIDATION',1)[1].split('# END REFERENCE EXPORT VALIDATION',1)[0]
pwsh=os.environ.get('PWSH','pwsh')
ps5_compatibility=r'''
function Select-Object {
 [CmdletBinding()]
 param([Parameter(ValueFromPipeline=$true)]$InputObject,
       [string]$ExpandProperty, [switch]$Unique)
 begin { $items=New-Object 'System.Collections.Generic.List[object]' }
 process {
  # WinPS 5 cannot expand a dictionary key as a PSObject property. Keep the
  # platform difference explicit while delegating every supported selection.
  if ($ExpandProperty -and $InputObject -is [Collections.IDictionary] -and
      $null -eq $InputObject.PSObject.Properties[$ExpandProperty]) {
   throw ('PS5_COMPAT_EXPAND_PROPERTY: '+$ExpandProperty)
  }
  $items.Add($InputObject)
 }
 end {
  $options=@{}
  if ($ExpandProperty) { $options['ExpandProperty']=$ExpandProperty }
  if ($Unique) { $options['Unique']=$true }
  $items.ToArray() | Microsoft.PowerShell.Utility\Select-Object @options
 }
}
'''

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
  streams=[(full,data,800,512,'full','FULL'),(pair,data[128*4:384*4],928,256,'pair','EXACT')]
  if mode=='good-context':streams.append(('epoch-1/segment-1/END_PLUS_2S/guardian-voice-pair-'+stage,data[128*4:],928,384,'pair','END_PLUS_2S'))
  for name,content,start,samples,kind,variant in streams:
   write(name+'.f32',content)
   with wave.open(str(path/(name+'.wav')),'wb') as w:w.setnchannels(1);w.setsampwidth(2);w.setframerate(16000);w.writeframes(b'\0'*(samples*2))
   for extension in ['f32','wav']:rows.append(dict(path=name+'.'+extension,kind=kind,epoch=1,segment=0 if kind=='full' else 1,variant=variant,lane=lane,globalStart=start,samples=samples))
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
 elif mode=='duplicate-pair-lane':
  # Four different paths and valid source slices must still be rejected when
  # two entries claim the same lane; reach the group check, not the hash check.
  for row in rows:
   if row['kind']=='pair' and row['lane']=='long':
    original=next(r for r in rows if r['kind']=='pair' and r['lane']=='short' and Path(r['path']).suffix==Path(row['path']).suffix)
    shutil.copyfile(path/original['path'],path/row['path']);row['lane']='short'
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

def snapshot(path):
 return {str(p.relative_to(path)):(hashlib.sha256(p.read_bytes()).hexdigest(),p.stat().st_mtime_ns,p.stat().st_mode)
         for p in path.rglob('*') if p.is_file()}

def runner_text(validation):
 return 'param([string]$Directory)\n$ErrorActionPreference="Stop"\n'+ps5_compatibility+validation+'\nAssert-GuardianReferenceExport $Directory | ConvertTo-Json -Depth 5\n'

with tempfile.TemporaryDirectory(prefix='guardian-reference-exports-') as tmp:
 tmp=Path(tmp);runner=tmp/'validate.ps1';runner.write_text(runner_text(block),encoding='utf-8-sig')
 env=os.environ.copy()
 for key in ['XDG_CONFIG_HOME','XDG_CACHE_HOME','XDG_DATA_HOME']:env[key]=str(tmp/key);Path(env[key]).mkdir()
 modes=['good','good-context','mono','pair-corrupt','missing-file','pair-outside','timeline-gap','timeline-offset','duplicate-path','unknown-lane','bad-path','incomplete-timeline','missing-pair-lane','duplicate-pair-lane','cross-epoch','oversized-window','timeline-length','mixed-pair-interval']
 for mode in modes:
  directory=tmp/mode;fixture(directory,mode)
  before=snapshot(directory)
  result=subprocess.run([pwsh,'-NoProfile','-File',str(runner),str(directory)],capture_output=True,text=True,env=env)
  assert snapshot(directory)==before,('Validator modified exports',mode)
  expected=mode in ['good','good-context','mono']
  assert (result.returncode==0)==expected,(mode,result.stdout,result.stderr)
  assert 'PS5_COMPAT_EXPAND_PROPERTY:' not in result.stderr,(mode,result.stderr)
  if expected:
   windows={'good':1,'good-context':2,'mono':0}[mode]
   data=json.loads(result.stdout);assert data['byteExactPairs'] and data['pairedWindows']==windows
  if mode=='duplicate-pair-lane':assert 'Les quatre voies reference' in result.stderr,(mode,result.stderr)
  print('PASS reference export',mode)
 print('PASS',len(modes),'reference export validation scenarios')
 # Restore the old projection only in a temporary runner. A valid four-lane
 # export must reproduce the platform failure, proving the shim detects it.
 group_check=next(line for line in block.splitlines() if 'if ($group.Count -ne 4 ' in line)
 legacy_group_check=re.sub(r'@\(\$group \| .+?\)\.Count -ne 4',
                          '@($group | Select-Object -ExpandProperty lane -Unique).Count -ne 4',group_check,count=1)
 assert legacy_group_check!=group_check,'Validator still uses the Windows PowerShell 5-incompatible lane projection'
 legacy=tmp/'validate-legacy.ps1';legacy.write_text(runner_text(block.replace(group_check,legacy_group_check)),encoding='utf-8-sig')
 directory=tmp/'good';before=snapshot(directory)
 result=subprocess.run([pwsh,'-NoProfile','-File',str(legacy),str(directory)],capture_output=True,text=True,env=env)
 assert result.returncode!=0 and 'PS5_COMPAT_EXPAND_PROPERTY: lane' in result.stderr,(result.stdout,result.stderr)
 assert snapshot(directory)==before,'Legacy validation modified exports'
 print('PASS reference export legacy Windows PowerShell 5 regression reproduced')
