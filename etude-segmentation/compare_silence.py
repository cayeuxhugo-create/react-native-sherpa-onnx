"""Replays identical audio/VAD decisions through the actual Kotlin segmenter.
Only END_SILENCE_MS varies in temporary source copies. No module is overwritten.
Requires numpy, sherpa-onnx==1.13.8, Java and the Kotlin compiler/stdlib jars.
Outputs contain recognized speech; keep them private.
"""
import argparse,shutil,csv,hashlib,importlib.util,json,os,re,struct,subprocess,time,wave
from pathlib import Path
import numpy as np
import sherpa_onnx as s

def main():
 p=argparse.ArgumentParser(description=__doc__)
 p.add_argument('--module',type=Path,required=True);p.add_argument('--compiler-classpath',required=True);p.add_argument('--stdlib',type=Path,required=True)
 p.add_argument('--qwen',type=Path,required=True);p.add_argument('--wav',type=Path,nargs='+',required=True);p.add_argument('--output',type=Path,required=True)
 p.add_argument('--recorded-input',type=Path,help='Sample-aligned input from build_recorded_input.py; requires exactly one WAV')
 a=p.parse_args()
 if a.recorded_input and len(a.wav)!=1:p.error('Recorded input requires exactly one WAV')
 if a.output.exists():p.error('Choose a new output directory')
 a.output.mkdir(parents=True)
 frontend=a.module/'tests/replay_v32.py';spec=importlib.util.spec_from_file_location('frontend',frontend);f=importlib.util.module_from_spec(spec);spec.loader.exec_module(f)
 source=(a.module/'android/src/main/java/expo/modules/guardiannativeprotection/GuardianSpeechSegmenter.kt').read_text()
 jars={}
 for ms in [800,1000,1200]:
  d=a.output/str(ms);d.mkdir();copy,n=re.subn(r'const val END_SILENCE_MS = \d+',f'const val END_SILENCE_MS = {ms}',source)
  if n!=1:raise ValueError('Expected one END_SILENCE_MS declaration')
  (d/'GuardianSpeechSegmenter.kt').write_text(copy);jars[ms]=d/'replay.jar'
  subprocess.run(['java','-cp',a.compiler_classpath,'org.jetbrains.kotlin.cli.jvm.K2JVMCompiler','-no-stdlib','-no-reflect','-classpath',str(a.stdlib),'-d',str(jars[ms]),str(d/'GuardianSpeechSegmenter.kt'),str(Path(__file__).with_name('ReplayTimed.kt'))],check=True)
 m=a.qwen;q=s.OfflineRecognizer.from_qwen3_asr(conv_frontend=str(m/'conv_frontend.onnx'),encoder=str(m/'encoder.int8.onnx'),decoder=str(m/'decoder.int8.onnx'),tokenizer=str(m/'tokenizer'),num_threads=2,max_total_len=512,max_new_tokens=256)
 result={'segmenterSourceSha256':hashlib.sha256(source.encode()).hexdigest(),'frontendSha256':hashlib.sha256(frontend.read_bytes()).hexdigest(),'sherpaVersion':s.__version__,'cases':[]}
 for idx,path in enumerate(a.wav):
  with wave.open(str(path)) as w:
   if (w.getnchannels(),w.getsampwidth(),w.getframerate())!=(1,2,16000):raise ValueError('Expected mono PCM16 16000 Hz')
   audio=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768
  decisions=[] if a.recorded_input else f.flags(audio);d=a.output/f'case-{idx}';d.mkdir()
  with (d/'input.bin').open('wb') as stream:
   for i,voice in zip(range(0,len(audio),800),decisions):
    x=audio[i:i+800];stream.write(struct.pack('<I?',len(x),voice));stream.write(x.astype('<f4').tobytes())
  if a.recorded_input:shutil.copyfile(a.recorded_input,d/'input.bin')
  case={'input':str(path),'inputSha256':hashlib.sha256(path.read_bytes()).hexdigest(),'variants':[]}
  for ms,jar in jars.items():
   dest=d/str(ms)
   subprocess.run(['java','-cp',str(jar)+os.pathsep+str(a.stdlib),'expo.modules.guardiannativeprotection.ReplayTimedKt',str(d/'input.bin'),str(dest)],check=True)
   windows=[]
   for event in csv.DictReader((dest/'events.tsv').open(),delimiter='\t'):
    pcm=np.fromfile(dest/f"segment-{event['id']}.f32",dtype='<f4');st=q.create_stream();st.set_option('language','French');st.accept_waveform(16000,pcm);start=time.monotonic();q.decode_stream(st)
    windows.append(dict(audioSeconds=len(pcm)/16000,availableAtSeconds=int(event['availableAtSample'])/16000,reason=event['reason'],text=st.result.text,decodeWallSeconds=time.monotonic()-start,pcmSha256=hashlib.sha256(pcm.tobytes()).hexdigest()))
   case['variants'].append(dict(endSilenceMs=ms,windows=windows));print(f'case {idx}: {ms} ms, {len(windows)} windows',flush=True)
  result['cases'].append(case);(a.output/'results.json').write_text(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
