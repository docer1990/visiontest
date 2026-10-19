import unittest

from benchmarks import report


def sample(state='off', duration=10, **extra):
    return {'status': 'passed', 'duration_ns': duration, 'operation_duration_ns': duration,
            'process_duration_ns': duration + 100, 'case_id': 'info', 'mode': 'cli',
            'lifecycle': 'warm', 'tracing': state, 'warmup': False, 'pair_id': 'pair1',
            'fixture_revision': 'v1', **extra}


class ReportTest(unittest.TestCase):
    def test_populations_are_split_by_case_mode_lifecycle_and_tracing(self):
        self.assertTrue(hasattr(report, 'build_report'), 'grouped report is missing')
        result = report.build_report([sample(), sample('on'), sample(lifecycle='host-cold')])
        self.assertEqual(3, len(result['groups']))
        self.assertEqual(3, result['attempts'])
        self.assertEqual(1, result['groups'][0]['summary']['measured']['attempts'])

    def test_overhead_is_paired_and_warmups_and_failed_pairs_stay_visible(self):
        self.assertTrue(hasattr(report, 'build_report'), 'paired report is missing')
        result = report.build_report([
            sample(), sample('on', 15),
            sample(pair_id='pair2'), sample('on', 50, pair_id='pair2', status='timeout'),
            sample(pair_id='warm', warmup=True), sample('on', 1000, pair_id='warm', warmup=True)])
        overhead = result['overhead'][0]
        self.assertEqual(2, overhead['attempted_pairs'])
        self.assertEqual(1, overhead['successful_pairs'])
        self.assertEqual(5, overhead['operation']['median_delta_ns'])
        self.assertEqual(5, overhead['process']['median_delta_ns'])
        self.assertEqual(50, overhead['operation']['median_percent'])
        self.assertEqual(6, result['attempts'])

    def test_unavailable_boundaries_and_mismatched_fixtures_are_explicit(self):
        self.assertTrue(hasattr(report, 'build_report'), 'paired report is missing')
        result = report.build_report([sample(operation_duration_ns=None),
                                      sample('on', operation_duration_ns=None)])
        self.assertEqual(0, result['overhead'][0]['operation']['count'])
        self.assertIn('operation_boundary_unavailable', result['overhead'][0]['measurement_gaps'])
        result = report.build_report([sample(), sample('on', fixture_revision='different')])
        self.assertEqual(0, result['overhead'][0]['successful_pairs'])
        self.assertIn('pair_fixture_mismatch', result['overhead'][0]['measurement_gaps'])

    def test_stage_contributors_keep_host_and_native_populations_separate(self):
        result = report.build_report([sample(host_stages={'operation': {'inclusive_ns': 10, 'self_ns': 4, 'count': 1}},
                                             native_stages={'operation': {'inclusive_ns': 6, 'self_ns': 6, 'count': 1}})])
        group = result['groups'][0]
        self.assertIn('contributors', group)
        self.assertEqual(4, group['contributors']['host']['operation']['self']['median_ns'])
        self.assertEqual(6, group['contributors']['native']['operation']['self']['median_ns'])
