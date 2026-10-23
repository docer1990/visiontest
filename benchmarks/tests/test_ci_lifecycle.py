import json
import os
from pathlib import Path
import signal
import subprocess
import sys
import tempfile
import time
import unittest


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / '.github/scripts/command-benchmarks.sh'


class LifecycleTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.bin = self.root / 'bin'
        self.bin.mkdir()
        self.events = self.root / 'events.jsonl'
        self.env = {**os.environ, 'PATH': f'{self.bin}:{os.environ["PATH"]}',
                    'FAKE_ROOT': str(self.root), 'FAKE_PYTHON': sys.executable}
        self.mock = self.bin / 'mock'
        self.mock.write_text(f'''#!{sys.executable}
import json, os, pathlib, signal, sys, time
root = pathlib.Path(os.environ['FAKE_ROOT'])
name = pathlib.Path(sys.argv[0]).name
args = sys.argv[1:]
with (root / 'events.jsonl').open('a') as out:
    out.write(json.dumps([name, *args]) + '\\n')
if name == 'python3':
    if 'write' in args:
        path = pathlib.Path(args[args.index('--output') + 1])
        path.write_text(json.dumps({{'target': 'fake-target'}}))
    elif '-c' in args:
        print('fake-target')
    elif 'commands' in args:
        sys.exit(int(os.environ.get('FAIL_BENCHMARK', '0')))
    elif 'unittest' in args:
        sys.exit(int(os.environ.get('FAIL_SMOKE', '0')))
elif name in ('adb', 'xcodebuild') and ('instrument' in args or 'test-without-building' in args):
    (root / 'native.pid').write_text(str(os.getpid()))
    if os.environ.get('EXIT_NATIVE') == '1':
        sys.exit(3)
    signal.signal(signal.SIGTERM, lambda *_: sys.exit(0))
    while True:
        time.sleep(0.02)
elif name == 'adb' and 'force-stop' in args:
    sys.exit(int(os.environ.get('FAIL_CLEANUP', '0')))
elif name == 'curl':
    for _ in range(100):
        if (root / 'native.pid').exists():
            break
        time.sleep(0.01)
    time.sleep(float(os.environ.get('HEALTH_DELAY', '0')))
    sys.exit(int(os.environ.get('FAIL_HEALTH', '0')))
''')
        self.mock.chmod(0o755)
        for name in ('python3', 'adb', 'xcodebuild', 'xcrun', 'curl'):
            (self.bin / name).symlink_to(self.mock)

    def run_wrapper(self, *args, **variables):
        return subprocess.run(['bash', str(SCRIPT), *args], cwd=self.root,
                              env={**self.env, **variables}, capture_output=True,
                              text=True, timeout=15)

    def calls(self):
        return [json.loads(line) for line in self.events.read_text().splitlines()]

    def assert_native_reaped(self):
        pid = int((self.root / 'native.pid').read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(pid, 0)

    def test_invalid_arguments_do_not_access_backend(self):
        for args in ((), ('android',), ('android', 'cli', 'warm', 'extra'),
                     ('linux', 'cli', 'warm'), ('android', 'bad', 'warm'),
                     ('ios', 'mcp', 'native-cold'), ('ios', 'mcp', '')):
            with self.subTest(args=args):
                result = self.run_wrapper(*args)
                self.assertEqual(2, result.returncode, result.stderr)
                self.assertIn('Usage:', result.stderr)
                self.assertFalse(self.events.exists())

    def test_failure_reaps_android_native_without_replaying_commands(self):
        result = self.run_wrapper('android', 'cli', 'warm', FAIL_BENCHMARK='7')
        self.assertEqual(7, result.returncode, result.stderr)
        self.assert_native_reaped()
        calls = self.calls()
        launches = [call for call in calls if 'instrument' in call]
        self.assertEqual(1, len(launches))
        self.assertIn('dev.visiontest.automationserver.AutomationServerTest#runAutomationServer', launches[0])
        self.assertIn('dev.visiontest.automationserver.test/dev.visiontest.automationserver.AutomationInstrumentationRunner', launches[0])
        self.assertIn('9008', launches[0])
        benchmark = [call for call in calls if 'commands' in call]
        self.assertEqual(1, len(benchmark))
        for flag, value in (('--samples', '30'), ('--warmup', '5'), ('--tracing', 'paired')):
            self.assertEqual(value, benchmark[0][benchmark[0].index(flag) + 1])
        self.assertTrue(any('force-stop' in call for call in calls))

    def test_smoke_failure_skips_measurement_and_reaps_ios_native(self):
        result = self.run_wrapper('ios', 'mcp', 'host-cold', FAIL_SMOKE='9')
        self.assertEqual(9, result.returncode, result.stderr)
        self.assert_native_reaped()
        calls = self.calls()
        self.assertFalse(any('commands' in call for call in calls))
        launches = [call for call in calls if 'test-without-building' in call]
        self.assertEqual(1, len(launches))
        self.assertIn('-only-testing:IOSAutomationServerUITests/AutomationServerUITest/testRunAutomationServer', launches[0])
        self.assertIn('platform=iOS Simulator,id=fake-target', launches[0])
        self.assertTrue(any('shutdown' in call for call in calls))

    def test_success_reaps_native(self):
        result = self.run_wrapper('ios', 'cli', 'warm')
        self.assertEqual(0, result.returncode, result.stderr)
        self.assert_native_reaped()

    def test_cleanup_failure_rejects_an_otherwise_successful_run(self):
        result = self.run_wrapper('android', 'cli', 'warm', FAIL_CLEANUP='11')
        self.assertEqual(1, result.returncode, result.stderr)
        self.assert_native_reaped()

    def test_startup_failure_is_not_replayed_and_skips_smoke(self):
        result = self.run_wrapper('android', 'mcp', 'warm', EXIT_NATIVE='1',
                                  FAIL_HEALTH='1', HEALTH_DELAY='0.05')
        self.assertEqual(1, result.returncode, result.stderr)
        self.assert_native_reaped()
        calls = self.calls()
        self.assertEqual(1, len([call for call in calls if 'instrument' in call]))
        self.assertFalse(any('unittest' in call or 'commands' in call for call in calls))

    def test_termination_reaps_native_and_records_failure(self):
        process = subprocess.Popen(['bash', str(SCRIPT), 'android', 'mcp', 'host-cold'],
                                   cwd=self.root, env={**self.env, 'HEALTH_DELAY': '0.5'},
                                   stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        self.addCleanup(lambda: process.poll() is None and process.kill())
        deadline = time.monotonic() + 5
        while not (self.root / 'native.pid').exists() and time.monotonic() < deadline:
            time.sleep(0.01)
        self.assertTrue((self.root / 'native.pid').exists())
        process.send_signal(signal.SIGTERM)
        _, stderr = process.communicate(timeout=10)
        self.assertEqual(143, process.returncode, stderr)
        self.assert_native_reaped()


class WorkflowContractTest(unittest.TestCase):
    @unittest.skipUnless(Path('/usr/bin/ruby').is_file(), 'Ruby YAML parser unavailable')
    def test_manual_matrix_preserves_failures_and_uses_resolved_sha(self):
        workflow = ROOT / '.github/workflows/command-benchmarks.yaml'
        self.assertTrue(workflow.is_file())
        result = subprocess.run(['/usr/bin/ruby', '-ryaml', '-rjson', '-e',
                                 'puts JSON.generate(YAML.load_file(ARGV[0]))', str(workflow)],
                                capture_output=True, text=True, check=True)
        data = json.loads(result.stdout)
        self.assertEqual(['workflow_dispatch'], list(data['on']))
        self.assertEqual({'contents': 'read'}, data['permissions'])
        jobs = data['jobs']
        strategy = jobs['benchmarks']['strategy']
        self.assertFalse(strategy['fail-fast'])
        self.assertEqual(['android', 'ios'], strategy['matrix']['platform'])
        self.assertEqual(['cli', 'mcp'], strategy['matrix']['mode'])
        self.assertEqual(['warm', 'host-cold'], strategy['matrix']['lifecycle'])
        self.assertEqual(120, jobs['benchmarks']['timeout-minutes'])
        for name in ('benchmarks', 'report'):
            checkout = next(step for step in jobs[name]['steps']
                            if step.get('uses', '').startswith('actions/checkout@'))
            self.assertEqual('${{ needs.resolve.outputs.sha }}', checkout['with']['ref'])
            self.assertFalse(checkout['with']['persist-credentials'])
        uploads = [step for step in jobs['benchmarks']['steps']
                   if step.get('uses', '').startswith('actions/upload-artifact@')]
        self.assertEqual('always()', uploads[0]['if'])
        self.assertIn('matrix.platform', uploads[0]['with']['name'])
        self.assertIn('always()', jobs['report']['if'])
        download = next(step for step in jobs['report']['steps']
                        if step.get('uses', '').startswith('actions/download-artifact@'))
        self.assertEqual('command-benchmarks-data-*-${{ github.run_id }}-${{ github.run_attempt }}',
                         download['with']['pattern'])


if __name__ == '__main__':
    unittest.main()
