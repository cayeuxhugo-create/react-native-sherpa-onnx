"""Evaluate fixed admission policies; never choose a branch using reference text."""
import argparse,json,collections
from pathlib import Path
from score import tokens

def lcs(a,b):
 p=[0]*(len(b)+1)
 for x in a:
  q=[0]
  for j,y in enumerate(b):q.append(p[j]+1 if x==y else max(p[j+1],q[-1]))
  p=q
 return p[-1]

def main():
 p=argparse.ArgumentParser();p.add_argument('root',type=Path);p.add_argument('--output',type=Path,required=True);a=p.parse_args();r=a.root
 datasets={x['id']:x for x in json.loads((r/'labels-private.json').read_text())}
 raw=json.loads((r/'pipeline-qwen/results-private.json').read_text())['results'];rawtexts={x['id']:x['text'] for x in raw}
 enhanced=sum([json.loads((r/f).read_text())['results'] for f in ['post-qwen/results-private.json','post-extra-qwen/results-private.json']],[])
 accepted={}
 for f in ['post-confirm-results.tsv','post-negative-confirm-results.tsv','post-wiener-confirm-results.tsv']:
  for line in (r/f).read_text().splitlines():
   cols=line.split('\t');accepted[cols[0]]=cols[2]=='true'
 out={}
 def report(name,outputs):
  stats=collections.defaultdict(lambda:dict(expected=0,exact=0,captions=0,unmatched=0));noise=[]
  for id,d in datasets.items():
   group='negative' if d['group'].startswith('negative') else d['group'];ref=[tokens(t) for t in d['texts']];hyp=[tokens(t) for _,t in sorted(outputs.get(id,[])) if tokens(t)];n=lcs(ref,hyp);s=stats[group];s['expected']+=len(ref);s['exact']+=n;s['captions']+=len(hyp);s['unmatched']+=len(hyp)-n
   if group=='noise':noise.append(dict(exact=n,captions=len(hyp)))
  out[name]=dict(groups=dict(stats),noiseRuns=noise)
 base=collections.defaultdict(list)
 for x in raw:
  for id in datasets:
   if x['dataset']==id+'-original':base[id].append((int(x['availableAtSample']),x['text']))
 report('reference',base)
 for method in sorted({x['method'] for x in enhanced}):
  for pre in [2,3]:
   for policy in ['enhanced_gate','raw_gate','rescue','prefer_enhanced']:
    outputs=collections.defaultdict(list)
    for x in enhanced:
     if x['method']!=method or x['preRoll']!=pre:continue
     original=rawtexts.get(x['dataset']+'-original-'+x['originalSegment'],'');ok=accepted[x['id']];text=None
     if policy=='enhanced_gate' and ok:text=x['text']
     if policy=='raw_gate' and x['rawAccepted']:text=x['text']
     if policy=='rescue':text=original if x['rawAccepted'] else x['text'] if ok else None
     if policy=='prefer_enhanced':text=x['text'] if ok else original if x['rawAccepted'] else None
     if text is not None:outputs[x['dataset']].append((x['availableAtSample'],text))
    report(f'{method}/pre{pre}/{policy}',outputs)
 a.output.write_text(json.dumps(out,indent=2))
 for name,s in out.items():print(name, json.dumps(s))
if __name__=='__main__':main()
