# Compact inspection evaluation

Compact inspection reduces observation payloads while retaining the selectors and
state checked by these fixtures. This evaluation measures serialized bytes and
estimated text tokens, plus a small live iOS functional check. It does not measure
agent reasoning, provider token usage, cost, or whole-agent completion time.

## Offline production projection

The eight [versioned synthetic fixtures](../../benchmarks/compact-inspection/v1/)
cover form state, navigation, duplicate labels and acquisition failure on Android
and iOS. `CompactInspectionEvaluationTest` feeds their result objects through the
production `CompactInspectionOptions` projector using a JSON-RPC envelope. It
checks every expected target field, element counts, and unchanged failure objects,
then exports the full and compact JSON strings. These are projection assertions,
not executed native actions. No filtering or bounds option is applied.

The [payload artifact](../../benchmarks/compact-inspection/results/offline-payloads.json)
and [metrics artifact](../../benchmarks/compact-inspection/results/offline.json)
use UTF-8 bytes and **tiktoken 0.12.0 / o200k_base** on the exact exported strings,
without reserialization. Full payloads are minified result objects; RPC framing,
CLI newlines, and model message wrappers are excluded.

| Fixture | Full bytes | Compact bytes | Full estimated tokens | Compact estimated tokens |
| --- | ---: | ---: | ---: | ---: |
| Android ambiguous | 789 | 599 | 209 | 151 |
| Android failure | 81 | 81 | 18 | 18 |
| Android form | 847 | 650 | 228 | 160 |
| Android navigation | 563 | 478 | 153 | 117 |
| iOS ambiguous | 525 | 326 | 149 | 85 |
| iOS failure | 81 | 81 | 18 | 18 |
| iOS form | 609 | 414 | 171 | 101 |
| iOS navigation | 389 | 298 | 113 | 73 |

These small successful fixtures reduce bytes by 15.1–37.9%; error payloads remain
unchanged. Results depend on screen contents, native attributes and chosen options.
Compact output adds envelope metadata, so reduction is not guaranteed for every
observation. The fixture expectations do not establish native-tree uniqueness.

Reproduce from the repository root; the Python environment is optional and adds
no dependency to the project:

```bash
./gradlew :app:test --tests '*CompactInspectionEvaluationTest' --rerun-tasks
python3 -m venv /tmp/visiontest-compact-tokenizer
/tmp/visiontest-compact-tokenizer/bin/pip install tiktoken==0.12.0
PYTHONDONTWRITEBYTECODE=1 /tmp/visiontest-compact-tokenizer/bin/python \
  benchmarks/compact_report.py \
  app/build/reports/compact-inspection/payloads.json \
  --encoding o200k_base --output /tmp/compact-inspection-metrics.json
PYTHONDONTWRITEBYTECODE=1 python3 -m unittest \
  benchmarks.tests.test_compact_report benchmarks.tests.test_compact_collection -v
```

The tokenizer downloads its encoding on first use. For byte-only accounting, run
`compact_report.py` with system Python and omit `--encoding`; token fields are
null. Gradle's rerun flag ensures the exporter executes even after previous tests.

## Live iOS functional check

The [live artifact](../../benchmarks/compact-inspection/results/ios-live.json)
records revision `0fef718213f994d8e1b4e2061def48070b993756`, JAR and fixture SHA-256,
host, simulator UDID, iOS 26.5 runtime, parsed stdout, stderr, exit status, byte/token
counts and elapsed time for every attempted sample. The native server stayed warm;
each CLI command started a fresh JVM. Setup launches and readiness polling were
outside timing. Full preceded compact, with a fixture reset before each mode.
There was **one pair per case**, no warmup series, and no statistical latency test.

Each mode executed four inspection commands and four flow commands. Each mode's
flows attempted seven steps (two each for form, navigation and ambiguous; one for
missing). This is CLI/step accounting; native RPC/poll counts were not instrumented.
The same scripted selectors were used in both modes, with no agent selecting them.
Manual inspection of the archived reports established:

- Form: `agent@example.test` was entered and retained in the final observation;
  input and explicit wait succeeded in both modes.
- Navigation: tapping `continue` and waiting for `done` succeeded in both modes.
- Ambiguous labels: both distinct Continue resource IDs remained visible; tapping
  `continue_secondary` and waiting for it succeeded in both modes.
- Missing target: waiting for `never-present` returned the expected exit 1 and
  `success:false`, with interactive-element failure evidence in both modes.

All eight initial inspections returned matching element counts and resource IDs
within each full/compact pair. Six flows succeeded; two intentionally failed on
the missing-target case. There were no unexpected failures in this corrected matrix.

| Case | Inspection tokens full / compact | Flow report tokens full / compact | Inspection ms full / compact | Flow ms full / compact |
| --- | ---: | ---: | ---: | ---: |
| Form | 318 / 178 | 2209 / 1193 | 282.589 / 397.350 | 2686.501 / 2169.880 |
| Navigation | 318 / 178 | 292 / 258 | 319.713 / 311.633 | 1188.731 / 1137.542 |
| Ambiguous | 370 / 209 | 559 / 399 | 282.219 / 296.037 | 1499.468 / 1430.092 |
| Missing | 192 / 115 | 381 / 305 | 282.174 / 257.293 | 776.909 / 758.852 |

Tokens here are tiktoken 0.12.0 / o200k_base estimates of captured stdout, including
its trailing newline. Flow totals include action results and report metadata, not
only observations. The artifact stores parsed stdout rather than original strings;
byte/token counts were taken before parsing. Timing includes process startup and
teardown and is shown to retain the observations, not as evidence of speed gains.
Compact projection does not reduce native acquisition work.

The [pilot artifact](../../benchmarks/compact-inspection/results/ios-pilot.json)
is retained separately, with all eight attempts. Its form flow used the invalid
`input_text --resource-id` flag and exited 2 in both modes; the corrected matrix
uses `--target-resource-id`. Pilot failures are not folded into the corrected
matrix or silently discarded. Before collection, the fixture crashed on iOS 27
because UIKit required scene lifecycle support; collection moved to iOS 26.5
without changing native code. This setup failure has no timed sample in either
artifact. No Android live device was available. MCP transport, live region/type
filters and bounds inclusion were not measured by this matrix; automated tests
cover their contracts. No agent/provider usage was available.

## Reproduce the live check

Follow the [fixture guide](../../benchmarks/fixtures/README.md) and
[build/server setup](../../benchmarks/README.md#build-native-artifacts-and-fixtures)
using an isolated booted iOS 26.5 simulator. Build the fixture into the documented
`.derivedData` directory, install it, build the current JAR, and start the native
server on localhost port 9009. Do not run other automation on that simulator.

```bash
./gradlew :app:shadowJar
# Set this to your isolated, booted iOS 26.5 simulator's UDID.
compact_udid=REPLACE_WITH_SIMULATOR_UDID
xcrun simctl install "$compact_udid" \
  benchmarks/fixtures/ios/.derivedData/Build/Products/Debug-iphonesimulator/BenchmarkFixture.app
java -jar app/build/libs/visiontest.jar start_automation_server -p ios
java -jar app/build/libs/visiontest.jar automation_server_status -p ios
PYTHONDONTWRITEBYTECODE=1 /tmp/visiontest-compact-tokenizer/bin/python \
  benchmarks/compact-inspection/collect_ios.py \
  --udid "$compact_udid" --runtime 'iOS 26.5' \
  --output test-output/compact-inspection-new
java -jar app/build/libs/visiontest.jar stop_automation_server -p ios
```

The [collector](../../benchmarks/compact-inspection/collect_ios.py) reproduces the
corrected command matrix with configurable UDID, runtime label and a fresh output
directory. The runtime label is supplied metadata; verify it against `simctl`.
The archived runs used the original local collector; this portable adaptation
changes configuration paths and arguments and has mocked process/HTTP tests,
but has not itself been rerun against a device.
The current portable collector retains setup exceptions as failed attempts and
continues through the matrix. Command records retain raw stdout, stderr, exit
status, byte/token counts and nullable parsed stdout independently of JSON parsing;
parse failures have explicit error fields. Process timeouts retain available partial
output and a null exit status. Timeout bytes are decoded as UTF-8 with replacement
for incomplete or invalid sequences, so their counts describe that retained text.
These additional diagnostics do not change the historical archives. The collector
prints command failures and exit statuses but is a collection script, not an
automated outcome assertion gate: inspect every result against the outcomes above.
Do not overwrite old runs. Pilot reproduction uses the same
matrix with the historical invalid form flag, only if that failure is needed.
