"""Render raw benchmark evidence as Markdown without contacting devices."""
import argparse
from collections import Counter
import html
import json
import os
from pathlib import Path
import re
import sys
from urllib.parse import quote

from benchmarks.report import build_report


def _escape(value):
    if isinstance(value, (dict, list)):
        value = json.dumps(value, sort_keys=True, ensure_ascii=False)
    value = html.escape(str(value), quote=False).replace('\n', ' ').replace('\r', ' ')
    return re.sub(r'([\\`*_{}\[\]()#+!|>~])', r'\\\1', value)


def _code(value):
    return '`' + html.escape(str(value), quote=False).replace('`', '&#96;').replace('\n', ' ') + '`'


def _number(value):
    if value is None:
        return 'unavailable'
    raw = str(int(value)) if value == int(value) else str(value)
    return f'{value / 1_000_000:.3f} ms ({raw} ns)' if abs(value) >= 1_000_000 else f'{raw} ns'


def _population(label, population):
    return f"| {label} | {population['count']} | {_number(population['median_ns'])} | {_number(population['p95_ns'])} |"


def _samples(path):
    samples, gaps = [], []
    if not path.is_file():
        return samples, ['samples.jsonl missing']
    # A terminated JSONL record survives even if the next write was interrupted.
    for index, line in enumerate(path.read_bytes().splitlines(), 1):
        if not line.strip():
            continue
        try:
            value = json.loads(line)
        except (ValueError, UnicodeError):
            gaps.append(f'incomplete_jsonl_line:{index}')
            continue
        try:
            if not isinstance(value, dict):
                raise ValueError('sample must be an object')
            if any(not isinstance(value.get(field), str) for field in ('case_id', 'mode', 'lifecycle', 'tracing')):
                raise ValueError('sample identity fields must be strings')
            # Validate independently so one bad record cannot erase good attempts.
            build_report([value])
        except (ValueError, TypeError, KeyError, AttributeError):
            gaps.append(f'invalid_sample_line:{index}')
            continue
        samples.append(value)
    return samples, gaps


def _identity(environment):
    metadata = environment.get('environment', {})
    build = metadata.get('build', {}) if isinstance(metadata, dict) else {}
    build = build.get('value', {}) if isinstance(build, dict) else {}
    if not isinstance(build, dict):
        build = {}
    sha = build.get('sourceSHA') or build.get('commit')
    return sha if isinstance(sha, str) and sha else 'unavailable'


def _expected(environment, samples):
    gaps = []
    cases = environment.get('selected_case_ids')
    states = ['off', 'on'] if environment.get('tracing') == 'paired' else [environment.get('tracing')]
    counts = [environment.get('requested_samples'), environment.get('requested_warmups')]
    if (not isinstance(cases, list) or not cases or any(not isinstance(case, str) for case in cases)
            or len(set(cases)) != len(cases) or states[0] not in ('on', 'off')
            or any(type(count) is not int or count < 0 for count in counts)):
        return None, None, ['expected_attempt_counts_unavailable']
    actual = Counter((s.get('case_id'), s.get('tracing'), s.get('warmup', False)) for s in samples)
    for case in cases:
        for state in states:
            for warmup, requested in ((False, counts[0]), (True, counts[1])):
                count = actual.pop((case, state, warmup), 0)
                if count != requested:
                    kind = 'missing' if count < requested else 'unexpected'
                    population = 'warmup' if warmup else 'measured'
                    gaps.append(f'{kind}_attempts:{case}/{state}/{population}:{abs(count - requested)}')
    for (case, state, warmup), count in actual.items():
        gaps.append(f'unexpected_attempts:{case}/{state}/{"warmup" if warmup else "measured"}:{count}')
    for sample in samples:
        for field in ('run_id', 'mode', 'lifecycle', 'platform'):
            if field in sample and field in environment and sample[field] != environment[field]:
                gaps.append(f'sample_{field}_mismatch')
    multiplier = len(cases) * len(states)
    return counts[0] * multiplier, counts[1] * multiplier, sorted(set(gaps))


def _counts(label, population, expected):
    expected = 'unavailable' if expected is None else expected
    return (f"{label} attempts: {population['attempts']} / {expected} expected; "
            f"passed: {population['passed']}; failed: {population['failed']}; "
            f"setup-failed: {population['setup_failed']}; timeout: {population['timeout']}.")


def _table(population):
    return ['| Population | Timed count | Median | p95 |', '| --- | --- | --- | --- |',
            _population('all timed', population['all_timed']),
            _population('successful only', population['successful']),
            _population('process boundary', population['process_timed']),
            _population('operation boundary', population['operation_timed']), '']


def _link(path, base):
    return f'[{_escape(path.name)}]({quote(Path(os.path.relpath(path, base)).as_posix(), safe="/")})'


def _metadata(lines, label, metadata):
    lines.extend([label, '', '| Field | Recorded value |', '| --- | --- |'])
    for key, value in sorted(metadata.items()):
        lines.append(f'| {_escape(key)} | {_escape(value)} |')
    lines.append('')


def _render_run(path, base):
    lines = [f'## Run: {_escape(path.parent.relative_to(base) if path.parent.is_relative_to(base) else path.parent.name)}', '']
    gaps = []
    try:
        environment = json.loads(path.read_text(encoding='utf-8'))
        if not isinstance(environment, dict):
            raise ValueError('environment must be an object')
    except (ValueError, UnicodeError):
        return lines + ['Incomplete: environment.json is invalid.', '', _link(path, base), ''], None, None, False
    samples, read_gaps = _samples(path.with_name('samples.jsonl'))
    gaps.extend(read_gaps)
    expected, expected_warmups, count_gaps = _expected(environment, samples)
    gaps.extend(count_gaps)
    result = build_report(samples)
    sha = _identity(environment)
    if sha == 'unavailable':
        gaps.append('tested_sha_unavailable')
    config = tuple(environment.get(key) if isinstance(environment.get(key), str) else 'unavailable'
                   for key in ('platform', 'mode', 'lifecycle'))
    lines.extend([f"Platform: {_escape(config[0])}; mode: {_escape(config[1])}; lifecycle: {_escape(config[2])}", '',
                  f'Tested SHA: {_escape(sha)}', '',
                  'The tested SHA is build.sourceSHA (CI) or build.commit (archived runtime); runnerCommit is separate provenance.', ''])
    _metadata(lines, 'Run metadata', {key: value for key, value in environment.items() if key != 'environment'})
    metadata = environment.get('environment', {})
    if not isinstance(metadata, dict):
        gaps.append('environment metadata is invalid')
        metadata = {'invalid value': metadata}
    _metadata(lines, 'Environment and build provenance', metadata)
    provenance = path.with_name('provenance.json')
    if provenance.is_file():
        try:
            value = json.loads(provenance.read_text(encoding='utf-8'))
            _metadata(lines, 'Archived provenance', value if isinstance(value, dict) else {'value': value})
            if isinstance(value, dict):
                if value.get('complete_configuration') is False:
                    gaps.append('provenance_configuration_incomplete')
                if value.get('interruption_gap'):
                    gaps.append('provenance_interruption:' + str(value['interruption_gap']))
        except (ValueError, UnicodeError):
            gaps.append('provenance_invalid')
    if not samples:
        gaps.append('No produced results: setup did not reach the runner; no latency evidence is available')
    failed = any(result.get(status, 0) for status in ('failed', 'setup_failed', 'timeout'))
    # Missing/invalid records and inconsistent provenance affect collection.
    # Structural boundary limitations and trace attribution have separate status.
    collection_gaps = list(gaps)
    traces = result['trace_completeness']
    if traces['incomplete']:
        gaps.append('incomplete_traces')
    for sample in samples:
        if sample.get('tracing') == 'on' and sample.get('trace_complete') is not True:
            gaps.append('traced_sample_without_complete_trace')
    gaps.extend(result['measurement_gaps'])
    for overhead in result['overhead']:
        gaps.extend(overhead['measurement_gaps'])
        collection_gaps.extend(gap for gap in overhead['measurement_gaps']
                               if gap in ('pair_incomplete', 'pair_fixture_mismatch'))
    complete = bool(samples) and not collection_gaps and not failed
    traced = [sample for sample in samples if sample.get('tracing') == 'on']
    attribution = ('incomplete' if any(sample.get('trace_complete') is not True for sample in traced)
                   else 'complete' if traced else 'unavailable (tracing disabled)')
    lines.extend(['Collection status: ' + ('Requested attempts recorded and passed.' if complete else 'Incomplete; inspect counts and gaps below.'), '',
                  f'Attribution status: {attribution}.', '',
                  'Measurement status: ' + ('gaps reported; see Gaps below.' if gaps else 'no reported gaps.'), '',
                  _counts('Measured', result['measured'], expected), '',
                  _counts('Warmup', result['warmups'], expected_warmups), '',
                  'Latency populations are reported per case and tracing state below; failed and timed-out attempts remain in all timed populations.', ''])
    lines.extend(['Setup timing (separate from operation)', '',
                  '| Population | Timed count | Median | p95 |', '| --- | --- | --- | --- |',
                  _population('setup, including warmups', result['setup_timed']), '',
                  f"Trace completeness: complete: {traces['complete']}; incomplete: {traces['incomplete']}; unavailable: {traces['unavailable']}.", ''])
    for group in result['groups']:
        lines.extend([f"### Case: {_escape(group['case_id'])}; mode: {_escape(group['mode'])}; lifecycle: {_escape(group['lifecycle'])}; tracing: {_escape(group['tracing'])}", '',
                      _counts('Measured', group['summary']['measured'], environment.get('requested_samples') if expected is not None else None), '',
                      _counts('Warmup', group['summary']['warmups'], environment.get('requested_warmups') if expected_warmups is not None else None), ''])
        lines.extend(['Measured samples', ''])
        lines.extend(_table(group['summary']['measured']))
        lines.extend(['Warmups (excluded from measured latency and contributors)', ''])
        lines.extend(_table(group['summary']['warmups']))
        lines.extend(['Measured host/native contributors (available spans; failed attempts included)', '',
                      '| Domain | Stage | Count | Inclusive median | Inclusive p95 | Self median | Self p95 |',
                      '| --- | --- | --- | --- | --- | --- | --- |'])
        for domain, stages in group['contributors'].items():
            if not stages:
                lines.append(f'| {domain} | unavailable | 0 | unavailable | unavailable | unavailable | unavailable |')
            for stage, totals in stages.items():
                inclusive, own = totals['inclusive'], totals['self']
                lines.append(f"| {domain} | {_escape(stage)} | {inclusive['count']} | {_number(inclusive['median_ns'])} | {_number(inclusive['p95_ns'])} | {_number(own['median_ns'])} | {_number(own['p95_ns'])} |")
        lines.append('')
    lines.extend(['### Paired tracing overhead', '', 'Signed on-minus-off differences from matching successful measured pairs only.', ''])
    if not result['overhead']:
        lines.extend(['Unavailable: no measured pairs were produced.', ''])
    for overhead in result['overhead']:
        lines.extend([f"Case: {_escape(overhead['case_id'])}; mode: {_escape(overhead['mode'])}; lifecycle: {_escape(overhead['lifecycle'])}; attempted pairs: {overhead['attempted_pairs']}; successful pairs: {overhead['successful_pairs']}", '',
                      '| Boundary | Pair count | Median delta | Median per-pair percent |', '| --- | --- | --- | --- |'])
        for boundary in ('process', 'operation'):
            value = overhead[boundary]
            percent = 'unavailable' if value['median_percent'] is None else f"{value['median_percent']:g}%"
            lines.append(f"| {boundary} | {value['count']} | {_number(value['median_delta_ns'])} | {percent} |")
        lines.append('')
    lines.extend(['### Gaps', ''])
    lines.extend(['- ' + _code(gap) for gap in sorted(set(gaps))] or ['No reported measurement gaps.'])
    lines.extend(['', '### Artifacts', '', 'Raw samples are authoritative; summary.json may be stale after interruption.', ''])
    for artifact in sorted(path.parent.iterdir()):
        if artifact.is_file() or artifact.name in ('artifacts', 'traces'):
            lines.append('- ' + _link(artifact, base))
    lines.append('')
    return lines, config if samples else None, sha, complete


def _render_report(input_dir, expected_configurations, link_base):
    if type(expected_configurations) is not int or expected_configurations < 1:
        raise ValueError('expected configurations must be positive')
    lines = ['# Command benchmark report', '',
             'CLI process lifetime includes startup, execution and teardown. MCP tools/call round trip excludes initialization and verification; warm MCP has no per-operation process lifetime. Host-cold MCP process timing includes initialization and discovery. CLI operation boundaries are unavailable; traced host registrar spans are separate contributors.', '',
             'No cross-mode speedup or agent token estimates are inferred. Native-cold is excluded from the CI matrix. Counts describe collected evidence; this report does not certify a complete baseline.', '']
    runs, configurations, by_sha, complete_runs, run_count = [], set(), {}, 0, 0
    for path in sorted(input_dir.rglob('environment.json')):
        run_lines, config, sha, complete = _render_run(path, link_base)
        runs.extend(run_lines)
        if config is not None:
            configurations.add(config)
            by_sha.setdefault(sha, set()).add(config)
        complete_runs += int(complete)
        run_count += 1
    lines.extend([f'Configurations: {len(configurations)} / {expected_configurations} expected (unique platform/mode/lifecycle).', '',
                  f'Missing configurations: {max(0, expected_configurations - len(configurations))}', '',
                  f'Runs with requested attempts recorded and passed: {complete_runs}.', ''])
    if expected_configurations == 8:
        matrix = {(platform, mode, lifecycle) for platform in ('android', 'ios')
                  for mode in ('cli', 'mcp') for lifecycle in ('warm', 'host-cold')}
        for config in sorted(matrix - configurations):
            lines.extend(['Missing configuration: ' + ' / '.join(config), ''])
    if not configurations:
        lines.extend(['No produced results: setup did not reach the runner; no latency evidence is available.', ''])
    if len(by_sha) > 1:
        lines.extend(['Multiple tested SHAs: configurations across these SHAs cannot establish one complete collection.', ''])
    for sha, configs in sorted(by_sha.items()):
        lines.extend([f'Tested SHA {_escape(sha)}: {len(configs)} / {expected_configurations} configurations.', ''])
    if len(configurations) < expected_configurations or complete_runs != run_count or not run_count or len(by_sha) > 1:
        lines.extend(['Incomplete collection.', ''])
    lines.extend(runs)
    return '\n'.join(lines).rstrip() + '\n'


def render_report(input_dir: Path, expected_configurations: int = 1) -> str:
    """Return Markdown with artifact links relative to input_dir; never pool runs."""
    root = Path(input_dir).resolve()
    return _render_report(root, expected_configurations, root)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input-dir', required=True, type=Path)
    parser.add_argument('--output', required=True, type=Path)
    parser.add_argument('--expected-configurations', type=int, default=1)
    try:
        args = parser.parse_args(argv)
    except SystemExit as error:
        return int(error.code)
    try:
        report = _render_report(args.input_dir.resolve(), args.expected_configurations, args.output.parent.resolve())
        args.output.parent.mkdir(parents=True, exist_ok=True)
        args.output.write_text(report, encoding='utf-8')
        print(report, end='')
        return 0
    except (ValueError, OSError) as error:
        print(f'benchmark report failed: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
