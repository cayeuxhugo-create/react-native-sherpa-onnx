"""Aggregate fixed-window ASR and acoustic admission, without private transcripts."""
import argparse,json,re,collections
from pathlib import Path

def tokens(x):return re.findall(r'[^\W_]+',x.casefold(),flags=re.UNICODE)
def distance(a,b):
 prev=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  row=[i]
  for j,y in enumerate(b,1):row.append(min(row[-1]+1,prev[j]+1,prev[j-1]+(x!=y)))
  prev=row
 return prev[-1]

def summarize(file,confirm):
 data=json.loads(file.read_text());out=[];groups=collections.defaultdict(list)
 for e in data['results']:groups[e['method']].append(e)
 baseline={('quiet1m',2),('quiet1m',3),('quiet1m',4),('quiet3m',1),('quiet3m',2),('quiet3m',3),('vacuum3m',3),('vacuum3m',4)}
 for method,es in groups.items():
  speech=[e for e in es if e['group']=='speech'];negative=[e for e in es if e['group']=='negative'];exact=lambda e:tokens(e['text'])==tokens(e['expected'])
  ok={(e['dataset'],e['segment']) for e in speech if exact(e)}
  admitted=lambda e:confirm.get(e['id'],False)
  out.append(dict(method=method,speechWindows=len(speech),negativeWindows=len(negative),quietExact=sum(exact(e) for e in speech if e['dataset']!='vacuum3m'),noiseExact=sum(exact(e) for e in speech if e['dataset']=='vacuum3m'),exact=len(ok),preservedBaselineCorrect=len(ok&baseline),firstNoisyExact=('vacuum3m',2) in ok,
   wordErrors=sum(distance(tokens(e['expected']),tokens(e['text'])) for e in speech),referenceWords=sum(len(tokens(e['expected'])) for e in speech),
   forcedNegativeTexts=sum(bool(tokens(e['text'])) for e in negative),admittedNegativeTexts=sum(admitted(e) and bool(tokens(e['text'])) for e in negative),
   admittedExact=sum(admitted(e) and exact(e) for e in speech),admittedSpeech=sum(admitted(e) for e in speech),
   decodeSeconds=sum(e['decodeSeconds'] for e in es),audioSeconds=sum(e['audioSeconds'] for e in es)))
 return sorted(out,key=lambda x:(-x['exact'],x['wordErrors'],x['admittedNegativeTexts']))

def main():
 p=argparse.ArgumentParser();p.add_argument('--root',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();r=a.root
 confirm={}
 for line in (r/'confirm-results.tsv').read_text().splitlines():
  e=line.split('\t');confirm[e[0]]=e[2]=='true'
 result={}
 for folder in ['qwen-grid','parakeet-baseline','turbo-baseline','small-baseline','fr2025-baseline','fr2025-supplement','fr2023','parakeet-supplement','turbo-supplement','small-supplement','fr2025-beam4']:
  f=r/folder/'results-private.json'
  if f.exists():result[folder]=summarize(f,confirm)
 a.output.write_text(json.dumps(result,indent=2))
 for folder,rows in result.items():
  print(folder)
  for e in rows[:5]:print(e['method'],'exact',e['exact'],'/',e['speechWindows'],'quiet/noise',e['quietExact'],e['noiseExact'],'kept',e['preservedBaselineCorrect'],'first',e['firstNoisyExact'],'admitted',e['admittedExact'],'falseText',e['admittedNegativeTexts'])
if __name__=='__main__':main()
