"""Build sample-aligned replay input using the phone's VAD_CHANGE events.
Refuses capture gaps, missing transitions, resets and ambiguous sessions.
Never recomputes VAD and never modifies source WAV/log/index files.
"""
import argparse,csv,hashlib,json,struct,wave
from pathlib import Path
import numpy as np

def build(wav,index,events_path):
 text=events_path.read_text(encoding='utf-8-sig')
 try:events=json.loads(text)
 except json.JSONDecodeError:
  events=[]
  for line in text.splitlines():
   if 'GuardianVoiceDiag' not in line or '{' not in line:continue
   try:events.append(json.loads(line[line.index('{'):]))
   except json.JSONDecodeError:continue
 if not isinstance(events,list):raise ValueError('Expected events array or Guardian log')
 sessions={e.get('session') for e in events if e.get('event')=='VAD_CHANGE'}
 if len(sessions)!=1:raise ValueError('Expected exactly one VAD session; filter the log first')
 session=next(iter(sessions));events=[e for e in events if e.get('session')==session]
 if any(e.get('event')=='PIPELINE_RESET' for e in events):raise ValueError('Replay does not support pipeline resets')
 changes={}
 for e in events:
  if e.get('event')!='VAD_CHANGE':continue
  seq=e['sequence'];voice=e['speech']
  if type(seq)!=int or type(voice)!=bool or seq in changes:raise ValueError('Invalid/duplicate VAD transition')
  changes[seq]=voice
 with wave.open(str(wav)) as w:
  if (w.getnchannels(),w.getsampwidth(),w.getframerate())!=(1,2,16000):raise ValueError('Expected mono PCM16 16000 Hz')
  audio=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768
 rows=[r for r in csv.DictReader(index.open(),delimiter='\t') if r['stage']=='asr']
 if not rows or int(rows[0]['sequence']) not in changes:raise ValueError('Missing initial VAD state')
 offset=0;previous=None;speech=False;seen=set();result=bytearray()
 for row in rows:
  seq=int(row['sequence']);start=int(row['fileSampleStart']);n=int(row['sampleCount'])
  if start!=offset or n<=0 or (previous is not None and seq!=previous+1):raise ValueError('ASR index discontinuity')
  if seq in changes:speech=changes[seq];seen.add(seq)
  pcm=audio[start:start+n]
  if len(pcm)!=n:raise ValueError('WAV shorter than index')
  result.extend(struct.pack('<I?',n,speech));result.extend(pcm.astype('<f4').tobytes());offset+=n;previous=seq
 if offset!=len(audio) or seen!=set(changes):raise ValueError('Index/log/WAV coverage mismatch')
 return result,{'samples':offset,'frames':len(rows),'transitions':len(changes),'sourceHashes':{p.name:hashlib.sha256(p.read_bytes()).hexdigest() for p in [wav,index,events_path]}}

def main():
 p=argparse.ArgumentParser(description=__doc__)
 for name in ['wav','index','events','output']:p.add_argument('--'+name,type=Path,required=True)
 a=p.parse_args()
 if a.output.exists() or a.output.with_suffix('.json').exists():p.error('Choose new output files')
 binary,metadata=build(a.wav,a.index,a.events);a.output.write_bytes(binary);a.output.with_suffix('.json').write_text(json.dumps(metadata,indent=2));print('Recorded VAD replay input verified')
if __name__=='__main__':main()
