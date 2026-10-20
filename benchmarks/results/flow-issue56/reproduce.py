import argparse
import hashlib
import json
import pathlib
import platform
import shlex
import statistics
import subprocess
import tempfile
import time

parser = argparse.ArgumentParser()
parser.add_argument('--target', required=True)
parser.add_argument('--jar', default='app/build/libs/visiontest.jar')
parser.add_argument('--output', required=True)
parser.add_argument('--samples', type=int, default=7)
parser.add_argument('--warmups', type=int, default=2)
args = parser.parse_args()
output = pathlib.Path(args.output)
output.mkdir(parents=True, exist_ok=True)
jar = pathlib.Path(args.jar).resolve()
prefix = ['java', '-jar', str(jar)]
fixture = 'com.example.visiontest.benchmark'

def invoke(command):
    start = time.perf_counter_ns()
    result = subprocess.run(command, capture_output=True, text=True, timeout=180)
    elapsed = (time.perf_counter_ns() - start) / 1_000_000
    if result.returncode:
        raise RuntimeError(f'{command}: exit {result.returncode}\n{result.stdout}\n{result.stderr}')
    return result.stdout, elapsed

def reset():
    subprocess.run(['xcrun', 'simctl', 'terminate', args.target, fixture], capture_output=True)
    invoke(['xcrun', 'simctl', 'launch', args.target, fixture, 'navigation', 'en'])
    ready, _ = invoke(prefix + ['wait_for_element', '-p', 'ios', '--bundle-id', fixture,
                              '--resource-id', 'fixture_ready', '--timeout', '10000'])
    assert json.loads(ready)['result']['found'] is True

def run(mode, image):
    steps = [
        ['input_text', 'flow-benchmark@example.com', '--target-resource-id', 'email', '--bundle-id', fixture],
        ['tap_on_element', '--resource-id', 'continue', '--bundle-id', fixture],
        ['wait_for_element', '--resource-id', 'done', '--bundle-id', fixture],
        ['screenshot', '--output', str(image)],
    ]
    start = time.perf_counter_ns()
    processes = []
    if mode == 'separate':
        for index, step in enumerate(steps):
            raw, elapsed = invoke(prefix + step + ['-p', 'ios'])
            processes.append(elapsed)
            if index < 2:
                assert json.loads(raw)['result']['success'] is True
            elif index == 2:
                assert json.loads(raw)['result']['found'] is True
        raw, elapsed = invoke(prefix + ['get_interactive_elements', '-p', 'ios', '--bundle-id', fixture, '--json'])
        processes.append(elapsed)
        elements = json.loads(raw)
    else:
        command = prefix + ['flow', '-p', 'ios', '--bundle-id', fixture, '--json']
        for step in steps:
            command += ['--step', shlex.join(step)]
        command += ['--observe', 'interactive-elements']
        raw, elapsed = invoke(command)
        processes.append(elapsed)
        report = json.loads(raw)
        assert report['success'] is True and report['exitCode'] == 0
        assert len(report['steps']) == 4
        assert all(step['status'] == 'succeeded' for step in report['steps'])
        assert report['steps'][3]['result']['path'] == str(image)
        elements = report['observations'][0]['result']
    elapsed = (time.perf_counter_ns() - start) / 1_000_000
    assert elements['success'] is True
    assert any(element.get('resourceId') == 'done' for element in elements['elements'])
    image_bytes = image.read_bytes()
    assert image_bytes.startswith(b'\x89PNG\r\n\x1a\n')
    return {'mode': mode, 'elapsedMs': elapsed, 'processMs': processes,
            'processCount': len(processes), 'screenshotBytes': len(image_bytes),
            'finalElementCount': elements['count']}

metadata = {
    'dateUtc': time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime()),
    'target': args.target, 'fixture': fixture, 'scenario': 'navigation', 'language': 'en',
    'host': platform.platform(), 'architecture': platform.machine(),
    'java': subprocess.run(['java', '-version'], capture_output=True, text=True).stderr.strip(),
    'xcode': subprocess.check_output(['xcodebuild', '-version'], text=True).strip(),
    'simulators': subprocess.check_output(['xcrun', 'simctl', 'list', 'devices', 'booted'], text=True).strip(),
    'sourceCommit': subprocess.check_output(['git', 'rev-parse', 'HEAD'], text=True).strip(),
    'jarSha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
    'samplesPerMode': args.samples, 'warmupsPerMode': args.warmups,
    'processesPerSequence': {'separate': 5, 'flow': 1},
    'timing': 'perf_counter_ns wall time, complete subprocess startup through exit and output capture',
    'setupExcluded': 'terminate and launch navigation fixture; explicit fixture_ready wait',
    'order': 'separate,flow on even rounds; flow,separate on odd rounds',
    'actions': ['targeted input email', 'tap continue', 'wait done', 'screenshot', 'observe interactive elements'],
    'tracePerformance': False,
}
(output / 'environment.json').write_text(json.dumps(metadata, indent=2) + '\n')
records = []
with tempfile.TemporaryDirectory(prefix='visiontest-flow-benchmark-') as images:
    with (output / 'samples.jsonl').open('w') as stream:
        for iteration in range(args.samples + args.warmups):
            for mode in (['separate', 'flow'] if iteration % 2 == 0 else ['flow', 'separate']):
                reset()
                record = run(mode, pathlib.Path(images) / f'{iteration}-{mode}.png')
                record.update({'round': iteration, 'warmup': iteration < args.warmups})
                records.append(record)
                stream.write(json.dumps(record) + '\n')
                stream.flush()
                print(json.dumps(record), flush=True)
summary = {}
for mode in ['separate', 'flow']:
    values = [r['elapsedMs'] for r in records if r['mode'] == mode and not r['warmup']]
    summary[mode] = {'samples': len(values), 'medianMs': statistics.median(values),
                     'meanMs': statistics.mean(values), 'minMs': min(values), 'maxMs': max(values)}
summary['medianSpeedup'] = summary['separate']['medianMs'] / summary['flow']['medianMs']
summary['medianReductionPercent'] = 100 * (1 - summary['flow']['medianMs'] / summary['separate']['medianMs'])
(output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
print(json.dumps(summary, indent=2))
