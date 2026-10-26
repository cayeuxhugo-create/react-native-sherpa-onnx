"""Additional initialization control, not a new parameter-search winner.
The old noise-profile sample support ends by3.008s; wait3.04s, replay buffered
prefix internally, fade100ms. Returned original candidates crossing startup
must use BOTH original confirmation and original ASR, not a mixed window.
"""
import numpy as np
from filters import apply,configs,SAMPLE_RATE
READY_SECONDS=3.04
FADE_SECONDS=.1
STABLE_SECONDS=READY_SECONDS+FADE_SECONDS
LOOKAHEAD_SAMPLES=512

def delayed_legacy(x):
 x=np.asarray(x,dtype=np.float32)
 ready=round(READY_SECONDS*SAMPLE_RATE)
 if len(x)<=ready:return x.copy()
 c=next(c for c in configs() if c['family']=='legacy_wiener')
 y=apply(x,c);out=y.copy();out[:ready]=x[:ready]
 count=min(len(x)-ready,round(FADE_SECONDS*SAMPLE_RATE))
 alpha=np.arange(1,count+1,dtype=np.float32)/round(FADE_SECONDS*SAMPLE_RATE)
 out[ready:ready+count]=x[ready:ready+count]*(1-alpha)+y[ready:ready+count]*alpha
 return out

def use_original_candidate(start_sample):
 return start_sample<round(STABLE_SECONDS*SAMPLE_RATE)
