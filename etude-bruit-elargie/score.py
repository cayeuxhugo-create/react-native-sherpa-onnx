"""Aggregate exact words and WER, without publishing private transcripts."""
import argparse,json,re,collections
from pathlib import Path

def tokens(x):return re.findall(r'[^\W_]+',x.casefold(),flags=re.UNICODE)
def distance(a,b):
 previous=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  current=[i]
  for j,y in enumerate(b,1):current.append(min(current[-1]+1,previous[j]+1,previous[j-1]+(x!=y)))
  previous=current
 return previous[-1]
def summarize(files):
 grouped=collections.defaultdict(list)
 for file in files:
  for e in json.loads(Path(file).read_text())['results']:
   ref=tokens(e['expected']);hyp=tokens(e['text']);r=dict(e,errors=distance(ref,hyp),referenceWords=len(ref),exact=(ref==hyp));grouped[(e['dataset'],e['method'])].append(r)
 out=[]
 for (dataset,method),xs in grouped.items():
  byprompt=collections.defaultdict(list)
  for x in xs:byprompt[x['prompt']].append(x)
  robust=sum(len(rows)==2 and all(r['exact'] for r in rows) for rows in byprompt.values())
  out.append(dict(dataset=dataset,method=method,family=xs[0]['family'],segmented=xs[0].get('segmented',False),windows=len(xs),exactWindows=sum(x['exact'] for x in xs),robustPhrases=robust,meanWER=sum(x['errors'] for x in xs)/max(sum(x['referenceWords'] for x in xs),1)))
 return sorted(out,key=lambda x:(x['dataset'],-x['robustPhrases'],x['meanWER'],-x['exactWindows']))
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('files',nargs='+');p.add_argument('--output',type=Path);a=p.parse_args();out=summarize(a.files)
 if a.output:a.output.write_text(json.dumps(out,indent=2))
 for e in out:print(e['dataset'],e['method'],f"n={e['windows']} exact={e['exactWindows']} robust={e['robustPhrases']} WER={e['meanWER']:.3f}")
if __name__=='__main__':main()
