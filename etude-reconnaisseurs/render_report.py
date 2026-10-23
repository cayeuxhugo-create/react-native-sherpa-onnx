"""Render aggregate-only benchmark tables for the French report."""
import argparse,json
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__);p.add_argument('--summary',type=Path,required=True);p.add_argument('--output',type=Path,required=True);a=p.parse_args()
data=json.loads(a.summary.read_text())['results'];names={'qwen':'Qwen3-ASR 0.6B','parakeet-v3':'Parakeet TDT 0.6B v3','whisper-small':'Whisper small','whisper-turbo':'Whisper turbo'}
lines=['| Modèle | Gain max | Phrases fixes /12 | Phrases FireRed /12 | Dont FireRed 5 m /6 | Mots synthétiques FireRed /15 | Textes sur les 8 négatifs forcés |','|---|---:|---:|---:|---:|---:|---:|']
for m in data:
 f=m['scopes']['fixed'];v=m['scopes']['firered'];lines.append(f"| {names[m['model']]} | {m['inputMaxGain']:g} | {f['voiceExact']} | {v['voiceExact']} | {v['voiceByDistance']['5m']['exact']} | {v['syntheticExact']} | {f['negativeNonempty']} |")
lines+=['','Mesures hôte avec préparation du niveau max 64, même corpus FireRed ; deux threads CPU.','', '| Modèle | Poids utilisés (Mio) | Pic RSS du processus (Mio) | Temps médian par phrase, préparation + décodage (s) |','|---|---:|---:|---:|']
for m in data:
 if m['inputMaxGain']!=64:continue
 z=m['scopes']['firered'];lines.append(f"| {names[m['model']]} | {m['modelWeightMiB']:.0f} | {m['peakProcessRssKiB']/1024:.0f} | {z['voiceMedianRecognitionSeconds']:.3f} |")
a.output.write_text('\n'.join(lines)+'\n')
