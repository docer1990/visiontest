import json
import re
from pathlib import Path


class TraceValidationError(ValueError):
    pass


def union_duration(intervals: list[tuple[int, int]]) -> int:
    total = 0
    end = None
    for start, stop in sorted(intervals):
        if end is None or start > end:
            total += stop - start
        else:
            total += max(0, stop - end)
        end = max(end, stop) if end is not None else stop
    return total


def join_trace(path, calls):
    try:
        rows = [json.loads(line) for line in Path(path).read_text(encoding='utf-8').splitlines()]
        if any(not isinstance(row, dict) for row in rows):
            raise TraceValidationError('invalid_trace')
        if len(rows) < 2 or rows[0].get('type') != 'session' or rows[-1].get('type') != 'session.end':
            raise TraceValidationError('incomplete_session')
        header, footer = rows[0], rows[-1]
        if footer.get('dropped', 0) != 0:
            raise TraceValidationError('dropped_events')
        if (header.get('schemaVersion') != 1 or footer.get('complete') is not True
                or footer.get('dropped') != 0 or footer.get('written') != len(rows) - 1
                or footer.get('invocationsStarted') != len(calls)
                or footer.get('invocationsCompleted') != len(calls)):
            raise TraceValidationError('incomplete_session')
        if any(row.get('sessionId') != header['sessionId'] for row in rows):
            raise TraceValidationError('session_mismatch')
        invocations = sorted((row for row in rows if row.get('stage') == 'invocation'),
                             key=lambda row: row['invocationSequence'])
        if (len(invocations) != len(calls)
                or [row['invocationSequence'] for row in invocations] != list(range(1, len(calls) + 1))
                or any(row.get('operation') != call['operation'] for call, row in zip(calls, invocations))):
            raise TraceValidationError('invocation_attribution_invalid')
        _validate_records(rows, invocations)
        return {call['sample_id']: {
            'session_id': row['sessionId'], 'invocation_id': row['invocationId'],
            'invocation_sequence': row['invocationSequence'],
            'trace_complete': True, **_contributors(rows, row),
        } for call, row in zip(calls, invocations) if call['measured']}
    except (OSError, UnicodeError, json.JSONDecodeError, KeyError, TypeError, ValueError) as error:
        gap = str(error) if isinstance(error, TraceValidationError) else 'invalid_trace'
        return {call['sample_id']: {'trace_complete': False, 'measurement_gaps': [gap]}
                for call in calls if call['measured']}


HOST_STAGES = set('invocation cli.prepare component.init operation health request.prepare http.exchange response.process poll poll.wait screenshot.parse screenshot.decode screenshot.write device.discovery adb simctl process.launch'.split())
NATIVE_STAGES = set('request decode queue_wait operation ui_sync poll poll_wait screenshot_capture png_encode base64_encode response_encode screenshot_capture_encode'.split())


def _stage_totals(spans):
    totals = {}
    for span in spans:
        start, duration = span['startOffsetNs'], span['durationNs']
        children = [(max(start, child['startOffsetNs']),
                     min(start + duration, child['startOffsetNs'] + child['durationNs']))
                    for child in spans if child.get('parentSpanId') == span['spanId']
                    and child['startOffsetNs'] < start + duration
                    and child['startOffsetNs'] + child['durationNs'] > start]
        total = totals.setdefault(span['stage'], {'count': 0, 'inclusive_ns': 0, 'self_ns': 0})
        total['count'] += 1
        total['inclusive_ns'] += duration
        total['self_ns'] += duration - union_duration(children)
    return totals


def _contributors(rows, invocation):
    own = [row for row in rows if row.get('invocationId') == invocation['invocationId']]
    host = [row for row in own if row.get('process') == 'host']
    gaps = []
    for row in host:
        if row.get('stage') not in HOST_STAGES:
            gaps.append('unsupported_host_stage:' + str(row.get('stage')))
    selected_ids = {row['spanId'] for row in host if row.get('stage') == 'operation'}
    while True:
        previous = len(selected_ids)
        selected_ids.update(row['spanId'] for row in host if row.get('parentSpanId') in selected_ids)
        if len(selected_ids) == previous:
            break
    operation_spans = [row for row in host if row['spanId'] in selected_ids and row['stage'] in HOST_STAGES]
    if not selected_ids:
        gaps.append('host_operation_missing')
    native_totals = {}
    estimates = []
    requests = [row for row in operation_spans if row['stage'] == 'http.exchange']
    for request in requests:
        metadata = [row for row in own if row.get('type') == 'native.timing'
                    and row.get('requestSpanId') == request['spanId']]
        if len(metadata) != 1:
            gaps.append('native_metadata_missing' if not metadata else 'native_metadata_ambiguous')
            continue
        snapshot = metadata[0]
        if snapshot.get('status') != 'available':
            gaps.append('native_metadata_' + str(snapshot.get('status')))
            continue
        if snapshot.get('clockDomain') != 'native.request':
            gaps.append('native_clock_invalid')
            continue
        spans = snapshot.get('spans', [])
        for stage in snapshot.get('missingStages', []):
            gaps.append('native_stage_missing:' + stage)
        for row in spans:
            if row.get('stage') not in NATIVE_STAGES:
                gaps.append('unsupported_native_stage:' + str(row.get('stage')))
        known = [row for row in spans if row.get('stage') in NATIVE_STAGES]
        for stage, total in _stage_totals(known).items():
            aggregate = native_totals.setdefault(stage, {'count': 0, 'inclusive_ns': 0, 'self_ns': 0})
            for key, value in total.items():
                aggregate[key] += value
        roots = [row for row in known if row['stage'] == 'request' and row.get('parentSpanId') is None]
        if len(roots) == 1:
            estimates.append(request['durationNs'] - roots[0]['durationNs'])
        else:
            gaps.append('native_request_service_missing')
    host_totals = _stage_totals(operation_spans)
    return {'host_operation_duration_ns': host_totals.get('operation', {}).get('inclusive_ns'),
            'host_stages': host_totals, 'native_stages': native_totals,
            'http_service_difference_estimate_ns': sum(estimates) if estimates else None,
            'measurement_gaps': sorted(set(gaps))}


def _integer(value):
    return type(value) is int and 0 <= value <= 2**63 - 1


def _identifier(value):
    return isinstance(value, str) and re.fullmatch('[0-9a-f]{32}', value) is not None


def _validate_spans(spans, native=False):
    if not isinstance(spans, list) or any(not isinstance(span, dict) for span in spans):
        raise TraceValidationError('invalid_spans')
    indexed = {}
    for span in spans:
        if (not _identifier(span.get('spanId')) or span['spanId'] in indexed
                or not _integer(span.get('startOffsetNs')) or not _integer(span.get('durationNs'))
                or span['startOffsetNs'] + span['durationNs'] > 2**63 - 1
                or span.get('outcome') not in {'returned', 'thrown', 'timeout', 'cancelled'}):
            raise TraceValidationError('invalid_span')
        indexed[span['spanId']] = span
    for span in spans:
        current = span
        seen = {span['spanId']}
        while current.get('parentSpanId') is not None:
            parent = current['parentSpanId']
            if parent not in indexed or parent in seen:
                raise TraceValidationError('invalid_span_parent')
            seen.add(parent)
            ancestor = indexed[parent]
            if native and (current['startOffsetNs'] < ancestor['startOffsetNs']
                           or current['startOffsetNs'] + current['durationNs'] > ancestor['startOffsetNs'] + ancestor['durationNs']):
                raise TraceValidationError('invalid_native_interval')
            current = ancestor


def _validate_records(rows, invocations):
    if not _identifier(rows[0].get('sessionId')):
        raise TraceValidationError('invalid_session_id')
    roots = {row['invocationId']: row for row in invocations}
    if len(roots) != len(invocations):
        raise TraceValidationError('duplicate_invocation_id')
    for row in rows[1:-1]:
        if row.get('type') not in (None, 'native.timing'):
            raise TraceValidationError('unsupported_record_type')
        root = roots.get(row.get('invocationId'))
        if (root is None or not _identifier(row.get('invocationId'))
                or not _integer(row.get('invocationSequence'))
                or row['invocationSequence'] != root['invocationSequence']):
            raise TraceValidationError('invalid_span_correlation')
    for root in invocations:
        own = [row for row in rows if row.get('invocationId') == root['invocationId']]
        host = [row for row in own if row.get('type') is None]
        if any(row.get('process') != 'host' for row in host):
            raise TraceValidationError('invalid_host_process')
        _validate_spans(host)
        requests = {row['spanId'] for row in host if row['stage'] == 'http.exchange'}
        for row in own:
            if row.get('type') != 'native.timing':
                continue
            if row.get('requestSpanId') not in requests:
                raise TraceValidationError('orphan_native_request')
            if row.get('status') == 'available':
                _validate_spans(row.get('spans', []), native=True)
