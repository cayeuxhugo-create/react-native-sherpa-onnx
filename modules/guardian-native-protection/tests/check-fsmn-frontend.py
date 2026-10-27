"""Compare native FSMN features with kaldi-native-fbank and upstream CMVN.

No corpus is required. --manifest optionally adds private clips without storing
them in the repository. Requires kaldi_native_fbank 1.22.4 and a C++ compiler.
"""
import argparse
import json
from pathlib import Path
import subprocess
import tempfile

import kaldi_native_fbank as knf
import numpy as np

MODULE = Path(__file__).resolve().parents[1]


def read_cmvn(path):
    lines = path.read_text().splitlines()
    values = []
    for i, line in enumerate(lines):
        if line.split()[:1] in [['<AddShift>'], ['<Rescale>']]:
            following = lines[i+1].split()
            assert following[0] == '<LearnRateCoef>'
            values.append(np.asarray(following[3:-1], dtype=np.float64))
    assert len(values) == 2 and all(len(row) == 400 for row in values)
    return np.stack(values)


def reference(pcm, cmvn):
    opts = knf.FbankOptions()
    opts.frame_opts.samp_freq = 16000
    opts.frame_opts.dither = 0
    opts.frame_opts.window_type = 'hamming'
    opts.frame_opts.frame_shift_ms = 10
    opts.frame_opts.frame_length_ms = 25
    opts.frame_opts.snip_edges = True
    opts.mel_opts.num_bins = 80
    opts.energy_floor = 0
    opts.mel_opts.debug_mel = False
    fbank = knf.OnlineFbank(opts)
    fbank.accept_waveform(16000, (pcm*np.float32(32768)).tolist())
    raw = np.asarray([fbank.get_frame(i) for i in range(fbank.num_frames_ready)], dtype=np.float32).reshape(-1,80)
    if not len(raw):
        return raw, np.empty((0,400), dtype=np.float32)
    indices = np.clip(np.arange(len(raw))[:,None]+np.arange(-2,3),0,len(raw)-1)
    lfr = raw[indices].reshape(-1,400)
    return raw, ((lfr+cmvn[0])*cmvn[1]).astype(np.float32)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--cmvn',type=Path,required=True)
    parser.add_argument('--manifest',type=Path)
    parser.add_argument('--output',type=Path)
    parser.add_argument('--probability-model',type=Path)
    parser.add_argument('--reference-export-dir',type=Path)
    args=parser.parse_args()
    cmvn=read_cmvn(args.cmvn)
    session=None
    if args.probability_model:
        import onnxruntime as ort
        options=ort.SessionOptions()
        options.intra_op_num_threads=options.inter_op_num_threads=1
        options.log_severity_level=3
        session=ort.InferenceSession(str(args.probability_model),sess_options=options,providers=['CPUExecutionProvider'])
    if args.reference_export_dir:
        assert session is not None
        args.reference_export_dir.mkdir(parents=True,exist_ok=True)
    exports=[]
    rng=np.random.default_rng(529)
    cases=[]
    for length in [0,1,399,400,559,560,719,720,16000,16193]:
        cases.append((f'noise-{length}',rng.normal(0,.02,length).astype(np.float32)))
    cases += [('silence',np.zeros(16093,dtype=np.float32)),
              ('constant',np.full(16093,.17,dtype=np.float32)),
              ('tiny',rng.normal(0,1e-7,16093).astype(np.float32)),
              ('tone',(np.sin(np.arange(16093)*2*np.pi*197/16000)*.06).astype(np.float32))]
    if args.manifest:
        for row in json.loads(args.manifest.read_text()):
            pcm=np.fromfile(row['path'],dtype='<f4')
            peak=float(np.max(abs(pcm)))
            gain=np.maximum(np.float32(1),np.minimum(np.float32(64),np.float32(.4)/np.float32(max(peak,1e-30))))
            cases.append((row['id'],pcm*gain))
    results=[]
    with tempfile.TemporaryDirectory(prefix='guardian-fsmn-frontend-') as temp:
        temp=Path(temp)
        binary=temp/'reference'
        native=MODULE/'android/src/main/cpp'
        subprocess.run(['c++','-std=c++17','-O2','-I'+str(native),'-I'+str(native/'firered'),
                        str(MODULE/'tests/native/FsmnFrontendReference.cc'),str(native/'firered/frontend/fft.cc'),
                        '-o',str(binary)],check=True)
        cmvn.astype('<f8').tofile(temp/'cmvn.bin')
        for name,pcm in cases:
            pcm.astype('<f4').tofile(temp/'input.f32')
            subprocess.run([str(binary),str(temp/'input.f32'),str(temp/'cmvn.bin'),str(temp/'fbank.f32'),str(temp/'features.f32')],check=True,capture_output=True)
            raw,features=reference(pcm,cmvn)
            native_raw=np.fromfile(temp/'fbank.f32',dtype='<f4').reshape(-1,80)
            native_features=np.fromfile(temp/'features.f32',dtype='<f4').reshape(-1,400)
            assert native_raw.shape==raw.shape and native_features.shape==features.shape
            error=float(np.max(abs(raw-native_raw),initial=0))
            normalized_error=float(np.max(abs(features-native_features),initial=0))
            # Different Float32 FFT implementations are not bit exact. These
            # bounds qualify the frontend numerically; the model/frame/segment
            # comparisons below still require the actual decisions to agree.
            assert error <= .003, (name,'FBANK error',error)
            assert normalized_error <= .001, (name,'CMVN error',normalized_error)
            result=dict(id=name,frames=len(raw),maxFbankError=error,maxNormalizedError=normalized_error)
            if session is not None and len(raw):
                reference_prob=session.run(None,{session.get_inputs()[0].name:features[None]})[0]
                native_prob=session.run(None,{session.get_inputs()[0].name:native_features[None]})[0]
                result['maxProbabilityError']=float(np.max(abs(reference_prob-native_prob)))
                result['maxSilenceProbabilityError']=float(np.max(abs(reference_prob[0,:,0]-native_prob[0,:,0])))
                a=reference_prob[0,:,0].astype(np.float64)
                b=native_prob[0,:,0].astype(np.float64)
                speech_a=(np.float32(1)-reference_prob[0,:,0]).astype(np.float64)
                speech_b=(np.float32(1)-native_prob[0,:,0]).astype(np.float64)
                result['speechFrameDisagreements']=int(np.sum((speech_a>=a+.6)!=(speech_b>=b+.6)))
                assert result['maxSilenceProbabilityError'] <= .0001, (name,result)
                assert result['speechFrameDisagreements'] == 0, (name,result)
                if args.reference_export_dir:
                    pcm_path=args.reference_export_dir/(name+'-conditioned.f32')
                    reference_path=args.reference_export_dir/(name+'-reference-p0.f32')
                    native_path=args.reference_export_dir/(name+'-cpp-features-p0.f32')
                    pcm.astype('<f4').tofile(pcm_path)
                    reference_prob[0,:,0].astype('<f4').tofile(reference_path)
                    native_prob[0,:,0].astype('<f4').tofile(native_path)
                    exports.append(dict(id=name,pcm=str(pcm_path),referenceP0=str(reference_path),cppFeaturesP0=str(native_path)))
            results.append(result)
        print(json.dumps(results,indent=2))
    if args.output:
        args.output.write_text(json.dumps(dict(kaldiNativeFbankVersion=knf.__version__,cases=results),indent=2)+'\n')
    if args.reference_export_dir:
        (args.reference_export_dir/'manifest-private.json').write_text(json.dumps(exports,indent=2)+'\n')


if __name__=='__main__':
    main()
