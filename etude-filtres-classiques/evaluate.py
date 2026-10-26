"""Acoustically gated scoring. Missing/duplicate confirmations are errors.
No inference uses references; this scorer publishes aggregate numbers only.
"""
import argparse,json,re,collections
from pathlib import Path

def tokens(text):return re.findall(r'[^\W_]+',text.casefold(),flags=re.UNICODE)
def distance(a,b):
 previous=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  row=[i]
  for j,y in enumerate(b,1):row.append(min(row[-1]+1,previous[j]+1,previous[j-1]+(x!=y)))
  previous=row
 return previous[-1]

def summarize(rows,confirmation):
 groups=collections.defaultdict(list);seen=set()
 for e in rows:
  if e['id'] in seen:raise ValueError('Duplicate inference ID')
  seen.add(e['id'])
  if e['id'] not in confirmation:raise ValueError('Missing acoustic confirmation')
  groups[e['method']].append(e)
 out=[]
 for method,es in groups.items():
  speech=[e for e in es if e['group']=='speech'];negative=[e for e in es if e['group']=='negative'];exact=lambda e:tokens(e['expected'])==tokens(e['text']);admit=lambda e:confirmation[e['id']]
  out.append(dict(method=method,family=es[0]['family'],speechWindows=len(speech),negativeWindows=len(negative),forcedExact=sum(exact(e) for e in speech),admittedExact=sum(exact(e) and admit(e) for e in speech),admittedSpeech=sum(admit(e) for e in speech),admittedNegative=sum(admit(e) for e in negative),admittedNegativeTexts=sum(admit(e) and bool(tokens(e['text'])) for e in negative),forcedNegativeTexts=sum(bool(tokens(e['text'])) for e in negative),wordErrors=sum(distance(tokens(e['expected']),tokens(e['text'])) for e in speech),referenceWords=sum(len(tokens(e['expected'])) for e in speech),firstNoisyExact=any(e['dataset']=='vacuum3m' and e['segment']==2 and exact(e) and admit(e) for e in speech),preservedNoisyPhoneSuccesses=sum(e['dataset']=='vacuum3m' and e['segment'] in [3,4] and exact(e) and admit(e) for e in speech)))
 return sorted(out,key=lambda e:(e['admittedNegative'],-e['admittedExact'],-e['preservedNoisyPhoneSuccesses'],e['wordErrors'],e['method']))

def main():
 p=argparse.ArgumentParser();p.add_argument('--results',type=Path,nargs='+',required=True);p.add_argument('--confirmation',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--select',type=Path);a=p.parse_args()
 confirm={}
 for line in a.confirmation.read_text().splitlines():
  e=line.split('\t')
  if e[0] in confirm:raise ValueError('Duplicate confirmation')
  confirm[e[0]]=e[2]=='true'
 rows=[]
 for f in a.results:rows+=json.loads(f.read_text())['results']
 out=summarize(rows,confirm);a.output.write_text(json.dumps(out,indent=2))
 for e in out:print(e['method'],'admittedExact',e['admittedExact'],'/',e['speechWindows'],'negative',e['admittedNegative'],'/',e['negativeWindows'],'first',e['firstNoisyExact'])
 if a.select:
  selected=['original','wiener-legacy-o1-f0.5'];families=set()
  for e in out:
   if e['family'] in ['reference','legacy_wiener'] or e['family'] in families or e['admittedNegative']>0:continue
   selected.append(e['method']);families.add(e['family'])
   if len(families)==4:break
  a.select.write_text(json.dumps(selected,indent=2));print('selected',selected)
if __name__=='__main__':main()
