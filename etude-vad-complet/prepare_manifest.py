"""Build private full-recording inputs from existing validated replay manifests.
No source audio is overwritten. The expected sentence is supplied privately.
"""
import argparse,collections,json
from pathlib import Path
import numpy as np
p=argparse.ArgumentParser(description=__doc__)
for key in ['voice-1m','voice-3m','voice-5m-first','voice-5m-latest','noise-manifest','short-word-manifest','output']:p.add_argument('--'+key,type=Path,required=True)
p.add_argument('--expected-phrase',required=True);a=p.parse_args()
if a.output.exists():p.error('Choose a new output directory')
a.output.mkdir(parents=True);rows=[]
for name in ['voice-1m','voice-3m','voice-5m-first','voice-5m-latest']:
 path=getattr(a,name.replace('-','_')).resolve();assert path.is_file();rows.append(dict(id=name,label='recorded-voice',path=str(path),expected=a.expected_phrase,expectedRepetitions=3))
groups=collections.defaultdict(list)
for row in json.loads(a.noise_manifest.read_text()):groups[row['condition']].append(row)
for condition,group in groups.items():
 group.sort(key=lambda r:int(r['id'].rsplit('-',1)[1]));audio=np.concatenate([np.fromfile(r['path'],dtype='<f4') for r in group]);assert len(audio)==1600000,'Expected contiguous validated 100s controls';path=a.output/(condition+'.f32');audio.astype('<f4').tofile(path);rows.append(dict(id='control-'+condition,label='recorded-nonspeech',path=str(path.resolve())))
for row in json.loads(a.short_word_manifest.read_text()):
 if row['label']=='short-word':rows.append(dict(row,label='synthetic-short-word',expected=row['id'].rsplit('-',1)[0],expectedRepetitions=1))
assert len(rows)==24
(a.output/'manifest-private.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2));print('Prepared 24 continuous inputs; private manifest contains source paths')
