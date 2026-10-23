"""Aggregate private ASR results without publishing transcripts or input IDs."""
import argparse,collections,json,re,statistics,unicodedata
from pathlib import Path

def tokens(s):
 return re.findall('[a-z0-9]+',''.join(c for c in unicodedata.normalize('NFD',s.lower()) if not unicodedata.combining(c)))
def edit(a,b):
 d=list(range(len(b)+1))
 for i,x in enumerate(a,1):
  v=[i]
  for j,y in enumerate(b,1):v.append(min(v[-1]+1,d[j]+1,d[j-1]+(x!=y)))
  d=v
 return d[-1]
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--inputs',nargs='+',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();out=[]
 for file in a.inputs:
  data=json.loads(file.read_text());meta=data['metadata'];groups=collections.defaultdict(list)
  for r in data['results']:groups[r['scope']].append(r)
  m=dict(meta,inputMaxGain=meta.get('inputMaxGain',1),modelWeightMiB=sum(v['bytes'] for v in meta['modelFiles'].values())/1024**2,scopes={})
  for scope,rows in groups.items():
   z=dict(inputs=len(rows),voiceTotal=0,voiceExact=0,voiceReferenceWords=0,voiceWordErrors=0,voiceByDistance={},syntheticTotal=0,syntheticExact=0,syntheticByLevel={},negativeTotal=0,negativeNonempty=0,audioSeconds=sum(r['audioSeconds'] for r in rows),decodeSeconds=sum(r['decodeSeconds'] for r in rows),medianDecodeSeconds=statistics.median(r['decodeSeconds'] for r in rows))
   for r in rows:
    ref=tokens(r['expected']);hyp=tokens(r['text']);exact=ref==hyp
    if r['label']=='positive':
     z['voiceTotal']+=1;z['voiceExact']+=exact;z['voiceReferenceWords']+=len(ref);z['voiceWordErrors']+=edit(ref,hyp)
     key='5m' if '5m' in r['condition'] else r['condition'].replace('voice-','');v=z['voiceByDistance'].setdefault(key,dict(total=0,exact=0));v['total']+=1;v['exact']+=exact
    elif r['label']=='short-word':
     z['syntheticTotal']+=1;z['syntheticExact']+=exact;key=r['condition'].rsplit('-',1)[-1];v=z['syntheticByLevel'].setdefault(key,dict(total=0,exact=0));v['total']+=1;v['exact']+=exact
    else:z['negativeTotal']+=1;z['negativeNonempty']+=bool(hyp)
   voice=[r for r in rows if r['label']=='positive']
   z['voiceMedianDecodeSeconds']=statistics.median(r['decodeSeconds'] for r in voice)
   if all('recognitionSeconds' in r for r in rows):
    z['recognitionSeconds']=sum(r['recognitionSeconds'] for r in rows);z['recognitionRtf']=z['recognitionSeconds']/z['audioSeconds'];z['voiceMedianRecognitionSeconds']=statistics.median(r['recognitionSeconds'] for r in voice)
   z['voiceWer']=z['voiceWordErrors']/max(1,z['voiceReferenceWords']);z['decodeRtf']=z['decodeSeconds']/z['audioSeconds'];m['scopes'][scope]=z
  out.append(m)
 a.output.write_text(json.dumps(dict(scope='French paired offline CPU export comparison; no phone/language-wide validation; forced ASR on negative windows',results=out),indent=2)+'\n')
 for m in out:
  for scope,z in m['scopes'].items():print(m['model'],'gain',m['inputMaxGain'],scope,'phrases',z['voiceExact'],'/',z['voiceTotal'],'words',z['syntheticExact'],'/',z['syntheticTotal'],'negative nonempty',z['negativeNonempty'],'/',z['negativeTotal'])
if __name__=='__main__':main()
