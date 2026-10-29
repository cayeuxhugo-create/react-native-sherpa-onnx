"""Collector contract for independently segmented filtered reference windows.

Only synthetic audio is used. Exercise the real export validator with reserved
positive study IDs, mixed origins and a real two-second context at the 8 s cap.
"""
from pathlib import Path
import csv
import hashlib
import json
import os
import struct
import subprocess
import tempfile
import wave


REPO = Path(__file__).resolve().parents[3]
COLLECTOR = (REPO / 'enregistrer-guardian-reference.ps1').read_text(encoding='utf-8-sig')
VALIDATOR = COLLECTOR.split('# BEGIN REFERENCE EXPORT VALIDATION', 1)[1].split('# END REFERENCE EXPORT VALIDATION', 1)[0]
LANES = {'primary': 'truth', 'reference': 'raw', 'short': 'pregain', 'long': 'asr'}
REGISTRY_FIELDS = ['path', 'kind', 'epoch', 'segment', 'variant', 'lane', 'globalStart', 'samples', 'windowSource']
TIMELINE_FIELDS = ['epoch', 'lane', 'routeRevision', 'originSample', 'globalStart', 'globalEnd', 'fileSampleStart', 'sampleCount', 'sequence', 'capturedAtNs']


def write_csv(path, rows, fields):
    with path.open('w', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def fixture(root, mode):
    root.mkdir()
    origin, full_samples = 80000, 200000
    rows, timeline = [], []
    full = {}

    def audio(stem, data, kind, lane, start, segment, variant, source):
        path = root / stem
        path.parent.mkdir(parents=True, exist_ok=True)
        path.with_suffix('.f32').write_bytes(data)
        with wave.open(str(path.with_suffix('.wav')), 'wb') as stream:
            stream.setnchannels(1)
            stream.setsampwidth(2)
            stream.setframerate(16000)
            stream.writeframes(b'\0' * (len(data) // 2))
        for extension in ['f32', 'wav']:
            rows.append(dict(path=stem + '.' + extension, kind=kind, epoch=1,
                             segment=segment, variant=variant, lane=lane,
                             globalStart=start, samples=len(data) // 4, windowSource=source))

    for index, (lane, stage) in enumerate(LANES.items()):
        full[lane] = struct.pack('<' + 'f' * full_samples,
                                 *(float((n + index * 7) % 101) / 1024 for n in range(full_samples)))
        audio('epoch-1/guardian-voice-full-' + stage, full[lane], 'full', lane,
              origin, 0, 'FULL', 'CONTINUOUS')
        for offset in [0, 100000]:
            timeline.append(dict(epoch=1, lane=lane, routeRevision=1, originSample=origin,
                                 globalStart=origin + offset, globalEnd=origin + offset + 100000,
                                 fileSampleStart=offset, sampleCount=100000, sequence=offset // 800 + 1,
                                 capturedAtNs=1000000000 + offset * 62500))
    specifications = [('PRIMARY_ORIGINAL', 1, 16000, 16000),
                      ('FILTERED_STREAM_LONG', 1000000000, 32000, 96000)]
    if mode == 'filtered-only':
        specifications = specifications[1:]
    elif mode == 'same-id-collision':
        specifications[1] = ('FILTERED_STREAM_LONG', 1, 32000, 96000)
    elif mode == 'negative-study-id':
        specifications[1] = ('FILTERED_STREAM_LONG', -1000000000, 32000, 96000)
    for source, segment, offset, exact_samples in specifications:
        for variant, samples in [('EXACT', exact_samples), ('END_PLUS_2S', exact_samples + 32000)]:
            if mode == 'context-over-cap' and source == 'FILTERED_STREAM_LONG' and variant == 'END_PLUS_2S':
                samples += 1
            for lane, stage in LANES.items():
                # Different filenames deliberately preserve both exports in the
                # collision scenario: only the segment/group identity is wrong.
                stem = 'epoch-1/segment-' + str(segment) + '/' + variant + '/guardian-voice-' + source + '-' + stage
                audio(stem, full[lane][offset * 4:(offset + samples) * 4], 'pair', lane,
                      origin + offset, segment, variant, source)
    write_csv(root / 'reference-files.csv', rows, REGISTRY_FIELDS)
    write_csv(root / 'reference-timeline.csv', timeline, TIMELINE_FIELDS)
    return rows


def snapshot(root):
    return {str(path.relative_to(root)): hashlib.sha256(path.read_bytes()).hexdigest()
            for path in root.rglob('*') if path.is_file()}


def assert_pairs(root, rows):
    full = {row['lane']: row for row in rows if row['kind'] == 'full' and row['path'].endswith('.f32')}
    pairs = {}
    for row in rows:
        if row['kind'] != 'pair' or not row['path'].endswith('.f32'):
            continue
        source = full[row['lane']]
        offset = (row['globalStart'] - source['globalStart']) * 4
        payload = (root / row['path']).read_bytes()
        assert payload == (root / source['path']).read_bytes()[offset:offset + len(payload)]
        key = (row['windowSource'], row['segment'], row['lane'])
        pairs.setdefault(key, {})[row['variant']] = row
    for variants in pairs.values():
        exact, context = variants['EXACT'], variants['END_PLUS_2S']
        assert exact['globalStart'] == context['globalStart']
        assert context['samples'] - exact['samples'] == 32000
        assert context['samples'] <= 128000


with tempfile.TemporaryDirectory(prefix='guardian-filtered-reference-exports-') as temporary:
    temporary = Path(temporary)
    runner = temporary / 'validate.ps1'
    runner.write_text('param([string]$Directory)\n$ErrorActionPreference="Stop"\n' + VALIDATOR +
                      '\nAssert-GuardianReferenceExport $Directory | ConvertTo-Json -Depth 5\n', encoding='utf-8-sig')
    env = os.environ.copy()
    for key in ['XDG_CONFIG_HOME', 'XDG_CACHE_HOME', 'XDG_DATA_HOME']:
        env[key] = str(temporary / key)
        Path(env[key]).mkdir()
    cases = {'mixed-origins': None, 'filtered-only': None,
             'same-id-collision': 'Les quatre voies reference',
             'negative-study-id': 'Chemin reference duplique ou interdit',
             'context-over-cap': 'Fenetre reference depasse 8 s'}
    for mode, error in cases.items():
        directory = temporary / mode
        rows = fixture(directory, mode)
        before = snapshot(directory)
        result = subprocess.run([os.environ.get('PWSH', 'pwsh'), '-NoProfile', '-File', str(runner), str(directory)],
                                env=env, capture_output=True, text=True, timeout=30)
        assert snapshot(directory) == before, ('Validator changed an export', mode)
        assert (result.returncode == 0) == (error is None), (mode, result.stdout, result.stderr)
        if error:
            assert error in result.stderr, (mode, result.stderr)
        else:
            data = json.loads(result.stdout)
            count = 2 if mode == 'filtered-only' else 4
            assert data['fullFloatStreams'] == 4 and data['pairedWindows'] == count
            assert data['pairedFloatStreams'] == count * 4 and data['byteExactPairs']
            assert_pairs(directory, rows)
            filtered = [row for row in rows if row['windowSource'] == 'FILTERED_STREAM_LONG']
            assert all(row['segment'] >= 1000000000 for row in filtered)
            assert any(row['variant'] == 'END_PLUS_2S' and row['samples'] == 128000 for row in filtered)
        print('PASS independent filtered reference export', mode, flush=True)
    print('PASS', len(cases), 'independent filtered reference export scenarios', flush=True)
