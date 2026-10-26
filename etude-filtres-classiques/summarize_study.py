"""Publish aggregates only. Private corpus/audio/transcripts stay under --root."""
import argparse,csv,json,collections
from pathlib import Path
import numpy as np
from evaluate import summarize,tokens
from delayed_compatibility import delayed_legacy,use_original_candidate

def lcs(a,b):
 previous=[0]*(len(b)+1)
 for x in a:
  row=[0]
  for j,y in enumerate(b):row.append(previous[j]+1 if x==y else max(previous[j+1],row[-1]))
  previous=row
 return previous[-1]

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--baseline-study',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();r=a.root;a.output.mkdir(parents=True,exist_ok=True)
 rows=[]
 for phase in ['screen','validation']:
  for k in [0,1]:
   es=json.loads((r/f'{phase}-qwen-{k}/results-private.json').read_text())['results'];expected=json.loads((r/phase/f'manifest-{k}-private.json').read_text());assert {e['id'] for e in es}=={e['id'] for e in expected};rows+=es
 confirm={}
 for line in (r/'all-confirm.tsv').read_text().splitlines():
  e=line.split('\t');assert e[0] not in confirm;confirm[e[0]]=e[2]=='true'
 summary=summarize(rows,confirm);(a.output/'candidate-aggregate.json').write_text(json.dumps(summary,indent=2))
 complete=[]
 for s in summary:
  if s['speechWindows']!=24:continue
  es=[e for e in rows if e['method']==s['method']];byScope={}
  for scope in ['recent','old-quiet','old-negative']:
   subset=[e for e in es if e['scope']==scope];sp=[e for e in subset if e['group']=='speech'];neg=[e for e in subset if e['group']=='negative']
   byScope[scope]=dict(expected=len(sp),admittedExact=sum(confirm[e['id']] and tokens(e['expected'])==tokens(e['text']) for e in sp),rejectedSpeech=sum(not confirm[e['id']] for e in sp),negativeCandidates=len(neg),admittedNegatives=sum(confirm[e['id']] for e in neg),admittedNegativeTexts=sum(confirm[e['id']] and bool(tokens(e['text'])) for e in neg))
  complete.append(dict(s,scopes=byScope))
 (a.output/'complete-validation.json').write_text(json.dumps(complete,indent=2))
 lookup={(e['dataset'],e['method'],e['segment']):e for e in rows};derived=[];derivedGates={};counts=collections.Counter();corpus=json.loads((r/'corpus-private.json').read_text())
 for d in corpus:
  x=np.fromfile(d['path'],dtype='<f4');y=delayed_legacy(x)
  for w in d['windows']:
   method='original' if use_original_candidate(w['start']) else 'wiener-legacy-o1-f0.5';e=lookup[d['id'],method,w['segment']];tested=np.fromfile(e['path'],dtype='<f4');actual=x[w['start']:w['end']] if method=='original' else y[w['start']:w['end']];assert np.array_equal(actual,tested);counts[method]+=1
   id=d['id']+'-delayed-guard-'+str(w['segment']);derived.append(dict(e,id=id,method='delayed-legacy-with-startup-candidate-guard',family='compatibility-control'));derivedGates[id]=confirm[e['id']]
 compatibility=dict(reusedInferenceNotNewDecodes=True,matchedOriginalWindows=counts['original'],matchedLegacyWindows=counts['wiener-legacy-o1-f0.5'],all37WaveformsMatch=True,aggregate=summarize(derived,derivedGates),scope='additional causal compatibility control; not part of the frozen 26-configuration search')
 (a.output/'delayed-compatibility.json').write_text(json.dumps(compatibility,indent=2))
 final=[]
 for k in [0,1]:final+=json.loads((r/f'final-qwen-{k}/results-private.json').read_text())['results']
 trans=[e for e in final if e['experiment']=='transfer'];tc={l.split('\t')[0]:l.split('\t')[2]=='true' for l in (r/'transfer-confirm.tsv').read_text().splitlines()};transfer=summarize(trans,tc)
 for s in transfer:
  groups=collections.defaultdict(list)
  for e in trans:
   if e['method']==s['method']:groups[e['dataset'],e['segment']].append(e)
  s['utterances']=len(groups);s['robustAdmittedExact']=sum(len(es)==2 and all(tc[e['id']] and tokens(e['text'])==tokens(e['expected']) for e in es) for es in groups.values())
 (a.output/'transfer.json').write_text(json.dumps(transfer,indent=2))
 full=[e for e in final if e['experiment']=='full-replay'];baseline=json.loads((a.baseline_study/'exact-qwen/results-private.json').read_text())['results'];full += [dict(e,start=e['startSample'],end=e['endSample']) for e in baseline if e['method']=='original'];fullSummary=[]
 for method in sorted({e['method'] for e in full}):
  datasets={}
  for d in [d for d in corpus if d['scope']=='recent']:
   refs=[w for w in d['windows'] if w['group']=='speech'];es=sorted([e for e in full if e['method']==method and e['dataset']==d['id']],key=lambda e:e['end']);hyp=[tokens(e['text']) for e in es if tokens(e['text'])];outOfReference=sum(bool(tokens(e['text'])) and not any(e['start']<w['end'] and e['end']>w['start'] for w in refs) for e in es)
   datasets[d['id']]=dict(expected=len(refs),exactUtterances=lcs([tokens(w['expected']) for w in refs],hyp),admittedWindows=len(es),nonemptyCaptions=len(hyp),nonemptyOutsideOriginalSpeechWindows=outOfReference)
  fullSummary.append(dict(method=method,datasets=datasets))
 (a.output/'full-stream.json').write_text(json.dumps(fullSummary,indent=2))
 assert len(rows)==382 and len(trans)==72 and len(full)-len([e for e in baseline if e['method']=='original'])==20
 (a.output/'validation.json').write_text(json.dumps(dict(freshAsrDecodes=474,initialConfigurations=26,newConfigurations=24,screenSpoken=3,screenNegative=5,completeSpoken=24,completeNegativeUnique=13,transferUtterances=9,transferCorrelatedWindows=18,fullReplayMethods=2,extraDelayedControlUsesExactPcmInferenceReuse=True,blindIndependentValidation=False,androidChanged=False),indent=2))
 print('completed candidates382 transfer72 new-full20 =474 fresh ASR decodes')
 for s in complete:print(s['method'],s['admittedExact'],'/24 negative',s['admittedNegative'],'/13')
 print('compatibility',compatibility['aggregate'][0]['admittedExact'],'/24, negative',compatibility['aggregate'][0]['admittedNegative'])
if __name__=='__main__':main()
