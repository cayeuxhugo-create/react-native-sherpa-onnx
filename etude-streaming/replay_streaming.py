"""French online-ASR evaluation, CPU replay only; never triggers Guardian alerts.
Dependencies: numpy, sherpa-onnx==1.13.8. Inputs: mono PCM16/16 kHz WAV.
Wall times are desktop replay costs, not Android latency. Output contains speech.
"""
import argparse, hashlib, json, time, wave
from pathlib import Path
import numpy as np
import sherpa_onnx as s

def read(path):
    with wave.open(str(path)) as w:
        if (w.getnchannels(), w.getsampwidth(), w.getframerate()) != (1, 2, 16000):
            raise ValueError('Expected mono PCM16/16000 Hz')
        return np.frombuffer(w.readframes(w.getnframes()), dtype='<i2').astype(np.float32)/32768

def replay(model, audio, model_type="zipformer2", encoder="encoder.onnx", decoder="decoder.onnx", joiner="joiner.onnx"):
    r=s.OnlineRecognizer.from_transducer(tokens=str(model/'tokens.txt'),encoder=str(model/encoder),decoder=str(model/decoder),joiner=str(model/joiner),model_type=model_type,num_threads=2,enable_endpoint_detection=True,rule1_min_trailing_silence=2.4,rule2_min_trailing_silence=1.2,rule3_min_utterance_length=20)
    st=r.create_stream(); changes=[]; finals=[]; last=''; start=time.monotonic(); real_samples=len(audio)
    # Fixed trailing silence flushes pending encoder context; marked separately.
    padded=np.concatenate((audio,np.zeros(48000,dtype=np.float32)))
    for i in range(0,len(padded),800):
        st.accept_waveform(16000,padded[i:i+800])
        while r.is_ready(st): r.decode_stream(st)
        text=r.get_result(st).strip(); t=min(i+800,len(padded))/16000
        if text != last:
            changes.append(dict(atSeconds=t,text=text,inFlush=i>=real_samples));last=text
        if r.is_endpoint(st):
            if text: finals.append(dict(atSeconds=t,text=text,inFlush=i>=real_samples))
            r.reset(st);last=''
            changes.append(dict(atSeconds=t,text='',inFlush=i>=real_samples,endpointReset=True))
    st.input_finished()
    while r.is_ready(st): r.decode_stream(st)
    text=r.get_result(st).strip()
    if text: finals.append(dict(atSeconds=len(padded)/16000,text=text,inFlush=True))
    elapsed=time.monotonic()-start
    return dict(durationSeconds=real_samples/16000,wallSeconds=elapsed,realTimeFactor=elapsed/(real_samples/16000),finals=finals,partialChanges=changes)

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--model',type=Path,required=True);p.add_argument('--wav',type=Path,nargs='+',required=True);p.add_argument('--output',type=Path,required=True)
    p.add_argument('--model-type',default='zipformer2');p.add_argument('--encoder',default='encoder.onnx');p.add_argument('--decoder',default='decoder.onnx');p.add_argument('--joiner',default='joiner.onnx')
    a=p.parse_args()
    if a.output.exists(): p.error('Output already exists; choose a new file')
    result={'sherpaVersion':s.__version__,'modelHashes':{n:hashlib.sha256((a.model/n).read_bytes()).hexdigest() for n in [a.encoder,a.decoder,a.joiner,'tokens.txt']},'inputs':[]}
    for path in a.wav:
        row=replay(a.model,read(path),a.model_type,a.encoder,a.decoder,a.joiner);row['input']=str(path);row['inputSha256']=hashlib.sha256(path.read_bytes()).hexdigest();result['inputs'].append(row)
        print(path.name, json.dumps(row['finals'],ensure_ascii=False),flush=True)
    a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(result,ensure_ascii=False,indent=2))
if __name__=='__main__':main()
