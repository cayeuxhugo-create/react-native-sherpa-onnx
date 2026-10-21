"""Aggregate private benchmark results without publishing voice metadata."""
import argparse,json
from pathlib import Path

def load(p):
 x=json.loads(p.read_text());return x['results'] if isinstance(x,dict) else x

def counts(rows):
 return {l:dict(accepted=sum(x['accepted'] for x in rows if x['label']==l),total=sum(x['label']==l for x in rows)) for l in sorted({x['label'] for x in rows})}

def fusion(a,b,op):
 other={x['id']:x for x in b['decisions']};assert set(other)=={x['id'] for x in a['decisions']}
 return [dict(id=x['id'],label=x['label'],accepted=(x['accepted'] and other[x['id']]['accepted']) if op=='and' else (x['accepted'] or other[x['id']]['accepted'])) for x in a['decisions']]

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--fsmn-results',type=Path,required=True);p.add_argument('--firered-results',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args();r=a.fsmn_results;f=a.firered_results
 output=dict(scope='Admission of completed windows on CPU; no ASR score or Android validation',sweep=[{k:v for k,v in x.items() if k!='decisions'} for x in load(r/'fsmn-original-verified.json')],comparisons={})
 original=load(r/'fsmn-original-verified.json')
 ref=next(x for x in original if x['gainMax']==64 and x['speechNoiseThreshold']==.4);reverse=load(r/'fsmn-reset-check.json')[0]
 assert {x['id']:x['segmentsMs'] for x in ref['decisions']}=={x['id']:x['segmentsMs'] for x in reverse['decisions']}
 output['reversedOrderIdenticalSegments']=True
 firebase=next(x for x in load(f/'firered-nonstream-verified-results.json') if 'threshold0.5-gainMax64' in x['candidate'])
 for threshold in [.4,.6]:
  candidate=next(x for x in original if x['gainMax']==64 and x['speechNoiseThreshold']==threshold)
  for label,fn,fr in [('original',None,None),('noisy-controls','fsmn-controls','firered-controls.json'),('nonspeech-controls','fsmn-noise','firered-full-noise-results.json')]:
   one=candidate if fn is None else load(r/(fn+('-sensitive' if threshold==.4 else '')+'.json'))[0]
   two=firebase if fr is None else load(f/fr)[0]
   output['comparisons'][f'threshold{threshold}-{label}']=dict(fsmn=one['counts'],firered=two['counts'],both=counts(fusion(one,two,'and')),either=counts(fusion(one,two,'or')))
 stress=load(r/'fsmn-stress-trim.json');firestress=load(f/'firered-stress.json')+load(f/'firered-half-hop-stress.json');lookup={(x['db'],x['offsetMs']):x for x in firestress};output['stress']=[]
 for x in stress:
  y=lookup[(x['levelDb'],x['offsetMs'])];output['stress'].append(dict(threshold=x['speechNoiseThreshold'],db=x['levelDb'],offsetMs=x['offsetMs'],fsmn=x['counts'],firered=y['counts'],both=counts(fusion(x,y,'and'))))
 output['models']=json.loads((r/'fsmn-original-verified.json').read_text())['models']
 a.output.write_text(json.dumps(output,indent=2))
 for t in [.4,.6,.8]:
  z=[x for x in output['stress'] if x['threshold']==t]
  for rule in ['fsmn','both']:
   print(t,rule,'perfect variants',sum(x[rule]['positive']['accepted']==12 and x[rule]['negative']['accepted']==0 and x[rule]['short-word']['accepted']==15 for x in z),'of',len(z),'phrase range',min(x[rule]['positive']['accepted'] for x in z),max(x[rule]['positive']['accepted'] for x in z))
if __name__=='__main__':main()
