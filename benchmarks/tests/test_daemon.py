import unittest
import json
import tempfile
from pathlib import Path
from unittest.mock import patch

from benchmarks import daemon


class DaemonBenchmarkTest(unittest.TestCase):
    def test_summary_uses_median_and_nearest_rank_p95(self):
        self.assertEqual({'median': 2.0, 'p95': 3.0}, daemon.summarize_durations([1, 2, 3]))
        self.assertEqual({'median': 2.5, 'p95': 4.0}, daemon.summarize_durations([4, 1, 3, 2]))
        self.assertEqual(19, daemon.summarize_durations(list(range(1, 21)))['p95'])
        for invalid in [[], [-1], [float('nan')], [float('inf')]]:
            with self.assertRaises(ValueError):
                daemon.summarize_durations(invalid)

    def test_modes_preserve_command_and_failure_evidence(self):
        result = dict(duration_ns=1200000, exit_code=1, stdout='', stderr='outcome is uncertain', timed_out=False)
        with patch.object(daemon, 'run_process', return_value=result) as execute:
            row = daemon.measure(['java', '-jar', 'app.jar'], ['get_device_info', '-p', 'ios'],
                                 mode='standalone', phase='subsequent', timeout_ms=10, env={})
        self.assertEqual(['java', '-jar', 'app.jar', '--standalone', 'get_device_info', '-p', 'ios'],
                         execute.call_args.args[0])
        self.assertEqual(['get_device_info', '-p', 'ios'], row['command'])
        self.assertEqual(1.2, row['duration_ms'])
        self.assertTrue(row['uncertain'])
        self.assertEqual(1, row['exit_code'])
        self.assertFalse(row['timed_out'])
        self.assertIsNone(row['host_invocation_ns'])
        self.assertIsNone(row['component_init_ns'])
        self.assertIsNone(row['cli_prepare_ns'])

    def test_trace_counts_do_not_hide_admission(self):
        rows = [dict(stage='simctl', durationNs=4), dict(stage='http.exchange', durationNs=5),
                dict(stage='health', durationNs=3), dict(stage='adb', durationNs=2, parentSpanId='op'),
                dict(stage='operation', spanId='op')]
        self.assertEqual({'native_http_count': 1, 'health_count': 1, 'admission_count': 1,
                          'admission_ns': 4, 'adb_count': 1, 'simctl_count': 1}, daemon.trace_counts(rows))

    def test_collection_keeps_identical_parameters_and_records_all_phases(self):
        result = dict(duration_ns=1000000, exit_code=0, stdout='{"running":false}', stderr='', timed_out=False)
        calls = []
        def execute(prefix, command, **kwargs):
            calls.append((command, kwargs['mode']))
            return {**result, 'duration_ms': 1, 'phase': kwargs['phase'], 'mode': kwargs['mode'],
                    'command': command, 'uncertain': False}
        with tempfile.TemporaryDirectory() as temporary, patch.object(daemon, 'run_process', return_value=result):
            output = Path(temporary) / 'result'
            summary = daemon.collect(['java'], platform_name='ios', output=output,
                                     repetitions=2, timeout_ms=1000, env={}, target='fixture', execute=execute,
                                     prepare=lambda *a, **kw: {'setup_duration_ns': 9000000000, 'setup_error': None})
            self.assertEqual(12, len(calls))
            self.assertEqual(calls[0][0], calls[3][0])
            self.assertEqual(calls[6][0], calls[9][0])
            self.assertEqual(['flow', '-p', 'ios', '--step',
                              'wait_for_element --resource-id start --timeout 5000 --bundle-id dev.visiontest.benchmark',
                              '--step', 'wait_for_element --resource-id start --timeout 5000 --bundle-id dev.visiontest.benchmark',
                              '--json'], calls[6][0])
            self.assertEqual(2, summary['flow/daemon']['samples'])
            self.assertEqual(1, summary['flow/daemon']['median_ms'])
            self.assertEqual(16, len((output / 'samples.jsonl').read_text().splitlines()))
            calls.clear()
            daemon.collect(['java'], platform_name='android', output=Path(temporary) / 'android',
                           repetitions=2, timeout_ms=1000, env={}, target='fixture', execute=execute,
                                     prepare=lambda *a, **kw: {'setup_duration_ns': 9000000000, 'setup_error': None})
            self.assertEqual(['flow', '-p', 'android', '--step',
                              'wait_for_element --resource-id dev.visiontest.benchmark:id/start --timeout 5000',
                              '--step', 'wait_for_element --resource-id dev.visiontest.benchmark:id/start --timeout 5000',
                              '--json'], calls[6][0])
            self.assertEqual(calls[6][0], calls[9][0])

    def test_complete_trace_exposes_host_costs_and_no_initialization_is_explicit_zero(self):
        result = dict(duration_ns=1200000, exit_code=0, stdout='{}', stderr='', timed_out=False)
        rows = [dict(type='session'), dict(stage='invocation', durationNs=23),
                dict(stage='cli.prepare', durationNs=7),
                dict(type='session.end', complete=True, dropped=0, invocationsStarted=1, invocationsCompleted=1)]
        with tempfile.TemporaryDirectory() as temporary, patch.object(daemon, 'run_process', return_value=result):
            trace = Path(temporary) / 'trace.jsonl'
            trace.write_text('\n'.join(json.dumps(row) for row in rows))
            row = daemon.measure(['java'], ['get_device_info'], mode='daemon', phase='subsequent',
                                 timeout_ms=10, env={}, trace_path=trace)
            self.assertTrue(row['trace_complete'])
            self.assertEqual(23, row['host_invocation_ns'])
            self.assertEqual(7, row['cli_prepare_ns'])
            self.assertEqual(0, row['component_init_ns'])
            self.assertEqual(0, row['native_http_count'])

    def test_setup_failure_is_retained_and_never_executes_measured_command(self):
        result = dict(duration_ns=1000000, exit_code=0, stdout='{"running":false}', stderr='', timed_out=False)
        with tempfile.TemporaryDirectory() as temporary, patch.object(daemon, 'run_process', return_value=result), \
                patch.object(daemon, 'measure') as execute:
            output = Path(temporary) / 'result'
            summary = daemon.collect(['java'], platform_name='ios', target='fixture', output=output,
                                     repetitions=1, timeout_ms=1000, env={}, execute=execute,
                                     prepare=lambda *a, **kw: {'setup_duration_ns': 9, 'setup_error': 'fixture not ready'})
            execute.assert_not_called()
            self.assertEqual(1, summary['flow/daemon']['setup_failures'])
            rows = [json.loads(line) for line in (output / 'samples.jsonl').read_text().splitlines()]
            failures = [r for r in rows if r.get('setup_error')]
            self.assertEqual(8, len(failures))
            self.assertTrue(all(r['duration_ns'] is None and r['exit_code'] is None for r in failures))

    def test_fixture_preparation_resets_only_fixed_namespace_and_uses_standalone_readiness(self):
        self.assertTrue(callable(getattr(daemon, 'prepare_fixture', None)), 'fixture reset/readiness seam is required')
        success = dict(exit_code=0, stdout='', stderr='', timed_out=False, duration_ns=1)
        inventory = {**success, 'stdout': 'List of devices attached\nfixture device\n'}
        installed = {**success, 'stdout': 'package:/fixture.apk'}
        with patch.object(daemon, 'run_process', side_effect=[inventory, installed, success, success]) as run, \
                patch.object(daemon, 'wait_fixture_ready', return_value=(True, None)) as ready:
            result = daemon.prepare_fixture(['java'], platform_name='android', target='fixture', timeout_ms=1000, env={})
            self.assertIsNone(result['setup_error'])
            self.assertEqual(['adb', '-s', 'fixture', 'shell', 'am', 'force-stop', 'dev.visiontest.benchmark'],
                             run.call_args_list[2].args[0])
            self.assertEqual(['java', '--standalone'], ready.call_args.args[1]['cliPrefix'])

    def test_ambiguous_target_never_resets_an_app(self):
        inventory = dict(exit_code=0, stdout='List of devices attached\nfixture device\nother device\n',
                         stderr='', timed_out=False, duration_ns=1)
        with patch.object(daemon, 'run_process', return_value=inventory) as run:
            result = daemon.prepare_fixture(['java'], platform_name='android', target='fixture', timeout_ms=1000, env={})
        self.assertIsNotNone(result['setup_error'])
        self.assertEqual(1, run.call_count)

    def test_ios_preparation_accepts_absent_fixture_process_but_never_resets_other_bundle(self):
        success = dict(exit_code=0, stdout='', stderr='', timed_out=False, duration_ns=1)
        inventory = {**success, 'stdout': '{"devices":{"runtime":[{"udid":"fixture","state":"Booted"}]}}'}
        installed = {**success, 'stdout': '/synthetic/BenchmarkFixture.app'}
        absent = {**success, 'exit_code': 3}
        with patch.object(daemon, 'run_process', side_effect=[inventory, installed, absent, success]) as run, \
                patch.object(daemon, 'wait_fixture_ready', return_value=(True, None)):
            result = daemon.prepare_fixture(['java'], platform_name='ios', target='fixture', timeout_ms=1000, env={})
        self.assertIsNone(result['setup_error'])
        self.assertEqual(['xcrun', 'simctl', 'terminate', 'fixture', 'dev.visiontest.benchmark'], run.call_args_list[2].args[0])
        self.assertEqual(['xcrun', 'simctl', 'launch', 'fixture', 'dev.visiontest.benchmark', 'navigation', 'en'],
                         run.call_args_list[3].args[0])

    def test_normally_returned_operation_failure_is_never_a_successful_duration(self):
        result = dict(duration_ns=1200000, exit_code=0, stdout='{"success":false,"error":"native failure"}',
                      stderr='', timed_out=False)
        with patch.object(daemon, 'run_process', return_value=result):
            row = daemon.measure(['java'], ['get_device_info'], mode='daemon', phase='subsequent',
                                 timeout_ms=10, env={})
        self.assertTrue(row['operation_failed'])
