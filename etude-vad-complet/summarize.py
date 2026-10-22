"""Aggregate local private Qwen results without exporting transcripts or voice hashes."""
import argparse,collections,json,re,statistics,unicodedata
from pathlib import Path

def tokens(text):
 return re.findall(r'[a-z0-9]+',''.join(c for c in unicodedata.normalize('NFD',text.lower()) if not unicodedata.combining(c)))
def distance(a,b):
 d=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  new=[i]
  for j,y in enumerate(b,1):new.append(min(new[-1]+1,d[j]+1,d[j-1]+(x!=y)))
  d=new
 return d[-1]
def occurrences(text,expected):
 hits=0;i=0
 while i<=len(text)-len(expected):
  if text[i:i+len(expected)]==expected:hits+=1;i+=len(expected)
  else:i+=1
 return hits

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--results',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();rows=json.loads(a.results.read_text());groups=collections.defaultdict(list)
 for r in rows:groups[(r['strategy'],r['db'],r['offsetMs'])].append(r)
 result=[]
 for (strategy,db,offset),items in sorted(groups.items()):
  stats=dict(strategy=strategy,db=db,offsetMs=offset,voice=[],syntheticWordsTotal=0,syntheticWordsAdmitted=0,syntheticWordsRecognized=0,noiseSeconds=0.,noiseQwenCalls=0,noiseNonemptyResults=0,noiseByCondition={},detectorSeconds=0.,confirmationSeconds=0.,inputSeconds=0.,qwenCalls=0,qwenAudioSeconds=0.,qwenWallSeconds=0.,syntheticNominalEndToReadySeconds=[])
  for r in items:
   stats['detectorSeconds']+=r['detectorWallSeconds'];stats['confirmationSeconds']+=r['confirmationWallSeconds'];stats['inputSeconds']+=r['inputSeconds'];stats['qwenCalls']+=len(r['decoded']);stats['qwenAudioSeconds']+=sum(Path(x['path']).stat().st_size/4/16000 for x in r['decoded']);stats['qwenWallSeconds']+=sum(x['decodeWallSeconds'] for x in r['decoded'])
   if r['label']=='recorded-voice':
    ref=tokens(r['expected']);allwords=[t for d in r['decoded'] for t in tokens(d['text'])];full=sum(occurrences(tokens(d['text']),ref) for d in r['decoded']);wordErrors=distance(ref*r['expectedRepetitions'],allwords)
    stats['voice'].append(dict(condition=r['inputId'],expectedRepetitions=r['expectedRepetitions'],exactRepetitions=min(full,r['expectedRepetitions']),extraExactRepetitions=max(0,full-r['expectedRepetitions']),wordErrors=wordErrors,referenceWords=len(ref)*r['expectedRepetitions'],candidateWindows=len(r['windows']),qwenCalls=len(r['decoded']),windowReadySeconds=[x['availableSeconds'] for x in r['decoded']]))
   elif r['label']=='recorded-nonspeech':
    n=len(r['decoded']);nonempty=sum(bool(tokens(x['text'])) for x in r['decoded']);stats['noiseSeconds']+=r['inputSeconds'];stats['noiseQwenCalls']+=n;stats['noiseNonemptyResults']+=nonempty;stats['noiseByCondition'][r['inputId'].removeprefix('control-')]=dict(qwenCalls=n,nonemptyResults=nonempty)
   elif r['label']=='synthetic-short-word':
    stats['syntheticWordsTotal']+=1;stats['syntheticWordsAdmitted']+=bool(r['decoded']);ref=tokens(r['expected']);found=[x for x in r['decoded'] if occurrences(tokens(x['text']),ref)>0];stats['syntheticWordsRecognized']+=bool(found)
    if found:stats['syntheticNominalEndToReadySeconds'].append(min(x['availableSeconds'] for x in found)-(r['inputSeconds']-3.))
  stats['exactVoiceRepetitions']=sum(v['exactRepetitions'] for v in stats['voice']);stats['expectedVoiceRepetitions']=sum(v['expectedRepetitions'] for v in stats['voice']);stats['voiceWordErrorRate']=sum(v['wordErrors'] for v in stats['voice'])/max(1,sum(v['referenceWords'] for v in stats['voice']));stats['detectorRtf']=(stats['detectorSeconds']+stats['confirmationSeconds'])/stats['inputSeconds'];lat=stats.pop('syntheticNominalEndToReadySeconds');stats['syntheticNominalEndToReadyMedianSeconds']=statistics.median(lat) if lat else None;result.append(stats)
 a.output.write_text(json.dumps(dict(scope='Local reference-model replay; no Android alert/energy validation; voice stress is synthetic',results=result),indent=2))
 for x in result:print(x['strategy'],x['db'],x['offsetMs'],'exact',x['exactVoiceRepetitions'],'/',x['expectedVoiceRepetitions'],'WER',round(x['voiceWordErrorRate'],3),'noise',x['noiseQwenCalls'],'words',x['syntheticWordsRecognized'],'/',x['syntheticWordsTotal'],'RTF',round(x['detectorRtf'],3))
if __name__=='__main__':main()
