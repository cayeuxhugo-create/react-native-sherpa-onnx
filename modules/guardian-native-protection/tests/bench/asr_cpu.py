"""CPU/wall profiling of real Qwen/GTCRN, private PCM paths supplied in a jobs JSON.
No expected phrases or PCM data are emitted. CPU time is process time (all native threads).
"""
import argparse,json,time,resource,hashlib
from pathlib import Path
import numpy as np
import sherpa_onnx
p=argparse.ArgumentParser(__doc__);p.add_argument('--model',type=Path,required=True);p.add_argument('--jobs',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--gtcrn',type=Path);a=p.parse_args()
jobs=json.loads(a.jobs.read_text());rows=[]
for threads in [1,2]:
 start=time.perf_counter();c=time.process_time()
 recognizer=sherpa_onnx.OfflineRecognizer.from_qwen3_asr(conv_frontend=str(a.model/'conv_frontend.onnx'),encoder=str(a.model/'encoder.int8.onnx'),decoder=str(a.model/'decoder.int8.onnx'),tokenizer=str(a.model/'tokenizer'),num_threads=threads,sample_rate=16000,feature_dim=128,max_total_len=512,max_new_tokens=256,temperature=1e-6,top_p=.8,seed=42,provider='cpu')
 rows.append(dict(stage='QWEN_LOAD',threads=threads,cpuMs=(time.process_time()-c)*1000,wallMs=(time.perf_counter()-start)*1000))
 for trial in range(4):
  for j in jobs:
   x=np.fromfile(j['original'],dtype='<f4');s=recognizer.create_stream();s.set_option('language','French');c=time.process_time();start=time.perf_counter();s.accept_waveform(16000,x);recognizer.decode_stream(s)
   row=dict(stage='QWEN',threads=threads,trial=trial,id=j['id'],audioSeconds=len(x)/16000,cpuMs=(time.process_time()-c)*1000,wallMs=(time.perf_counter()-start)*1000,pcmSha256=hashlib.sha256(x.tobytes()).hexdigest(),textSha256=hashlib.sha256(s.result.text.encode()).hexdigest())
   if trial:rows.append(row)
 del recognizer
 if a.gtcrn and threads==1:
  cfg=sherpa_onnx.OnlineSpeechDenoiserConfig();cfg.model.gtcrn.model=str(a.gtcrn);cfg.model.num_threads=1;cfg.model.provider='cpu';d=sherpa_onnx.OnlineSpeechDenoiser(cfg)
  for trial in range(4):
   d.reset();count=0;seconds=0;c=time.process_time();start=time.perf_counter()
   for j in jobs:
    d.reset();x=np.fromfile(j['original'],dtype='<f4');seconds+=len(x)/16000
    for i in range(0,len(x)-255,256):count+=len(d.run(x[i:i+256],16000).samples)
   if trial:rows.append(dict(stage='GTCRN',threads=1,trial=trial,audioSeconds=seconds,cpuMs=(time.process_time()-c)*1000,wallMs=(time.perf_counter()-start)*1000,outputSamples=count))
metadata=dict(runtime=sherpa_onnx.__version__,rssHighWaterKiB=resource.getrusage(resource.RUSAGE_SELF).ru_maxrss,rows=rows)
a.output.write_text(json.dumps(metadata,indent=2));print('CPU benchmark completed',len(rows),'rows',flush=True)
