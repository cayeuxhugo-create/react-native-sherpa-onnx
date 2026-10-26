"""Private study corpus assembly. No files or transcripts are uploaded/published.
One window per original candidate; alternate pre-roll versions are not duplicates.
"""
import argparse,json
from pathlib import Path

def main():
 p=argparse.ArgumentParser();p.add_argument('--recent',type=Path,required=True);p.add_argument('--old',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();out=[]
 latest=json.loads((a.recent/'inputs-private.json').read_text());base=[e for e in json.loads((a.recent/'baseline-all-private.json').read_text()) if e['method']=='original']
 for d in latest:
  r=Path(d['root']);windows=[dict(segment=e['segment'],start=e['startSample'],end=e['endSample'],expected=e['expected'],group=e['group'],rawAccepted=e['rawAccepted']) for e in base if e['dataset']==d['id']]
  out.append(dict(id=d['id'],scope='recent',path=str(next((r/'wav').glob('*-asr.f32'))),index=str(next((r/'wav').glob('*index.tsv'))),windows=windows))
 oldds=json.loads((a.old/'control-dataset.json').read_text());labels={e['id']:e['texts'] for e in json.loads((a.old/'labels-private.json').read_text())};ws=[e for e in json.loads((a.old/'all-production-windows.json').read_text()) if e['preRoll']==2]
 def quiet_name(id):
  if 'words' in id:return 'old-short3m'
  if '5m' in id:return 'old-5m'
  if '1m' in id:return 'old-quiet1m'
  if '3m' in id:return 'old-quiet3m'
  raise ValueError('Unknown quiet condition; supply an explicit anonymized mapping')
 neg=0
 for d in oldds:
  if d['group'] not in ['quiet','negative','negative-older']:continue
  if d['group']=='quiet':id=quiet_name(d['id']);scope='old-quiet'
  else:neg+=1;id=f'old-negative{neg}';scope='old-negative'
  windows=[];n=0
  for w in ws:
   if w['dataset']!=d['id']:continue
   positive=scope=='old-quiet' and w['rawAccepted'];text=labels[d['id']][n] if positive else ''
   if positive:n+=1
   windows.append(dict(segment=int(w['originalSegment']),start=w['startSample'],end=w['availableAtSample'],expected=text,group='speech' if positive else 'negative',rawAccepted=w['rawAccepted']))
  out.append(dict(id=id,scope=scope,path=d['path'],windows=windows))
 assert sum(w['group']=='speech' for d in out for w in d['windows'])==24
 assert sum(w['group']=='negative' for d in out for w in d['windows'])==13
 a.output.write_text(json.dumps(out,ensure_ascii=False,indent=2));print('recordings',len(out),'spoken24 negatives13')
if __name__=='__main__':main()
