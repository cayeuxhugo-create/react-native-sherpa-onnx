"""Exploratory confirmation of already segmented FireRed-only alternatives.
Does not rerun segmentation or change PCM. Original rows must not be modified.
"""
import argparse,copy,json,time
from pathlib import Path
import numpy as np
from compare_full_recordings import Models
p=argparse.ArgumentParser(description=__doc__)
for key in ['results','model-root','output']:p.add_argument('--'+key,type=Path,required=True)
a=p.parse_args();m=Models(a.model_root);rows=json.loads(a.results.read_text());out=list(rows)
for row in rows:
 if row['strategy'].split('-tail')[0] not in ['stream0.5','sliding4-lag0','sliding4-lag1.6']:continue
 new=copy.deepcopy(row);new['strategy']+='-confirm';start=time.perf_counter()
 for w in new['windows']:w['accepted']=any(m.decision(np.fromfile(w['path'],dtype='<f4')))
 new['confirmationWallSeconds']=time.perf_counter()-start;new['detectorCalls']+=len(new['windows']);out.append(new)
a.output.write_text(json.dumps(out,ensure_ascii=False,indent=2));print('Added exploratory final confirmations; rows',len(out))
