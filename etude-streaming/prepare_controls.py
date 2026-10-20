"""Generate reproducible synthetic controls (not recordings from a phone).
Short-word directory: aide.wav, au-secours.wav, non.wav, oui.wav, stop.wav,
e.g. eSpeak NG fr-fr, speed 145. Resampled to mono 16 kHz.
"""
import argparse, wave
from pathlib import Path
import numpy as np
from scipy.signal import resample_poly

def save(output, name, audio):
    with wave.open(str(output/(name+'.wav')),'wb') as w:
        w.setparams((1,2,16000,0,'NONE','not compressed'))
        w.writeframes((np.clip(audio,-.999,.999)*32768).astype('<i2').tobytes())

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);p.add_argument('--short-word-directory',type=Path)
    a=p.parse_args()
    if a.output.exists() and any(a.output.iterdir()):p.error('Choose an empty output directory')
    a.output.mkdir(parents=True,exist_ok=True)
    if a.short_word_directory:
        for name in ['aide','au-secours','non','oui','stop']:
            with wave.open(str(a.short_word_directory/(name+'.wav'))) as w:
                if (w.getnchannels(),w.getsampwidth()) != (1,2):raise ValueError('Expected mono PCM16')
                audio=np.frombuffer(w.readframes(w.getnframes()),dtype='<i2').astype(np.float32)/32768;rate=w.getframerate()
            audio=resample_poly(audio,16000,rate)
            for db in [-35,-50,-60]:
                x=audio*10**(db/20)/max(np.sqrt(np.mean(audio*audio)),1e-9)
                save(a.output,name+str(db),np.concatenate([np.zeros(16000),x,np.zeros(48000)]))
    rng=np.random.default_rng(12);n=16000*150;t=np.arange(n)/16000;white=rng.standard_normal(n);freq=np.fft.rfftfreq(n,1/16000)
    pink=np.fft.irfft(np.fft.rfft(white)/np.sqrt(np.maximum(freq,1)),n=n)
    fan=np.sin(2*np.pi*60*t)+.5*np.sin(2*np.pi*120*t)+.3*np.sin(2*np.pi*240*t)+.2*white
    clicks=np.zeros(n);clicks[::8000]=1
    for name,audio in [('white',white),('pink',pink),('fan',fan),('clicks',clicks)]:
        chunks=[]
        for part,db in enumerate([-65,-45,-25]):
            x=audio[part*800000:(part+1)*800000];chunks.append(x/max(np.sqrt(np.mean(x*x)),1e-9)*10**(db/20))
        save(a.output,'noise-'+name,np.concatenate(chunks))
if __name__=='__main__':main()
