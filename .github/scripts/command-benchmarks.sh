#!/usr/bin/env bash
set -euo pipefail

usage() {
  echo 'Usage: command-benchmarks.sh android|ios cli|mcp warm|host-cold' >&2
  exit 2
}
[[ $# == 3 ]] || usage
case "$1" in android|ios) ;; *) usage ;; esac
case "$2" in cli|mcp) ;; *) usage ;; esac
case "$3" in warm|host-cold) ;; *) usage ;; esac

platform=$1
mode=$2
lifecycle=$3
evidence="test-output/ci-benchmarks/$platform-$mode-$lifecycle"
mkdir -p "$evidence"
native_pid=''
target=''
forwarded=false
cleanup() {
  local status=$?
  trap - EXIT INT TERM
  set +e
  if [[ "$platform" == android && -n "$native_pid" ]]; then
    adb -s "$target" logcat -d -t 500 > "$evidence/logcat.log" 2>&1
    adb -s "$target" shell am force-stop dev.visiontest.automationserver >> "$evidence/cleanup.log" 2>&1
    if [[ $? != 0 && $status == 0 ]]; then status=1; fi
  fi
  if [[ "$forwarded" == true ]]; then
    adb -s "$target" forward --remove tcp:9008 >> "$evidence/cleanup.log" 2>&1
    if [[ $? != 0 && $status == 0 ]]; then status=1; fi
  fi
  if [[ -n "$native_pid" ]]; then
    kill "$native_pid" 2>/dev/null
    for ((attempt=0; attempt<10; attempt++)); do
      kill -0 "$native_pid" 2>/dev/null || break
      sleep 1
    done
    if kill -0 "$native_pid" 2>/dev/null; then
      kill -KILL "$native_pid" 2>/dev/null
    fi
    wait "$native_pid" 2>/dev/null
    if [[ "$platform" == ios ]]; then
      xcrun simctl shutdown "$target" >> "$evidence/cleanup.log" 2>&1
      if [[ $? != 0 && $status == 0 ]]; then status=1; fi
    fi
  fi
  printf '%s\n' "$status" > "$evidence/lifecycle-exit-code.txt"
  exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

export VISIONTEST_TRACE_PERFORMANCE=''
python3 -B -m benchmarks.ci_manifest write --platform "$platform" --output "$evidence/manifest.json"
target=$(python3 -c 'import json,sys; print(json.load(open(sys.argv[1]))["target"])' "$evidence/manifest.json")
if [[ "$platform" == android ]]; then
  adb -s "$target" install -r automation-server/build/outputs/apk/debug/automation-server-debug.apk
  adb -s "$target" install -r automation-server/build/outputs/apk/androidTest/debug/automation-server-debug-androidTest.apk
  adb -s "$target" forward tcp:9008 tcp:9008
  forwarded=true
  adb -s "$target" shell am instrument -w -e port 9008 \
    -e class 'dev.visiontest.automationserver.AutomationServerTest#runAutomationServer' \
    dev.visiontest.automationserver.test/dev.visiontest.automationserver.AutomationInstrumentationRunner \
    > "$evidence/native-server.log" 2>&1 &
  native_pid=$!
  port=9008
else
  export VISION_TEST_IOS_PROJECT_PATH="$PWD/ios-automation-server/IOSAutomationServer.xcodeproj"
  export TEST_RUNNER_PORT=9009
  xcodebuild test-without-building \
    -project ios-automation-server/IOSAutomationServer.xcodeproj \
    -scheme IOSAutomationServer \
    -destination "platform=iOS Simulator,id=$target" \
    -derivedDataPath test-output/benchmark-native \
    -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testRunAutomationServer \
    > "$evidence/native-server.log" 2>&1 &
  native_pid=$!
  port=9009
fi

healthy=false
for ((attempt=0; attempt<120; attempt++)); do
  if ! kill -0 "$native_pid" 2>/dev/null; then
    echo 'Native server process exited before readiness.'
    exit 1
  fi
  if curl --fail --silent --show-error --connect-timeout 1 --max-time 2 \
      "http://localhost:$port/health" > "$evidence/health.json" 2>> "$evidence/health.log"; then
    healthy=true
    break
  fi
  sleep 2
done
if [[ "$healthy" != true ]]; then
  echo 'Native server readiness deadline expired; startup will not be replayed.'
  exit 1
fi

VISIONTEST_BENCHMARK_SMOKE=1 VISIONTEST_BENCHMARK_MANIFEST="$PWD/$evidence/manifest.json" \
  python3 -B -m unittest benchmarks.tests.test_fixture_smoke -q
python3 -B -m benchmarks.run commands --manifest "$evidence/manifest.json" \
  --output "$evidence/run-{run_id}" --mode "$mode" --lifecycle "$lifecycle" \
  --warmup 5 --samples 30 --tracing paired
