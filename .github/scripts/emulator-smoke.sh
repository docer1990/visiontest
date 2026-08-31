#!/usr/bin/env bash
#
# Smoke test for the Android automation server, run inside the emulator session
# created by reactivecircus/android-emulator-runner.
#
# Lives in its own file (rather than inline in the workflow's `script:` input)
# because that input is executed via `sh -c` -> dash on ubuntu-latest, which
# rejects `set -o pipefail`. The pipefail is load-bearing here: without it the
# `java ... | tee` pipelines would report tee's exit code and hide a CLI failure.
#
# Expects the workspace root as CWD, with the fat JAR and APKs already built.

set -euo pipefail

JAR=app/build/libs/visiontest.jar

echo "::group::install_automation_server"
java -jar "$JAR" install_automation_server --platform android | tee install.log
grep -q "installed successfully" install.log
echo "::endgroup::"

echo "::group::start_automation_server"
# start returns after a short health poll; on a cold emulator the
# instrumentation may need longer, so poll status ourselves below
# instead of retrying start (a retry would spawn a second
# instrumentation process).
java -jar "$JAR" start_automation_server --platform android | tee start.log || true
for i in $(seq 1 30); do
  if java -jar "$JAR" automation_server_status --platform android | grep -q "is running"; then
    echo "Automation server healthy after $i poll(s)"
    break
  fi
  if [ "$i" = 30 ]; then
    echo "Automation server did not become healthy"
    adb logcat -d -t 200 || true
    exit 1
  fi
  sleep 2
done
echo "::endgroup::"

echo "::group::JSON-RPC round-trips"
java -jar "$JAR" get_device_info --platform android
java -jar "$JAR" get_interactive_elements --platform android
java -jar "$JAR" tap_by_coordinates --platform android 200 400
java -jar "$JAR" screenshot --platform android --output smoke-screenshot.png
test -s smoke-screenshot.png
echo "::endgroup::"
