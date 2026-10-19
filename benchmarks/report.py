from collections import defaultdict
from statistics import median

from benchmarks.results import _percentiles, summarize


def build_report(samples):
    result = summarize(samples)
    grouped = defaultdict(list)
    pairs = defaultdict(list)
    for sample in samples:
        key = tuple(sample.get(field) for field in ('case_id', 'mode', 'lifecycle'))
        grouped[(*key, sample.get('tracing'))].append(sample)
        if sample.get('pair_id') and not sample.get('warmup', False):
            pairs[key].append(sample)
    result['groups'] = [dict(zip(('case_id', 'mode', 'lifecycle', 'tracing'), key),
                             summary=summarize(values), contributors=_contributors(values)) for key, values in sorted(grouped.items())]
    result['overhead'] = [_paired(key, values) for key, values in sorted(pairs.items())]
    return result


def _paired(key, samples):
    pairs = defaultdict(list)
    for sample in samples:
        pairs[sample['pair_id']].append(sample)
    deltas = {'process': [], 'operation': []}
    percentages = {'process': [], 'operation': []}
    gaps = set()
    successful = 0
    for values in pairs.values():
        states = {sample['tracing']: sample for sample in values}
        if len(values) != 2 or set(states) != {'on', 'off'}:
            gaps.add('pair_incomplete')
            continue
        on, off = states['on'], states['off']
        if on.get('fixture_revision') != off.get('fixture_revision'):
            gaps.add('pair_fixture_mismatch')
            continue
        if on['status'] != 'passed' or off['status'] != 'passed':
            gaps.add('pair_unsuccessful')
            continue
        successful += 1
        for boundary in deltas:
            field = boundary + '_duration_ns'
            if on.get(field) is None or off.get(field) is None:
                gaps.add(boundary + '_boundary_unavailable')
                continue
            delta = on[field] - off[field]
            deltas[boundary].append(delta)
            if off[field] > 0:
                percentages[boundary].append(100 * delta / off[field])
            else:
                gaps.add(boundary + '_zero_reference')
    return dict(zip(('case_id', 'mode', 'lifecycle'), key),
                attempted_pairs=len(pairs), successful_pairs=successful,
                measurement_gaps=sorted(gaps), **{
                    boundary: {'count': len(values), 'median_delta_ns': median(values) if values else None,
                               'median_percent': median(percentages[boundary]) if percentages[boundary] else None}
                    for boundary, values in deltas.items()})


def _contributors(samples):
    result = {}
    for domain in ('host', 'native'):
        stages = defaultdict(lambda: {'inclusive': [], 'self': []})
        for sample in samples:
            if sample.get('warmup', False):
                continue
            for stage, totals in sample.get(domain + '_stages', {}).items():
                stages[stage]['inclusive'].append(totals['inclusive_ns'])
                stages[stage]['self'].append(totals['self_ns'])
        result[domain] = {stage: {field: _percentiles(values) for field, values in populations.items()}
                          for stage, populations in sorted(stages.items())}
    return result
