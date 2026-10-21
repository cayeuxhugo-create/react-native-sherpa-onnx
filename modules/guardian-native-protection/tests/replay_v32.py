"""Offline v3.2 replay. Requires numpy and sherpa-onnx==1.13.8.
This is a diagnostic harness; it never records a microphone or changes a phone.
GTCRN/three VAD views generate decisions; the actual Kotlin segmenter generates
final original-PCM windows. Optional Qwen inference uses those windows only.
"""
# Reusable faithful 256-sample GTCRN conditioning for offline regression.
from collections import deque
import numpy as np
import sherpa_onnx as s
from pathlib import Path
MODELS=Path(__file__).resolve().parents[1]/'android/src/main/assets'
def cleaned(audio):
 c=s.OnlineSpeechDenoiserConfig();c.model.gtcrn.model=str(MODELS/'gtcrn_simple.onnx');c.model.num_threads=1;d=s.OnlineSpeechDenoiser(c);out=[];pending=deque()
 for i in range(0,len(audio),256):
  x=audio[i:i+256]
  if len(x)!=256:break
  peak=float(abs(x).max());g=max(1,min(6,.8/max(peak,1e-30)));pending.append([len(x),g]);y=np.asarray(d.run(x*g,16000).samples,dtype=np.float32);offset=0
  while offset<len(y):
   n=min(len(y)-offset,pending[0][0]);y[offset:offset+n]/=pending[0][1];pending[0][0]-=n;offset+=n
   if pending[0][0]==0:pending.popleft()
  out.extend(y)
 return np.asarray(out,dtype=np.float32)
def detector():
 c=s.VadModelConfig();c.silero_vad.model=str(MODELS/'silero_vad.onnx');c.silero_vad.threshold=.5;c.silero_vad.min_speech_duration=.192;c.silero_vad.min_silence_duration=.128;c.silero_vad.window_size=512;c.sample_rate=16000;c.num_threads=1;return s.VoiceActivityDetector(c,buffer_size_in_seconds=40)
def flags(audio):
 a=cleaned(audio);v=detector();raw1=detector();raw4=detector();out=[]
 for i in range(0,len(audio),800):
  x=a[i:i+800];raw=audio[i:i+800]
  if len(x)!=800:out.append(False);continue
  g=max(1,min(4,.8/max(float(abs(x).max()),1e-30)));rg=max(1,min(4,.8/max(float(abs(raw).max()),1e-30)));v.accept_waveform(x*g);raw1.accept_waveform(raw);raw4.accept_waveform(raw*rg);out.append(v.is_speech_detected() or (raw1.is_speech_detected() and raw4.is_speech_detected()))
  for m in [v,raw1,raw4]:
   while not m.empty():m.pop()
 return out

if __name__ == "__main__":
 import argparse,wave,struct,json,subprocess,time
 parser=argparse.ArgumentParser(description=__doc__)
 parser.add_argument("--wav",required=True,type=Path)
 parser.add_argument("--output",required=True,type=Path)
 parser.add_argument("--segmenter-jar",type=Path)
 parser.add_argument("--kotlin-stdlib",type=Path)
 parser.add_argument("--qwen-directory",type=Path)
 args=parser.parse_args()
 if args.output.exists() and any(args.output.iterdir()):
  parser.error("Use a new empty output directory to avoid mixing replay runs")
 args.output.mkdir(parents=True,exist_ok=True)
 with wave.open(str(args.wav)) as w:
  if (w.getnchannels(),w.getsampwidth(),w.getframerate()) != (1,2,16000):
   parser.error("Expected mono PCM16 16000 Hz")
  audio=np.frombuffer(w.readframes(w.getnframes()),dtype="<i2").astype(np.float32)/32768
 decisions=flags(audio)
 with (args.output/"input.bin").open("wb") as f:
  for i,voice in zip(range(0,len(audio),800),decisions):
   a=audio[i:i+800];f.write(struct.pack("<I?",len(a),voice));f.write(a.astype("<f4").tobytes())
 (args.output/"vad.json").write_text(json.dumps(decisions))
 if args.segmenter_jar:
  if not args.kotlin_stdlib:parser.error("--kotlin-stdlib is required with --segmenter-jar")
  cp=str(args.segmenter_jar)+__import__('os').pathsep+str(args.kotlin_stdlib)
  subprocess.run(["java","-cp",cp,"expo.modules.guardiannativeprotection.ReplaySelectedKt",str(args.output/"input.bin"),str(args.output/"segments")],check=True)
 if args.qwen_directory:
  m=args.qwen_directory
  q=s.OfflineRecognizer.from_qwen3_asr(conv_frontend=str(m/"conv_frontend.onnx"),encoder=str(m/"encoder.int8.onnx"),decoder=str(m/"decoder.int8.onnx"),tokenizer=str(m/"tokenizer"),num_threads=2,max_total_len=512,max_new_tokens=256)
  results=[]
  for p in sorted((args.output/"segments").glob("*.f32")):
   a=np.fromfile(p,dtype="<f4");st=q.create_stream();st.set_option("language","French");st.accept_waveform(16000,a);start=time.monotonic();q.decode_stream(st)
   results.append(dict(segment=p.name,text=st.result.text,audioSeconds=len(a)/16000,wallSeconds=time.monotonic()-start))
  (args.output/"qwen.json").write_text(json.dumps(results,ensure_ascii=False,indent=2))
 print("Replay complete; this is not an Android runtime validation")
