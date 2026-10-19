import importlib
import json
from pathlib import Path
import tempfile
import unittest


class CiReportTest(unittest.TestCase):
    def test_failed_attempts_remain_in_all_timed_population(self):
        import importlib.util
        self.assertIsNotNone(importlib.util.find_spec('benchmarks.ci_report'),
                             'readable CI report module is missing')
        report = importlib.import_module('benchmarks.ci_report')
        with tempfile.TemporaryDirectory() as directory:
            run = Path(directory) / 'run'
            run.mkdir()
            (run / 'environment.json').write_text(json.dumps({
                'platform': 'android', 'mode': 'cli', 'lifecycle': 'warm',
                'selected_case_ids': ['info'], 'requested_samples': 2,
                'requested_warmups': 0, 'tracing': 'off',
                'environment': {'build': {'value': {'sourceSHA': 'tested-sha'}}}}))
            samples = [dict(status=status, duration_ns=duration, case_id='info', mode='cli',
                            lifecycle='warm', tracing='off', warmup=False)
                       for status, duration in [('passed', 10), ('failed', 90)]]
            (run / 'samples.jsonl').write_text(''.join(json.dumps(s) + '\n' for s in samples))
            (run / 'summary.json').write_text('{"attempts": 999, "passed": 999}')
            result = report.render_report(Path(directory))
            self.assertIn('Measured attempts: 2 / 2 expected; passed: 1; failed: 1;', result)
            self.assertIn('| all timed | 2 | 50 ns | 90 ns |', result)
            self.assertIn('| successful only | 1 | 10 ns | 10 ns |', result)
            self.assertIn('Incomplete', result)
            self.assertIn('tested-sha', result)
            self.assertNotIn('999', result)

    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.report = importlib.import_module('benchmarks.ci_report')

    def run_data(self, name='run', samples=None, **environment):
        run = self.root / name
        run.mkdir(parents=True)
        data = {'run_id': name, 'platform': 'android', 'mode': 'cli', 'lifecycle': 'warm',
                'selected_case_ids': ['info'], 'requested_samples': 1, 'requested_warmups': 0,
                'tracing': 'off', 'environment': {'build': {'value': {'sourceSHA': 'source-123'}}},
                **environment}
        (run / 'environment.json').write_text(json.dumps(data))
        (run / 'samples.jsonl').write_text(''.join(json.dumps(s) + '\n' for s in (samples or [])))
        return run

    @staticmethod
    def sample(**extra):
        return {'case_id': 'info', 'mode': 'cli', 'lifecycle': 'warm', 'tracing': 'off',
                'warmup': False, 'status': 'passed', 'duration_ns': 10,
                'process_duration_ns': 10, 'operation_duration_ns': None, **extra}

    def test_no_results_explains_runner_was_not_reached(self):
        result = self.report.render_report(self.root, 8)
        self.assertIn('Configurations: 0 / 8 expected', result)
        self.assertIn('No produced results: setup did not reach the runner', result)
        self.assertNotIn('0 ns', result)

    def test_missing_configurations_and_interrupted_jsonl_remain_visible(self):
        run = self.run_data(samples=[self.sample()], requested_samples=2)
        with (run / 'samples.jsonl').open('a') as output:
            output.write('{"status":')
        result = self.report.render_report(self.root, 8)
        self.assertIn('Configurations: 1 / 8 expected', result)
        self.assertIn('Missing configurations: 7', result)
        self.assertIn('Measured attempts: 1 / 2 expected', result)
        self.assertIn('incomplete_jsonl_line:2', result)
        self.assertIn('Incomplete', result)

    def test_environment_only_is_not_a_successful_configuration(self):
        run = self.run_data()
        (run / 'samples.jsonl').unlink()
        result = self.report.render_report(self.root)
        self.assertIn('Configurations: 0 / 1 expected', result)
        self.assertIn('samples.jsonl missing', result)
        self.assertIn('setup did not reach the runner', result)

    def test_null_operation_and_setup_time_stay_separate(self):
        self.run_data(samples=[self.sample(status='setup_failed', duration_ns=None,
                                          process_duration_ns=None, setup_duration_ns=500)])
        result = self.report.render_report(self.root)
        self.assertIn('setup-failed: 1', result)
        self.assertIn('| all timed | 0 | unavailable | unavailable |', result)
        self.assertIn('| operation boundary | 0 | unavailable | unavailable |', result)
        self.assertIn('Setup timing (separate from operation)', result)
        self.assertIn('500 ns', result)
        self.assertIn('CLI process lifetime', result)
        self.assertIn('MCP tools/call round trip', result)
        self.assertIn('No cross-mode speedup or agent token estimates', result)

    def test_warmups_platforms_and_tested_shas_are_separate(self):
        self.run_data('android-a', [self.sample(), self.sample(warmup=True, duration_ns=1000)],
                      requested_warmups=1)
        self.run_data('ios-b', [self.sample(mode='mcp', duration_ns=80)], platform='ios', mode='mcp',
                      environment={'build': {'value': {'commit': 'runtime-456', 'runnerCommit': 'runner-789'}}})
        result = self.report.render_report(self.root, 2)
        self.assertIn('Platform: android; mode: cli; lifecycle: warm', result)
        self.assertIn('Platform: ios; mode: mcp; lifecycle: warm', result)
        self.assertIn('Tested SHA: source-123', result)
        self.assertIn('Tested SHA: runtime-456', result)
        self.assertIn('runner-789', result)
        self.assertIn('Warmup attempts: 1 / 1 expected', result)
        self.assertIn('| all timed | 1 | 1000 ns | 1000 ns |', result)
        self.assertIn('| all timed | 1 | 10 ns | 10 ns |', result)
        self.assertIn('| all timed | 1 | 80 ns | 80 ns |', result)
        self.assertIn('Multiple tested SHAs', result)

    def test_paired_deltas_and_measured_contributors_keep_gaps(self):
        stages = {'http.exchange': {'inclusive_ns': 8, 'self_ns': 5}}
        native = {'request': {'inclusive_ns': 6, 'self_ns': 4}}
        self.run_data(samples=[self.sample(pair_id='p', fixture_revision='f'),
                               self.sample(tracing='on', pair_id='p', fixture_revision='f',
                                           process_duration_ns=15, trace_complete=False,
                                           host_stages=stages, native_stages=native,
                                           measurement_gaps=['native_metadata_missing']),
                               self.sample(tracing='on', warmup=True, host_stages={
                                   'warmup-only': {'inclusive_ns': 999, 'self_ns': 999}})],
                      tracing='paired', requested_warmups=1)
        result = self.report.render_report(self.root)
        self.assertIn('Paired tracing overhead', result)
        self.assertIn('attempted pairs: 1; successful pairs: 1', result)
        self.assertIn('| process | 1 | 5 ns | 50% |', result)
        self.assertIn('operation_boundary_unavailable', result)
        self.assertIn('| host | http.exchange | 1 | 8 ns | 8 ns | 5 ns | 5 ns |', result)
        self.assertIn('| native | request | 1 | 6 ns | 6 ns | 4 ns | 4 ns |', result)
        self.assertNotIn('warmup-only', result)
        self.assertIn('Trace completeness: complete: 0; incomplete: 1;', result)
        self.assertIn('native_metadata_missing', result)

    def test_metadata_and_relative_artifact_links_are_safe(self):
        run = self.run_data('space run', [self.sample()], target='<img src=x>|[bad]\n# title',
                            environment={'build': {'value': {'sourceSHA': 'sha*test'}},
                                         'targetModel': {'unavailableReason': 'unknown <model>'}})
        (run / 'provenance.json').write_text(json.dumps({'label': '<b>local</b>', 'runnerCommit': 'abc'}))
        (run / 'summary.json').write_text('{}')
        result = self.report.render_report(self.root)
        self.assertNotIn('<img', result)
        self.assertNotIn('<b>', result)
        self.assertIn('&lt;img', result)
        self.assertIn('sha\\*test', result)
        self.assertIn('unknown &lt;model&gt;', result)
        self.assertIn('[environment.json](space%20run/environment.json)', result)
        self.assertIn('[samples.jsonl](space%20run/samples.jsonl)', result)
        self.assertIn('[summary.json](space%20run/summary.json)', result)
        self.assertIn('[provenance.json](space%20run/provenance.json)', result)
        self.assertIn('abc', result)

    def test_expected_attempts_are_checked_per_case_and_state(self):
        self.run_data(samples=[self.sample(), self.sample()], selected_case_ids=['info', 'lookup'])
        result = self.report.render_report(self.root)
        self.assertIn('Measured attempts: 2 / 2 expected', result)
        self.assertIn('missing_attempts:lookup/off/measured:1', result)
        self.assertIn('unexpected_attempts:info/off/measured:1', result)
        self.assertIn('Incomplete', result)

    def test_cli_writes_identical_markdown_to_stdout_and_output(self):
        from contextlib import redirect_stdout
        import io
        self.run_data(samples=[self.sample()])
        output = self.root / 'reports' / 'report.md'
        stdout = io.StringIO()
        with redirect_stdout(stdout):
            status = self.report.main(['--input-dir', str(self.root), '--output', str(output),
                                       '--expected-configurations', '8'])
        self.assertEqual(0, status)
        self.assertEqual(output.read_text(), stdout.getvalue())
        self.assertIn('Configurations: 1 / 8 expected', stdout.getvalue())

    def test_invalid_expected_configuration_count_is_rejected(self):
        with self.assertRaises(ValueError):
            self.report.render_report(self.root, 0)

    def test_repeated_runs_do_not_fill_missing_configurations(self):
        self.run_data('first', [self.sample()])
        self.run_data('second', [self.sample()])
        result = self.report.render_report(self.root, 8)
        self.assertIn('Configurations: 1 / 8 expected', result)
        self.assertIn('Missing configurations: 7', result)
        self.assertEqual(2, result.count('Platform: android; mode: cli; lifecycle: warm'))

    def test_each_case_and_state_has_expected_counts_and_separate_warmups(self):
        self.run_data(samples=[self.sample(), self.sample(warmup=True, duration_ns=200),
                               self.sample(case_id='lookup', duration_ns=50)],
                      selected_case_ids=['info', 'lookup'], requested_warmups=1)
        result = self.report.render_report(self.root)
        info = result.split('### Case: info;')[1].split('### Case: lookup;')[0]
        self.assertIn('Measured attempts: 1 / 1 expected', info)
        self.assertIn('Warmup attempts: 1 / 1 expected', info)
        self.assertIn('| all timed | 1 | 200 ns | 200 ns |', info)
        # Mixing distinct case latencies creates no meaningful benchmark statistic.
        self.assertNotIn('| all timed | 2 | 30 ns | 50 ns |', result)

    def test_millisecond_display_preserves_raw_nanoseconds(self):
        self.run_data(samples=[self.sample(duration_ns=12_345_678, process_duration_ns=12_345_678)])
        result = self.report.render_report(self.root)
        self.assertIn('12.346 ms (12345678 ns)', result)

    def test_partial_run_marks_overall_collection_incomplete_even_with_one_good_run(self):
        self.run_data('good', [self.sample()])
        self.run_data('partial', [self.sample()], platform='ios', requested_samples=2)
        result = self.report.render_report(self.root, 2)
        self.assertIn('Incomplete collection.', result.split('## Run:')[0])

    def test_invalid_environment_keeps_sibling_evidence(self):
        run = self.run_data('bad')
        (run / 'environment.json').write_text('{')
        self.run_data('good', [self.sample()])
        result = self.report.render_report(self.root)
        self.assertIn('environment.json is invalid', result)
        self.assertIn('Measured attempts: 1 / 1 expected', result)

    def test_invalid_sample_is_reported_and_later_complete_lines_survive(self):
        run = self.run_data(samples=[self.sample()])
        with (run / 'samples.jsonl').open('a') as output:
            output.write('{"status":"not-a-status"}\n')
            output.write(json.dumps(self.sample(status='timeout', duration_ns=90)) + '\n')
        result = self.report.render_report(self.root)
        self.assertIn('invalid_sample_line:2', result)
        self.assertIn('Measured attempts: 2 / 1 expected; passed: 1; failed: 0; setup-failed: 0; timeout: 1.', result)

    def test_cli_artifact_links_resolve_from_output_directory(self):
        from contextlib import redirect_stdout
        import io
        run = self.run_data(samples=[self.sample()])
        output = self.root / 'reports' / 'report.md'
        with redirect_stdout(io.StringIO()):
            self.assertEqual(0, self.report.main(['--input-dir', str(self.root), '--output', str(output)]))
        self.assertIn('[samples.jsonl](../run/samples.jsonl)', output.read_text())

    def test_full_matrix_names_missing_configurations(self):
        self.run_data(samples=[self.sample()])
        result = self.report.render_report(self.root, 8)
        self.assertIn('Missing configuration: ios / mcp / host-cold', result)
        self.assertNotIn('Missing configuration: android / cli / warm\n', result)

    def test_invalid_metadata_and_samples_do_not_abort_sibling_runs(self):
        run = self.run_data('bad', [self.sample()])
        (run / 'environment.json').write_text('{"environment": null}')
        good = self.run_data('good', [self.sample()])
        with (good / 'samples.jsonl').open('a') as output:
            output.write('{"status":"passed","duration_ns":5}\n')
        result = self.report.render_report(self.root)
        self.assertIn('environment metadata is invalid', result)
        self.assertIn('invalid_sample_line:2', result)
        self.assertIn('Measured attempts: 1 / 1 expected', result)

    def test_complete_paired_cli_collection_retains_expected_boundary_gap(self):
        self.run_data(samples=[self.sample(pair_id='pair', fixture_revision='f'),
                               self.sample(pair_id='pair', fixture_revision='f', tracing='on',
                                           process_duration_ns=15, trace_complete=True)], tracing='paired')
        result = self.report.render_report(self.root)
        self.assertIn('Collection status: Requested attempts recorded and passed.', result)
        self.assertIn('Runs with requested attempts recorded and passed: 1.', result)
        self.assertNotIn('Incomplete collection.', result)
        self.assertIn('operation_boundary_unavailable', result)
        self.assertIn('Measurement status: gaps reported; see Gaps below.', result)

    def test_incomplete_trace_does_not_disguise_complete_collection(self):
        self.run_data(samples=[self.sample(tracing='on', trace_complete=False,
                                          measurement_gaps=['native_metadata_missing'])], tracing='on')
        result = self.report.render_report(self.root)
        self.assertIn('Collection status: Requested attempts recorded and passed.', result)
        self.assertNotIn('Incomplete collection.', result)
        self.assertIn('Attribution status: incomplete.', result)
        self.assertIn('native_metadata_missing', result)
        self.assertIn('incomplete_traces', result)

    def test_fixture_mismatch_keeps_paired_collection_incomplete(self):
        self.run_data(samples=[self.sample(pair_id='pair', fixture_revision='before'),
                               self.sample(pair_id='pair', fixture_revision='after', tracing='on',
                                           trace_complete=True)], tracing='paired')
        result = self.report.render_report(self.root)
        self.assertIn('Collection status: Incomplete;', result)
        self.assertIn('Incomplete collection.', result)
        self.assertIn('pair_fixture_mismatch', result)
