"""Paired standalone/daemon measurements; no device setup or speed threshold."""
import argparse
import hashlib
import json
import math
import os
import platform
import statistics
import tempfile
import time
from pathlib import Path

from benchmarks.execution import run_process
from benchmarks.fixtures import validate_target, wait_fixture_ready


def summarize_durations(values: list[float]) -> dict[str, float]:
    if not values or any(not math.isfinite(v) or v < 0 for v in values):
        raise ValueError('durations must be nonempty, finite and nonnegative')
    ordered = sorted(values)
    return {'median': float(statistics.median(ordered)),
            'p95': float(ordered[math.ceil(len(ordered) * .95) - 1])}


def trace_counts(rows):
    by_id = {r['spanId']: r for r in rows if 'spanId' in r}
    def outside_operation(row):
        seen = set()
        while row.get('parentSpanId') in by_id:
            parent = row['parentSpanId']
            if parent in seen:
                return False
            seen.add(parent)
            row = by_id[parent]
            if row.get('stage') == 'operation':
                return False
        return True
    admission = [r for r in rows if r.get('stage') in {'adb', 'simctl'} and outside_operation(r)]
    return {'native_http_count': sum(r.get('stage') == 'http.exchange' for r in rows),
            'health_count': sum(r.get('stage') == 'health' for r in rows),
            'admission_count': len(admission),
            'admission_ns': sum(r.get('durationNs', 0) for r in admission),
            'adb_count': sum(r.get('stage') == 'adb' for r in rows),
            'simctl_count': sum(r.get('stage') == 'simctl' for r in rows)}


def measure(prefix, command, *, mode, phase, timeout_ms, env, trace_path=None):
    argv = list(prefix) + (['--standalone'] if mode == 'standalone' else [])
    if trace_path is not None:
        argv += ['--trace-performance', str(trace_path)]
    started_at_ns = time.time_ns()
    result = run_process(argv + command, timeout_ms, env)
    try:
        payload = json.loads(result['stdout'])
        operation_failed = (not isinstance(payload, dict) or payload.get('success') is False or
                            payload.get('error') is not None)
    except (TypeError, json.JSONDecodeError):
        operation_failed = True
    trace = []
    if trace_path is not None and Path(trace_path).exists():
        trace = [json.loads(line) for line in Path(trace_path).read_text().splitlines()]
    complete = bool(trace and trace[0].get('type') == 'session' and
                    trace[-1].get('type') == 'session.end' and trace[-1].get('complete') is True and
                    trace[-1].get('dropped') == 0 and
                    trace[-1].get('invocationsStarted') == trace[-1].get('invocationsCompleted'))
    counts = trace_counts(trace) if complete else {key: None for key in trace_counts([])}
    stages = {'host_invocation_ns': 'invocation', 'component_init_ns': 'component.init',
              'cli_prepare_ns': 'cli.prepare'}
    timing = {key: sum(r.get('durationNs', 0) for r in trace if r.get('stage') == stage)
              if complete else None for key, stage in stages.items()}
    return {**result, 'started_at_ns': started_at_ns, 'duration_ms': result['duration_ns'] / 1e6, 'mode': mode, 'phase': phase,
            'command': command, 'uncertain': 'uncertain' in result['stderr'].lower(),
            'operation_failed': operation_failed,
            'trace_complete': complete, **counts, **timing}


def rss_kib(pid):
    if pid is None:
        return None
    result = run_process(['/bin/ps', '-o', 'rss=', '-p', str(pid)], 2000)
    try:
        return int(result['stdout'].strip())
    except ValueError:
        return None


def prepare_fixture(prefix, *, platform_name, target, timeout_ms, env):
    """Reset only the installed synthetic fixture, after single-target validation."""
    started = time.perf_counter_ns()
    processes = []
    def observe(argv, timeout_ms=timeout_ms, env=env):
        result = run_process(argv, timeout_ms, env)
        processes.append({'argv': argv, **result})
        return result
    def require(argv, allowed=(0,)):
        result = observe(argv)
        if result['timed_out'] or result['exit_code'] not in allowed:
            raise ValueError(f"fixture setup failed (exit {result['exit_code']}): {result['stderr']}")
        return result
    error = None
    try:
        identity = 'dev.visiontest.benchmark'
        if platform_name == 'android':
            inventory = require(['adb', 'devices'])
            available = [line.split()[0] for line in inventory['stdout'].splitlines()[1:]
                         if len(line.split()) >= 2 and line.split()[1] == 'device']
        else:
            inventory = require(['xcrun', 'simctl', 'list', 'devices', 'booted', '-j'])
            data = json.loads(inventory['stdout'])
            available = [device['udid'] for devices in data.get('devices', {}).values()
                         for device in devices if device.get('state') == 'Booted']
        validate_target(platform_name, target, available)
        if platform_name == 'android':
            installed = require(['adb', '-s', target, 'shell', 'pm', 'path', identity])
            if not installed['stdout'].strip().startswith('package:'):
                raise ValueError('synthetic fixture is not installed on the selected target')
            require(['adb', '-s', target, 'shell', 'am', 'force-stop', identity])
            require(['adb', '-s', target, 'shell', 'am', 'start', '-n', identity + '/.MainActivity',
                     '--es', 'scenario', 'navigation', '--es', 'language', 'en'])
        else:
            installed = require(['xcrun', 'simctl', 'get_app_container', target, identity, 'app'])
            if not installed['stdout'].strip():
                raise ValueError('synthetic fixture is not installed on the selected target')
            require(['xcrun', 'simctl', 'terminate', target, identity], allowed=(0, 3))
            require(['xcrun', 'simctl', 'launch', target, identity, 'navigation', 'en'])
        ready, error = wait_fixture_ready({'fixtureScenario': 'navigation', 'timeoutMs': timeout_ms},
            {'platform': platform_name, 'fixtureIdentity': identity, 'cliPrefix': prefix + ['--standalone']},
            env, runner=observe)
        if not ready and error is None:
            error = 'fixture readiness failed'
    except (ValueError, KeyError, json.JSONDecodeError) as failure:
        error = str(failure)
    return {'setup_duration_ns': time.perf_counter_ns() - started, 'setup_error': error,
            'setup_processes': processes, 'setup_native_counts': 'not_traced'}


def collect(prefix, *, platform_name, target, output, repetitions, timeout_ms, env,
            execute=measure, prepare=prepare_fixture):
    """Fresh client process per sample; lifecycle calls are outside operation samples."""
    output.mkdir(parents=True, exist_ok=False)
    samples = []
    def control(*args):
        return run_process(prefix + ['daemon', *args], timeout_ms, env)
    def status():
        result = control('status', '--json')
        return json.loads(result['stdout']) if result['exit_code'] == 0 else {}
    selector = 'dev.visiontest.benchmark:id/start' if platform_name == 'android' else 'start'
    wait_step = f'wait_for_element --resource-id {selector} --timeout 5000'
    if platform_name == 'ios':
        wait_step += ' --bundle-id dev.visiontest.benchmark'
    commands = {'command': ['get_device_info', '-p', platform_name, '--json'],
                'flow': ['flow', '-p', platform_name, '--step', wait_step, '--step', wait_step, '--json']}
    with (output / 'samples.jsonl').open('w') as stream:
        def record(row):
            samples.append(row)
            stream.write(json.dumps(row, sort_keys=True) + '\n'); stream.flush()
        try:
            for name, command in commands.items():
                for mode in ['standalone', 'daemon']:
                    control('stop')
                    # Stop acknowledges before cleanup; wait for metadata removal without starting work.
                    deadline = time.monotonic() + 35
                    while status().get('running') and time.monotonic() < deadline:
                        time.sleep(.05)
                    if mode == 'daemon':
                        started = time.perf_counter_ns()
                        result = control('start')
                        record({**result, 'duration_ms': (time.perf_counter_ns() - started) / 1e6,
                                'mode': mode, 'phase': 'cold_host', 'command': ['daemon', 'start'],
                                'case': name, 'uncertain': False, 'rss_kib': rss_kib(status().get('pid'))})
                    for index in range(repetitions + 1):
                        trace = output / f'{name}-{mode}-{index}.trace.jsonl'
                        phase = 'first_use' if index == 0 else 'subsequent'
                        setup = prepare(prefix, platform_name=platform_name, target=target, timeout_ms=timeout_ms, env=env)
                        if setup['setup_error']:
                            row = {'duration_ns': None, 'duration_ms': None, 'exit_code': None, 'stdout': '',
                                   'stderr': '', 'timed_out': False, 'mode': mode, 'phase': phase,
                                   'command': command, 'uncertain': False, 'trace_complete': False}
                        else:
                            row = execute(prefix, command, mode=mode, phase=phase,
                                          timeout_ms=timeout_ms, env=env, trace_path=trace)
                        row.update(case=name, iteration=index, rss_kib=rss_kib(status().get('pid')), **setup)
                        record(row)
                    if mode == 'daemon':
                        time.sleep(1)
                        record({'mode': mode, 'phase': 'idle_rss', 'case': name,
                                'rss_kib': rss_kib(status().get('pid')), 'command': [], 'uncertain': False})
        finally:
            control('stop')
    summary = {}
    for name in commands:
        for mode in ['standalone', 'daemon']:
            group = [r for r in samples if r['case'] == name and r['mode'] == mode and r['phase'] == 'subsequent']
            good = [r['duration_ms'] for r in group if r['exit_code'] == 0 and not r['timed_out'] and not r.get('operation_failed', False)]
            summary[f'{name}/{mode}'] = {**({key + '_ms': value for key, value in summarize_durations(good).items()} if good else {}),
                'samples': len(group),
                'failures': sum(r['exit_code'] != 0 or r.get('operation_failed', False) for r in group),
                'setup_failures': sum(bool(r.get('setup_error')) for r in group),
                'timeouts': sum(r['timed_out'] for r in group), 'uncertain': sum(r['uncertain'] for r in group),
                'rss_kib': [r['rss_kib'] for r in group]}
    (output / 'summary.json').write_text(json.dumps(summary, indent=2) + '\n')
    return summary


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--platform', choices=['android', 'ios'], required=True)
    parser.add_argument('--target', required=True, help='Sole Android serial or booted iOS simulator UDID')
    parser.add_argument('--jar', type=Path, default=Path('app/build/libs/visiontest.jar'))
    parser.add_argument('--java', default='java')
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--repetitions', type=int, default=10)
    parser.add_argument('--timeout-ms', type=int, default=60000)
    args = parser.parse_args()
    if args.repetitions < 1 or args.timeout_ms < 1:
        parser.error('repetitions and timeout must be positive')
    jar = args.jar.resolve()
    with tempfile.TemporaryDirectory(prefix='vtd-', dir='/tmp') as runtime:
        env = {**os.environ, 'VISIONTEST_DAEMON_DIR': runtime}
        summary = collect([args.java, '-jar', str(jar)], platform_name=args.platform,
                          target=args.target, output=args.output, repetitions=args.repetitions, timeout_ms=args.timeout_ms, env=env)
    provenance = {'host': platform.platform(), 'python': platform.python_version(),
                  'java': run_process([args.java, '-version'], 5000)['stderr'], 'platform': args.platform,
                  'target': args.target,
                  'jar_sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                  'git_commit': run_process(['git', 'rev-parse', 'HEAD'], 2000)['stdout'].strip(),
                  'worktree': run_process(['git', 'status', '--porcelain'], 2000)['stdout'],
                  'limitations': ['Synthetic fixture reset/readiness precedes every timed sample; setup native calls are untraced.',
                                   'RSS is host resident set after completion, not peak client RSS.',
                                   'Native HTTP and health spans are reported without subtracting admission costs.',
                                   'Short local pilot; no hardware-independent speed threshold.']}
    (args.output / 'environment.json').write_text(json.dumps(provenance, indent=2) + '\n')
    print(json.dumps(summary, indent=2))


if __name__ == '__main__':
    main()
