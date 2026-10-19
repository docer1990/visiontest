import contextlib
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import tempfile
from unittest.mock import patch

import importlib.util
import unittest


class IOSSelectionTests(unittest.TestCase):
    def test_selects_newest_runtime(self):
        spec = importlib.util.find_spec('benchmarks.ci_manifest')
        self.assertIsNotNone(spec, 'CI manifest helper must exist')
        from benchmarks.ci_manifest import select_ios_target
        devices = {'devices': {
            'com.apple.CoreSimulator.SimRuntime.iOS-26-2': [dict(name='iPhone 17', udid='older', state='Shutdown', isAvailable=True)],
            'com.apple.CoreSimulator.SimRuntime.iOS-26-5': [dict(name='iPhone 17', udid='newer', state='Shutdown', isAvailable=True)],
        }}
        self.assertEqual(select_ios_target(devices), 'newer')

    def test_rejects_ambiguous_offline_and_unexpected_booted(self):
        from benchmarks.ci_manifest import select_ios_target
        base = dict(name='iPhone 17', udid='one', state='Shutdown', isAvailable=True)
        for entries in ([base, {**base, 'udid': 'two'}], [{**base, 'isAvailable': False}],
                        [base, dict(name='iPhone 16', udid='other', state='Booted', isAvailable=True)]):
            with self.subTest(entries=entries), self.assertRaises(ValueError):
                select_ios_target({'devices': {'com.apple.CoreSimulator.SimRuntime.iOS-26-5': entries}})


class ManifestTests(unittest.TestCase):
    def test_templates_preserve_selectors_and_pin_timeout_and_safe_launch(self):
        from benchmarks import ci_manifest
        for platform in ('ios', 'android'):
            result = ci_manifest.build_manifest(platform, 'selected', {}, 'fixture-tree')
            self.assertEqual(result['fixtureIdentity'], 'com.example.visiontest.benchmark')
            self.assertEqual(result['fixtureRevision'], 'fixture-tree')
            self.assertEqual(result['target'], 'selected')
            self.assertTrue(all(case['timeoutMs'] == 20000 for case in result['cases']))
            self.assertEqual(result['processEnvironment']['VISIONTEST_TRACE_PERFORMANCE'], '')
            lookup = next(case for case in result['cases'] if case['id'] == 'lookup')
            self.assertEqual(lookup['mcpArguments']['resourceId'], 'start' if platform == 'ios' else 'com.example.visiontest.benchmark:id/start')

    def test_builder_rejects_invalid_configuration(self):
        from benchmarks import ci_manifest
        for args in [('bad', 'target', {}, 'rev'), ('ios', '', {}, 'rev'), ('ios', 'target', {}, ''), ('ios', 'target', {}, 'rev', 0)]:
            with self.subTest(args=args), self.assertRaises(ValueError):
                ci_manifest.build_manifest(*args)




class CLITests(unittest.TestCase):
    def setUp(self):
        from benchmarks import ci_manifest
        self.module = ci_manifest
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        shutil.copytree(ci_manifest.ROOT / 'benchmarks/manifests', self.root / 'benchmarks/manifests')
        self.patch(ci_manifest, 'ROOT', self.root)
        self.output = self.root / 'results/manifest.json'
        self.files = [
            'app/build/libs/visiontest.jar',
            'benchmarks/fixtures/android/build/outputs/apk/debug/benchmark-fixture-debug.apk',
            'automation-server/build/outputs/apk/debug/automation-server-debug.apk',
            'automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk',
        ]
        for relative in self.files:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(relative.encode())
        self.commands = []
        self.adb_devices = 'List of devices attached\nemulator-5554\tdevice\n'
        self.ios_devices = {'devices': {'com.apple.CoreSimulator.SimRuntime.iOS-26-5': [
            dict(name='iPhone 17', udid='ios-one', state='Booted', isAvailable=True)]}}
        self.patch(ci_manifest.subprocess, 'run', side_effect=self.command)

    def patch(self, owner, name, *args, **kwargs):
        patcher = patch.object(owner, name, *args, **kwargs)
        self.addCleanup(patcher.stop)
        return patcher.start()

    def command(self, argv, **kwargs):
        from types import SimpleNamespace
        self.commands.append(argv)
        self.assertEqual(kwargs['env']['VISIONTEST_TRACE_PERFORMANCE'], '')
        if argv == ['adb', 'devices']:
            output = self.adb_devices
        elif argv[:3] == ['xcrun', 'simctl', 'list']:
            output = json.dumps(self.ios_devices)
        elif argv[:3] == ['git', 'rev-parse', 'HEAD']:
            output = 'actual-head\n'
        elif argv[:2] == ['git', 'rev-parse']:
            output = 'actual-fixture-tree\n'
        elif argv == ['java', '-version']:
            return SimpleNamespace(stdout='', stderr='openjdk test-version', returncode=0)
        elif 'getprop' in argv:
            output = 'Android test value\n'
        else:
            self.fail(f'unexpected command {argv}')
        return SimpleNamespace(stdout=output, stderr='', returncode=0)

    def write(self, platform='android'):
        with contextlib.redirect_stderr(io.StringIO()):
            return self.module.main(['write', '--platform', platform, '--output', str(self.output)])

    def test_write_records_actual_source_artifact_and_safe_metadata(self):
        with patch.dict(os.environ, {'GITHUB_SHA': 'event-sha', 'SECRET_TOKEN': 'secret',
                                    'ImageOS': 'ubuntu24', 'ImageVersion': '123',
                                    'VISIONTEST_TRACE_PERFORMANCE': 'inherited'}):
            self.assertEqual(self.write(), 0)
        result = json.loads(self.output.read_text())
        encoded = json.dumps(result)
        self.assertIn('actual-head', encoded)
        self.assertIn('actual-fixture-tree', encoded)
        self.assertIn(hashlib.sha256(self.files[0].encode()).hexdigest(), encoded)
        self.assertNotIn('event-sha', encoded)
        self.assertNotIn('secret', encoded)
        self.assertIn('ubuntu24', encoded)
        self.assertEqual(result['processEnvironment'], {'VISIONTEST_TRACE_PERFORMANCE': ''})
        self.assertEqual(result['environment']['connectionType']['value'], 'emulator')
        self.assertEqual(self.module.load_manifest(self.output), result)

    def test_missing_critical_artifact_fails_without_writing(self):
        for relative in self.files:
            with self.subTest(relative=relative):
                artifact = self.root / relative
                content = artifact.read_bytes()
                artifact.unlink()
                self.assertEqual(self.write(), 2)
                self.assertFalse(self.output.exists())
                artifact.write_bytes(content)

    def test_rejects_missing_multiple_and_offline_android_targets(self):
        for devices in ('List of devices attached\n', 'a\toffline\n', 'a\tdevice\nb\tdevice\n'):
            self.adb_devices = devices
            self.assertEqual(self.write(), 2)
            self.assertFalse(self.output.exists())

    def test_invalid_options_and_existing_output_do_not_run_commands(self):
        for args in ([], ['write', '--platform', 'bad', '--output', str(self.output)],
                     ['write', '--platform', 'android'], ['ios-target', '--bad']):
            with contextlib.redirect_stderr(io.StringIO()):
                self.assertEqual(self.module.main(args), 2)
        self.output.parent.mkdir()
        self.output.write_text('existing')
        self.assertEqual(self.write(), 2)
        self.assertEqual(self.output.read_text(), 'existing')
        self.assertEqual(self.commands, [])

    def test_ios_target_only_lists_devices(self):
        output = io.StringIO()
        with contextlib.redirect_stdout(output):
            self.assertEqual(self.module.main(['ios-target']), 0)
        self.assertEqual(output.getvalue(), 'ios-one\n')
        self.assertEqual(self.commands, [['xcrun', 'simctl', 'list', 'devices', 'available', '-j']])

    def test_ios_requires_one_booted_target_and_native_binary(self):
        self.assertEqual(self.write('ios'), 2)
        fixture = self.root / 'benchmarks/fixtures/ios/.derivedData/Build/Products/Debug-iphonesimulator/BenchmarkFixture.app/BenchmarkFixture'
        native = self.root / 'test-output/benchmark-native/Build/Products/Debug-iphonesimulator/IOSAutomationServerUITests-Runner.app/PlugIns/IOSAutomationServerUITests.xctest/IOSAutomationServerUITests'
        project = self.root / 'ios-automation-server/IOSAutomationServer.xcodeproj/project.pbxproj'
        for path in (fixture, native, project):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'built')
        self.assertEqual(self.write('ios'), 0)
        manifest = json.loads(self.output.read_text())
        self.assertEqual(manifest['target'], 'ios-one')
        self.assertIn('VISION_TEST_IOS_PROJECT_PATH', manifest['processEnvironment'])
        self.output.unlink()
        self.ios_devices['devices']['com.apple.CoreSimulator.SimRuntime.iOS-26-5'][0]['state'] = 'Shutdown'
        self.assertEqual(self.write('ios'), 2)

    def test_ios_missing_or_ambiguous_native_artifacts_fail_setup(self):
        fixture = self.root / 'benchmarks/fixtures/ios/.derivedData/Build/Products/Debug-iphonesimulator/BenchmarkFixture.app/BenchmarkFixture'
        project = self.root / 'ios-automation-server/IOSAutomationServer.xcodeproj/project.pbxproj'
        for path in (fixture, project):
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(b'fixture')
        self.assertEqual(self.write('ios'), 2)
        native = self.root / 'test-output/benchmark-native/Build/Products/Debug-iphonesimulator/one/IOSAutomationServerUITests.xctest/IOSAutomationServerUITests'
        native.parent.mkdir(parents=True)
        native.write_bytes(b'native')
        duplicate = native.parent.parent.parent / 'two/IOSAutomationServerUITests.xctest/IOSAutomationServerUITests'
        duplicate.parent.mkdir(parents=True)
        duplicate.write_bytes(b'native')
        self.assertEqual(self.write('ios'), 2)
        self.assertFalse(self.output.exists())

    def test_missing_ios_targets_fail_setup(self):
        entries = self.ios_devices['devices']['com.apple.CoreSimulator.SimRuntime.iOS-26-5']
        entries.append({**entries[0], 'udid': 'ios-two'})
        self.assertEqual(self.write('ios'), 2)
        entries.clear()
        self.assertEqual(self.write('ios'), 2)

    def test_optional_metadata_failure_is_explicit(self):
        original = self.command

        def command(argv, **kwargs):
            if argv == ['java', '-version'] or 'getprop' in argv:
                raise self.module.subprocess.CalledProcessError(1, argv)
            return original(argv, **kwargs)

        self.patch(self.module.subprocess, 'run', side_effect=command)
        self.assertEqual(self.write(), 0)
        environment = json.loads(self.output.read_text())['environment']
        for key in ('JVM', 'targetOS', 'targetModel'):
            self.assertTrue(environment[key]['unavailableReason'])
