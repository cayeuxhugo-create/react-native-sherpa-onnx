"""Real-model prefix invariance: extending the input must not alter committed past.
Prefix length is hop-aligned so no EOF-only query is introduced in the comparison.
"""
import argparse
from pathlib import Path
import numpy as np
from compare_full_recordings import Models,read,SR
p=argparse.ArgumentParser(description=__doc__);p.add_argument('--model-root',type=Path,required=True);p.add_argument('--wav',type=Path,required=True);a=p.parse_args();m=Models(a.model_root);audio=read(a.wav)[:16*SR];prefix=audio[:8*SR].copy()
for strategy in ['stream','sliding0','sliding1.6']:
 f,v=m.streaming(audio) if strategy=='stream' else m.sliding(audio,4,float(strategy[7:]))
 g,w=m.streaming(prefix) if strategy=='stream' else m.sliding(prefix,4,float(strategy[7:]))
 k=sum(x<=8*SR for x in v)
 assert f[:k]==g and v[:k]==w,(strategy,k,len(g))
 print('PASS real-model prefix invariance',strategy,'committed frames',k,flush=True)
