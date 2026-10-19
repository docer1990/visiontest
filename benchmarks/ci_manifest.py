"""Prepare manifests from built checkout artifacts; never build or boot devices."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform as host_platform
import re
import subprocess
import sys
import tempfile

from benchmarks.manifest import load_manifest


def select_ios_target(devices: dict) -> str:
    candidates = []
    booted = []
    for runtime, entries in devices.get('devices', {}).items():
        version = re.fullmatch(r'com\.apple\.CoreSimulator\.SimRuntime\.iOS-(\d+(?:-\d+)*)', runtime)
        for device in entries:
            if device.get('state') == 'Booted':
                booted.append(device.get('udid'))
            if version and device.get('isAvailable') is True and device.get('name') == 'iPhone 17':
                candidates.append((tuple(map(int, version[1].split('-'))), device.get('udid')))
    if not candidates:
        raise ValueError('no available iPhone 17 simulator')
    newest = max(version for version, _ in candidates)
    targets = [target for version, target in candidates if version == newest]
    if len(targets) != 1 or not isinstance(targets[0], str) or not targets[0]:
        raise ValueError('ambiguous iPhone 17 simulators')
    if booted and booted != targets:
        raise ValueError('unexpected booted simulators')
    return targets[0]


ROOT = Path(__file__).resolve().parents[1]
_METADATA_FIELDS = {'build', 'nativeArtifactVersions', 'connectionType', 'targetOS', 'targetModel',
                    'JVM', 'hostOS', 'hostCPU', 'hostMemory', 'runnerImage', 'runnerLabels'}


def build_manifest(platform: str, target: str, environment: dict, fixture_revision: str,
                   timeout_ms: int = 20000) -> dict:
    if platform not in {'android', 'ios'}:
        raise ValueError('platform must be android or ios')
    if not isinstance(target, str) or not target.strip():
        raise ValueError('target must identify one device')
    if not isinstance(fixture_revision, str) or not fixture_revision.strip():
        raise ValueError('fixture revision is required')
    if isinstance(timeout_ms, bool) or not isinstance(timeout_ms, int) or timeout_ms <= 0:
        raise ValueError('timeout must be positive')
    result = json.loads((ROOT / 'benchmarks' / 'manifests' / f'{platform}.json').read_text())
    result.update(target=target, fixtureRevision=fixture_revision)
    for name in _METADATA_FIELDS:
        result['environment'][name] = environment.get(name, {'unavailableReason': f'{name} unavailable on this runner'})
    result['processEnvironment'] = {'VISIONTEST_TRACE_PERFORMANCE': ''}
    if platform == 'ios':
        result['processEnvironment']['VISION_TEST_IOS_PROJECT_PATH'] = str(ROOT / 'ios-automation-server/IOSAutomationServer.xcodeproj')
    for case in result['cases']:
        case['timeoutMs'] = timeout_ms
    with tempfile.TemporaryDirectory() as directory:
        path = Path(directory) / 'manifest.json'
        path.write_text(json.dumps(result))
        return load_manifest(path)


def _run(argv: list[str]) -> str:
    result = subprocess.run(argv, cwd=ROOT, text=True, capture_output=True, check=True,
                            timeout=30, env={**os.environ, 'VISIONTEST_TRACE_PERFORMANCE': ''})
    return (result.stdout + result.stderr).strip()


def _optional(argv: list[str]) -> dict:
    try:
        value = _run(argv)
        if value:
            return {'value': value}
    except (OSError, subprocess.SubprocessError):
        pass
    return {'unavailableReason': f'{argv[0]} metadata unavailable on this runner'}


def _artifact(relative: str) -> dict:
    path = ROOT / relative
    if not path.is_file() or path.stat().st_size == 0:
        raise ValueError(f'missing or empty built artifact: {relative}')
    digest = hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(block)
    return {'path': relative, 'sha256': digest.hexdigest()}


def _ios_devices() -> dict:
    return json.loads(_run(['xcrun', 'simctl', 'list', 'devices', 'available', '-j']))


def _target(platform: str) -> tuple[str, dict]:
    if platform == 'android':
        devices = []
        for line in _run(['adb', 'devices']).splitlines():
            fields = line.split()
            if len(fields) >= 2 and fields[1] == 'device':
                devices.append(fields[0])
        if len(devices) != 1:
            raise ValueError('expected exactly one online Android target')
        target = devices[0]
        return target, {
            'targetOS': _optional(['adb', '-s', target, 'shell', 'getprop', 'ro.build.version.release']),
            'targetModel': _optional(['adb', '-s', target, 'shell', 'getprop', 'ro.product.model']),
            'connectionType': {'value': 'emulator' if target.startswith('emulator-') else
                               'tcp' if ':' in target else 'usb'},
        }
    booted = [(runtime, device) for runtime, entries in _ios_devices().get('devices', {}).items()
              for device in entries if device.get('state') == 'Booted']
    if len(booted) != 1 or booted[0][1].get('isAvailable') is not True:
        raise ValueError('expected exactly one available booted iOS target')
    runtime, device = booted[0]
    target = device.get('udid')
    if not isinstance(target, str) or not target:
        raise ValueError('booted iOS target has no UDID')
    return target, {'targetOS': {'value': runtime},
                    'targetModel': {'value': device['name']} if device.get('name') else
                                   {'unavailableReason': 'simulator model unavailable'},
                    'connectionType': {'value': 'simulator'}}


def _environment(platform: str, target_metadata: dict, template: dict) -> tuple[dict, str]:
    source_sha = _run(['git', 'rev-parse', 'HEAD'])
    fixture_revision = _run(['git', 'rev-parse', f'HEAD:benchmarks/fixtures/{platform}'])
    if not source_sha or not fixture_revision:
        raise ValueError('cannot resolve source and fixture revisions')
    jar = _artifact('app/build/libs/visiontest.jar')
    fixture_path = template['fixtureArtifact']
    if platform == 'android':
        fixture = _artifact(fixture_path)
        native = [_artifact('automation-server/build/outputs/apk/debug/automation-server-debug.apk'),
                  _artifact('automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk')]
    else:
        fixture = _artifact(f'{fixture_path}/BenchmarkFixture')
        project = ROOT / 'ios-automation-server/IOSAutomationServer.xcodeproj/project.pbxproj'
        if not project.is_file():
            raise ValueError('missing native iOS project')
        products = ROOT / 'test-output/benchmark-native/Build/Products/Debug-iphonesimulator'
        binaries = sorted(path for path in products.rglob('IOSAutomationServerUITests')
                          if path.is_file() and path.parent.name == 'IOSAutomationServerUITests.xctest')
        if len(binaries) != 1:
            raise ValueError('expected exactly one built IOSAutomationServerUITests binary')
        native = [_artifact(str(binaries[0].relative_to(ROOT)))]
    try:
        memory = {'value': os.sysconf('SC_PHYS_PAGES') * os.sysconf('SC_PAGE_SIZE')}
    except (ValueError, OSError, AttributeError):
        memory = _optional(['sysctl', '-n', 'hw.memsize']) if sys.platform == 'darwin' else {
            'unavailableReason': 'physical memory unavailable on this runner'}
    runner_image = {name: os.environ[name] for name in ('ImageOS', 'ImageVersion') if os.environ.get(name)}
    runner_labels = {name: os.environ[name] for name in ('RUNNER_OS', 'RUNNER_ARCH', 'RUNNER_ENVIRONMENT')
                     if os.environ.get(name)}
    return {
        **target_metadata,
        'build': {'value': {'sourceSHA': source_sha, 'jar': jar, 'fixture': fixture}},
        'nativeArtifactVersions': {'value': native},
        'JVM': _optional(['java', '-version']),
        'hostOS': {'value': {'system': host_platform.system(), 'release': host_platform.release(),
                             'version': host_platform.version()}},
        'hostCPU': {'value': {'architecture': host_platform.machine(), 'logicalCores': os.cpu_count()}},
        'hostMemory': memory,
        'runnerImage': {'value': runner_image} if runner_image else {'unavailableReason': 'runner image fields unavailable'},
        'runnerLabels': {'value': runner_labels} if runner_labels else {'unavailableReason': 'runner label fields unavailable'},
    }, fixture_revision


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    commands.add_parser('ios-target', help='print the newest available iPhone 17 UDID without booting')
    write = commands.add_parser('write', help='write a fresh manifest for already built artifacts')
    write.add_argument('--platform', choices=('android', 'ios'), required=True)
    write.add_argument('--output', type=Path, required=True)
    try:
        args = parser.parse_args(argv)
    except SystemExit as error:
        return int(error.code)
    try:
        if args.command == 'ios-target':
            print(select_ios_target(_ios_devices()))
            return 0
        if args.output.exists() or args.output.is_symlink():
            raise ValueError('output manifest already exists')
        if args.output.name in {'', '.', '..'}:
            raise ValueError('output must name a fresh manifest file')
        target, target_metadata = _target(args.platform)
        template = json.loads((ROOT / 'benchmarks/manifests' / f'{args.platform}.json').read_text())
        environment, revision = _environment(args.platform, target_metadata, template)
        manifest = build_manifest(args.platform, target, environment, revision)
        args.output.parent.mkdir(parents=True, exist_ok=True)
        with args.output.open('x', encoding='utf-8') as destination:
            destination.write(json.dumps(manifest, indent=2) + '\n')
        return 0
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f'benchmark manifest setup failed: {error}', file=sys.stderr)
        return 2


if __name__ == '__main__':
    raise SystemExit(main())
