import unittest

from benchmarks import traces


class TraceIntervalsTest(unittest.TestCase):
    def test_overlapping_children_are_not_added_twice(self):
        self.assertTrue(hasattr(traces, 'union_duration'), 'interval union is missing')
        self.assertEqual(9, traces.union_duration([(1, 7), (5, 10)]))
        self.assertEqual(0, traces.union_duration([]))
        self.assertEqual(10, traces.union_duration([(0, 10), (2, 5), (10, 10)]))


def span(sequence, stage='invocation', start=0, duration=100, parent=None, identifier=None):
    return {'sessionId': 'a' * 32, 'invocationId': f'{sequence:032x}',
            'invocationSequence': sequence, 'spanId': identifier or f'{sequence + 100:032x}',
            'parentSpanId': parent, 'process': 'host', 'platform': 'android',
            'operation': 'info', 'stage': stage, 'startOffsetNs': start,
            'durationNs': duration, 'outcome': 'returned', 'operationOutcome': 'success'}


def records(count=1):
    return [{'type': 'session', 'schemaVersion': 1, 'sessionId': 'a' * 32,
             'mode': 'mcp', 'visionTestVersion': 'test', 'originNs': 0},
            *[span(index) for index in range(1, count + 1)],
            {'type': 'session.end', 'sessionId': 'a' * 32, 'written': count + 1,
             'dropped': 0, 'invocationsStarted': count, 'invocationsCompleted': count,
             'complete': True}]


def join(rows, calls=None):
    import json
    import tempfile
    from pathlib import Path
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'trace.jsonl'
        path.write_text(''.join(json.dumps(row) + '\n' for row in rows))
        return traces.join_trace(path, calls or [{'sample_id': 's1', 'operation': 'info', 'measured': True}])


class TraceJoinTest(unittest.TestCase):
    def test_finalized_dispatch_ledger_maps_verification_without_shifting_samples(self):
        self.assertTrue(hasattr(traces, 'join_trace'), 'trace joining is missing')
        result = join(records(3), [
            {'sample_id': 's1', 'operation': 'info', 'measured': True},
            {'sample_id': 's1', 'operation': 'info', 'measured': False},
            {'sample_id': 's2', 'operation': 'info', 'measured': True}])
        self.assertEqual(1, result['s1']['invocation_sequence'])
        self.assertEqual(3, result['s2']['invocation_sequence'])
        self.assertTrue(result['s1']['trace_complete'])

    def test_incomplete_or_ambiguous_sessions_never_expose_attribution(self):
        variants = []
        rows = records(); rows.pop(); variants.append(rows)
        rows = records(); rows[-1]['dropped'] = 1; variants.append(rows)
        rows = records(); rows[-1]['complete'] = False; variants.append(rows)
        rows = records(); rows[-1]['invocationsStarted'] = 2; variants.append(rows)
        rows = records(); rows[-1]['written'] = 10; variants.append(rows)
        variants.append(records(2))
        rows = records(); rows[1]['invocationSequence'] = 2; variants.append(rows)
        rows = records(); rows[1]['operation'] = 'other'; variants.append(rows)
        for rows in variants:
            with self.subTest(rows=rows):
                result = join(rows)['s1']
                self.assertFalse(result['trace_complete'])
                self.assertNotIn('invocation_id', result)
                self.assertTrue(result['measurement_gaps'])

    def test_host_contributors_exclude_preparation_and_use_child_union(self):
        rows = records()
        root_id = rows[1]['spanId']
        op = span(1, 'operation', 10, 50, root_id, 'b' * 32)
        rows[2:2] = [op, span(1, 'http.exchange', 15, 20, op['spanId'], 'c' * 32),
                     span(1, 'health', 25, 20, op['spanId'], 'd' * 32),
                     span(1, 'cli.prepare', 0, 9, root_id, 'e' * 32)]
        rows[-1]['written'] = len(rows) - 1
        result = join(rows)['s1']
        self.assertEqual(50, result.get('host_operation_duration_ns'))
        self.assertEqual(20, result['host_stages']['operation']['self_ns'])
        self.assertNotIn('cli.prepare', result['host_stages'])

    def test_native_metadata_joins_only_to_its_host_request_and_keeps_clocks_separate(self):
        rows = records()
        op = span(1, 'operation', 10, 50, rows[1]['spanId'], 'b' * 32)
        request = span(1, 'http.exchange', 15, 30, op['spanId'], 'c' * 32)
        native = {'type': 'native.timing', 'sessionId': 'a' * 32, 'invocationId': f'{1:032x}',
                  'invocationSequence': 1, 'requestSpanId': request['spanId'], 'platform': 'android',
                  'process': 'android', 'operation': 'info', 'clockDomain': 'native.request',
                  'status': 'available', 'missingStages': ['ui_sync'],
                  'spans': [{'spanId': 'f' * 32, 'parentSpanId': None, 'stage': 'request',
                             'startOffsetNs': 999, 'durationNs': 20, 'outcome': 'returned'}]}
        rows[2:2] = [op, request, native]; rows[-1]['written'] = len(rows) - 1
        result = join(rows)['s1']
        self.assertEqual(10, result.get('http_service_difference_estimate_ns'))
        self.assertEqual(20, result['native_stages']['request']['inclusive_ns'])
        self.assertIn('native_stage_missing:ui_sync', result['measurement_gaps'])
        self.assertEqual(20, result['host_stages']['operation']['self_ns'])

    def test_missing_metadata_and_unsupported_stages_are_gaps(self):
        rows = records()
        op = span(1, 'operation', 0, 100, rows[1]['spanId'], 'b' * 32)
        rows[2:2] = [op, span(1, 'http.exchange', 0, 50, op['spanId'], 'c' * 32),
                     span(1, 'future.stage', 0, 1, op['spanId'], 'd' * 32)]
        rows[-1]['written'] = len(rows) - 1
        result = join(rows)['s1']
        self.assertIn('native_metadata_missing', result['measurement_gaps'])
        self.assertIn('unsupported_host_stage:future.stage', result['measurement_gaps'])

    def test_malformed_span_correlation_or_intervals_invalidates_attribution(self):
        for field, value in [('invocationId', 'bad'), ('spanId', 'bad'),
                             ('invocationSequence', True), ('durationNs', -1),
                             ('durationNs', True), ('durationNs', 2**63),
                             ('parentSpanId', 'f' * 32), ('process', 'ios')]:
            with self.subTest(field=field, value=value):
                rows = records(); rows[1][field] = value
                result = join(rows)['s1']
                self.assertFalse(result['trace_complete'])
                self.assertNotIn('invocation_id', result)

    def test_native_wrong_invocation_or_orphan_request_is_never_silently_ignored(self):
        rows = records()
        rows.insert(2, {'type': 'native.timing', 'sessionId': 'a' * 32,
                        'invocationId': 'f' * 32, 'invocationSequence': 1,
                        'requestSpanId': 'd' * 32})
        rows[-1]['written'] = len(rows) - 1
        result = join(rows)['s1']
        self.assertFalse(result['trace_complete'])

    def test_corrupt_native_shapes_are_reported_without_crashing(self):
        rows = records()
        op = span(1, 'operation', 0, 100, rows[1]['spanId'], 'b' * 32)
        request = span(1, 'http.exchange', 0, 100, op['spanId'], 'c' * 32)
        rows[2:2] = [op, request]
        for malformed in ('bad', [1], [None], [{'durationNs': 2}]):
            with self.subTest(malformed=malformed):
                snapshot = {'type': 'native.timing', 'sessionId': 'a' * 32,
                            'invocationId': f'{1:032x}', 'invocationSequence': 1,
                            'requestSpanId': request['spanId'], 'status': 'available',
                            'spans': malformed, 'clockDomain': 'native.request', 'missingStages': []}
                own = rows[:-1] + [snapshot, dict(rows[-1], written=len(rows))]
                try:
                    result = join(own)['s1']
                except Exception as error:
                    self.fail(f'corrupt native shape escaped: {type(error).__name__}')
                self.assertFalse(result['trace_complete'])

    def test_dropped_events_are_an_explicit_gap(self):
        rows = records(); rows[-1]['dropped'] = 1
        self.assertIn('dropped_events', join(rows)['s1']['measurement_gaps'])
