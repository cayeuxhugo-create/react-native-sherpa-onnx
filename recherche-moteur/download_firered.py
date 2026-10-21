"""Fetch the research candidate and its license from a pinned upstream commit.
No app changes. All bytes are verified before creating a new output directory.
"""
import argparse,hashlib,urllib.request
from pathlib import Path
COMMIT='c30ec49e8cc69642b0ee65362eba11b9d11c6e54'
FILES={
 'fireredvad_vad.onnx':('pretrained_models/onnx_models/fireredvad_vad.onnx','a226e68e695548754a9ab5480a55bbcb8522aa69111cb97e4f093ddce63ad5dc'),
 'cmvn.ark':('pretrained_models/onnx_models/cmvn.ark','c87f6f13edf0f0ec7535ddfc9cc3387d9268cb234b70182d566c5e2edf3ca473'),
 'LICENSE':('LICENSE','c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4')}
def main():
 p=argparse.ArgumentParser(description=__doc__);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
 if a.output.exists():p.error('Choose a new directory')
 payloads={}
 for name,(relative,digest) in FILES.items():
  with urllib.request.urlopen(f'https://raw.githubusercontent.com/FireRedTeam/FireRedVAD/{COMMIT}/{relative}',timeout=30) as response:data=response.read()
  if hashlib.sha256(data).hexdigest()!=digest:raise ValueError('SHA-256 mismatch: '+name)
  payloads[name]=data
 a.output.mkdir(parents=True)
 for name,data in payloads.items():(a.output/name).write_bytes(data)
 print('Verified FireRed model, CMVN and Apache-2.0 license downloaded; no app modified')
if __name__=='__main__':main()
