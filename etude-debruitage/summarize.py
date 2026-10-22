"""Publish aggregate paired scores only; never export transcripts/input IDs."""
import argparse,collections,json,re,unicodedata
from pathlib import Path

def tokens(text):
 return re.findall('[a-z0-9]+',''.join(c for c in unicodedata.normalize('NFD',text.lower()) if not unicodedata.combining(c)))
def edit(a,b):
 d=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  n=[i]
  for j,y in enumerate(b,1):n.append(min(n[-1]+1,d[j]+1,d[j-1]+(x!=y)))
  d=n
 return d[-1]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--inputs',type=Path,nargs='+',required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 rows=[r for path in a.inputs for r in json.loads(path.read_text())];groups=collections.defaultdict(list)
 for r in rows:groups[r['method']].append(r)
 original={r['id']:r for r in rows if r['method']=='original'}
 out=[]
 for method,items in groups.items():
  z=dict(method=method,totalInputs=len(items),voiceTotal=0,voiceExact=0,voiceReferenceWords=0,voiceWordErrors=0,voiceAdmitted=0,voiceByDistance={},syntheticTotal=0,syntheticExact=0,syntheticAdmitted=0,syntheticByLevel={},negativeTotal=0,negativeNonemptyForcedQwen=0,negativeVadAdmitted=0,negativeNonemptyAfterVad=0,enhanceSeconds=0,enhanceAudioSeconds=0)
  for r in items:
   ref=tokens(r['expected']);hyp=tokens(r['text']);accepted=r['vadAccepted'];exact=ref==hyp
   if r['label']=='positive':
    z['voiceTotal']+=1;z['voiceExact']+=exact;z['voiceReferenceWords']+=len(ref);z['voiceWordErrors']+=edit(ref,hyp);z['voiceAdmitted']+=accepted
    condition='5m' if '5m' in r['condition'] else r['condition'].replace('voice-','');g=z['voiceByDistance'].setdefault(condition,dict(total=0,exact=0,admitted=0));g['total']+=1;g['exact']+=exact;g['admitted']+=accepted
   elif r['label']=='short-word':
    z['syntheticTotal']+=1;z['syntheticExact']+=exact;z['syntheticAdmitted']+=accepted
    level=r['condition'].rsplit('-',1)[-1];g=z['syntheticByLevel'].setdefault(level,dict(total=0,exact=0,admitted=0));g['total']+=1;g['exact']+=exact;g['admitted']+=accepted
   elif r['label']=='negative':z['negativeTotal']+=1;z['negativeNonemptyForcedQwen']+=bool(hyp);z['negativeVadAdmitted']+=accepted;z['negativeNonemptyAfterVad']+=accepted and bool(hyp)
   if r['enhanceWallSeconds'] is not None:z['enhanceSeconds']+=r['enhanceWallSeconds'];z['enhanceAudioSeconds']+=r['audioSeconds']
  z['zeroOutputs']=sum(r['peak']==0 for r in items)
  z['zeroVoiceOutputs']=sum(r['peak']==0 and r['label']=='positive' for r in items)
  z['pairedChanges']={}
  for label in ['positive','short-word']:
   paired=[r for r in items if r['label']==label and r['id'] in original];improved=regressed=0
   for r in paired:
    before=tokens(original[r['id']]['text'])==tokens(r['expected']);after=tokens(r['text'])==tokens(r['expected']);improved+=after and not before;regressed+=before and not after
   z['pairedChanges'][label]=dict(total=len(paired),improved=improved,regressed=regressed)
  z['voiceWer']=z['voiceWordErrors']/max(1,z['voiceReferenceWords']);z['hostEnhanceRtf']=z['enhanceSeconds']/max(1e-9,z['enhanceAudioSeconds']);out.append(z)
 a.output.write_text(json.dumps(dict(scope='Fixed offline windows; forced Qwen and FireRed admission assessed separately; no Android validation',results=out),indent=2)+'\n')
 for z in out:print(z['method'],'voice',z['voiceExact'],'/',z['voiceTotal'],'words',z['syntheticExact'],'/',z['syntheticTotal'],'negative admissions',z['negativeVadAdmitted'],'/',z['negativeTotal'])
if __name__=='__main__':main()
