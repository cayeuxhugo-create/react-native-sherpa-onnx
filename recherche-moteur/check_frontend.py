"""Verify the CPU ONNX frontend against upstream's feature-only classes.
Loads CMVN and KaldifeatFbank definitions only; no PyTorch model is simulated.
Also checks the upstream postprocessor contract (binary decisions -> segments).
"""
import argparse,ast,math,os,sys,types,importlib.util
from pathlib import Path
import numpy as np,kaldi_native_fbank as knf,kaldiio
from evaluate_new_vad import frontend,base

def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--source',type=Path,required=True);p.add_argument('--cmvn',type=Path,required=True);a=p.parse_args()
 path=a.source/'fireredvad/core/audio_feat.py';tree=ast.parse(path.read_text());nodes=[n for n in tree.body if isinstance(n,ast.ClassDef) and n.name in ['CMVN','KaldifeatFbank']];assert len(nodes)==2
 namespace={'np':np,'math':math,'os':os,'knf':knf,'kaldiio':kaldiio};exec(compile(ast.Module(body=nodes,type_ignores=[]),str(path),'exec'),namespace)
 original_fb=namespace['KaldifeatFbank'](80,25,10,0);cmvn=namespace['CMVN'](str(a.cmvn));stats=kaldiio.load_mat(str(a.cmvn));rng=np.random.default_rng(20261021)
 arrays=[np.zeros(32000,dtype=np.float32),rng.uniform(-.05,.05,32000).astype(np.float32),base.read(a.source/'assets/hello_en.wav')]
 for i,audio in enumerate(arrays):
  pcm=np.round(np.clip(audio,-1,32767/32768)*32768).astype(np.int16);reference=cmvn(original_fb((16000,pcm))).astype(np.float32);got=frontend(audio,stats);assert reference.shape==got.shape;error=float(np.max(abs(reference-got)));assert error<1e-5;print('frontend',i,'max difference',error)
 print('Feature frontend agrees with upstream on silence, noise and public speech')
 for name,path in [('fireredvad',a.source/'fireredvad'),('fireredvad.core',a.source/'fireredvad/core')]:
  module=types.ModuleType(name);module.__path__=[str(path)];sys.modules[name]=module
 spec=importlib.util.spec_from_file_location('fireredvad.core.vad_postprocessor',a.source/'fireredvad/core/vad_postprocessor.py');post=importlib.util.module_from_spec(spec);sys.modules[spec.name]=post;spec.loader.exec_module(post)
 for probability,expected in [(0.,False),(1.,True)]:
  pp=post.VadPostprocessor(5,.5,20,2000,20,0,0);decisions=pp.process([probability]*100);assert len(decisions)==100;assert set(decisions)<=set([0,1]);segments=pp.decision_to_segment(decisions);assert bool(segments)==expected
 print('Postprocessor contract verified: all-zero frames have no speech segments; all-one frames do')
if __name__=='__main__':main()
