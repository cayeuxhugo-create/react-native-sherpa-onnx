"""Export exact upstream offline-policy decisions for the Kotlin reference test.

The upstream source is imported without changing its postprocessor. Its model
call is replaced by supplied probability arrays, isolating policy from numeric
model inference. Generated PCM/probabilities stay in the explicit output dir.
"""
import argparse
import hashlib
import importlib
import json
from pathlib import Path
import sys

import numpy as np
import yaml


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--upstream-root',type=Path,required=True)
    parser.add_argument('--cases',type=Path)
    parser.add_argument('--output-dir',type=Path,required=True)
    args=parser.parse_args()
    args.output_dir.mkdir(parents=True,exist_ok=True)
    sys.path.insert(0,str(args.upstream_root))
    module=importlib.import_module('fsmnvad.runtime.src.fsmnvad.Speech2VadSegmentOffline')
    module.VadOrtInferSession=lambda config,root: None
    config=yaml.safe_load((args.upstream_root/'fsmnvad/config/config.yaml').read_text())['vadPostArgs']
    references=[]
    pairs=[]

    def evaluate(name,p0_path,pcm_path):
        p0=np.fromfile(p0_path,dtype='<f4')
        pcm=np.fromfile(pcm_path,dtype='<f4')
        assert len(p0)==1+(len(pcm)-400)//160
        vad=module.E2EVadModel({},config,args.upstream_root/'fsmnvad')
        # One silence PDF and its complement suffice: the upstream policy
        # sums precisely class zero and derives speech as 1-silence.
        scores=np.stack((p0,1-p0),axis=-1)[None]
        vad.model=lambda features: [scores]
        classify=vad.get_frame_state
        states=[]
        def observed(frame):
            state=classify(frame)
            states.append(state==module.FrameState.kFrameStateSpeech)
            return state
        vad.get_frame_state=observed
        parts,_=vad.infer_offline(np.zeros((1,len(p0),400),dtype=np.float32),pcm[None],in_cache={},is_final=True)
        segments=[segment for batch in parts for segment in batch]
        result=dict(id=name,p0=str(p0_path),pcm=str(pcm_path),accepted=bool(segments),
                    speechFrames=sum(states),detectedSegments=len(segments),segmentsMs=segments)
        references.append(result)
        return result

    def synthetic(name,p0,level=.01):
        pcm=np.full(400+(len(p0)-1)*160,level,dtype=np.float32)
        pcm_path=args.output_dir/(name+'-pcm.f32')
        p0_path=args.output_dir/(name+'-p0.f32')
        pcm.tofile(pcm_path);np.asarray(p0,dtype='<f4').tofile(p0_path)
        evaluate(name,p0_path,pcm_path)

    for frames in [1,14,15,16,19,20,21,40,100,6200]:
        for state in [False,True]:
            synthetic(f'synthetic-{frames}-{state}',np.full(frames,.01 if state else .99,dtype=np.float32))
    synthetic('synthetic-zero-energy-voice',np.full(40,.01,dtype=np.float32),0.)
    synthetic('synthetic-exact-boundary',np.full(40,.2,dtype=np.float32))
    synthetic('synthetic-below-boundary',np.full(40,np.nextafter(np.float32(.2),np.float32(0)),dtype=np.float32))
    synthetic('synthetic-above-boundary',np.full(40,np.nextafter(np.float32(.2),np.float32(1)),dtype=np.float32))
    synthetic('synthetic-two-segments',np.asarray([.01 if 20<=i<=50 or 170<=i<=200 else .99 for i in range(220)],dtype=np.float32))
    synthetic('synthetic-15-in-20',np.asarray([.01 if i<20 and i%4 else .99 for i in range(21)],dtype=np.float32))
    rng=np.random.default_rng(731)
    for probability in [0,.1,.25,.5,.75,.9,1]:
        for index in range(12):
            frames=int(rng.integers(16,800))
            values=np.where(rng.uniform(size=frames)<probability,.01,.99).astype(np.float32)
            synthetic(f'synthetic-random-{probability}-{index}',values)
    if args.cases:
        for row in json.loads(args.cases.read_text()):
            reference=evaluate(row['id']+'-knf',Path(row['referenceP0']),Path(row['pcm']))
            native=evaluate(row['id']+'-cpp',Path(row['cppFeaturesP0']),Path(row['pcm']))
            pairs.append(dict(id=row['id'],speechFrameDifference=native['speechFrames']-reference['speechFrames'],
                              identicalSegments=native['segmentsMs']==reference['segmentsMs']))
    output=args.output_dir/'policy-reference-private.tsv'
    with output.open('w') as file:
        file.write('id\tp0\tpcm\taccepted\tspeechFrames\tdetectedSegments\n')
        for row in references:
            file.write('\t'.join(str(row[key]).lower() if isinstance(row[key],bool) else str(row[key])
                                 for key in ['id','p0','pcm','accepted','speechFrames','detectedSegments'])+'\n')
    source=Path(module.__file__)
    (args.output_dir/'policy-reference-private.json').write_text(json.dumps(dict(
        source=str(source),sourceSha256=hashlib.sha256(source.read_bytes()).hexdigest(),
        configuration=config,cases=references,frontendPairs=pairs),indent=2)+'\n')
    print(json.dumps(dict(cases=len(references),frontendPairs=len(pairs),
                          differentSegments=[row for row in pairs if not row['identicalSegments']],
                          differentSpeechFrames=[row for row in pairs if row['speechFrameDifference']],
                          referenceTable=str(output)),indent=2))


if __name__=='__main__':
    main()
