# Agent initialization

## Purpose

Agent initialization installs VisionTest usage instructions into project-local skill locations for supported coding agents without coupling project configuration to binary installation or network access.

## Requirements

### Requirement: Init maps supported agent names to project-local paths

`visiontest init` MUST support `claude`, `opencode`, and `codex` and SHALL map them respectively to `.claude/skills/visiontest/SKILL.md`, `.opencode/skills/visiontest/SKILL.md`, and `.agents/skills/visiontest/SKILL.md` beneath the process current working directory.

#### Scenario: One supported agent

- **Given** the current working directory is a project and `--agent claude` is supplied
- **When** initialization runs
- **Then** it SHALL create parent directories as needed and write `.claude/skills/visiontest/SKILL.md`

#### Scenario: Multiple supported agents

- **Given** `--agent claude,opencode,codex` is supplied
- **When** initialization runs
- **Then** it SHALL write all three mapped files and report each path plus a summary count

### Requirement: Agent selection is comma-separated and validated as a whole

The required `--agent` option MUST split on commas and trim surrounding whitespace from each value. Every trimmed value MUST be nonblank and present in the supported mapping before any file is written.

#### Scenario: Whitespace around names

- **Given** supported names contain surrounding spaces after comma splitting
- **When** values are validated
- **Then** the trimmed names SHALL select their normal mapped paths

#### Scenario: Unknown or blank name

- **Given** any requested value is unknown or blank
- **When** validation occurs
- **Then** initialization MUST write no files, MUST list the validation problem and valid names on stderr, and MUST exit with code 2

#### Scenario: Missing option

- **Given** `visiontest init` is invoked without `--agent`
- **When** Clikt parses the command
- **Then** it SHALL report a missing required option and exit with code 2

### Requirement: Instructions are embedded for offline use

The build MUST package
`app/src/main/resources/agent-instructions.md` as a classpath resource with the
same name. Init MUST load that resource locally and prepend YAML frontmatter
containing `name: visiontest` and an activation-oriented description, followed
by a generated-by line that identifies the runtime JAR `Implementation-Version`;
it MUST NOT fetch instructions from the network. The embedded body MUST be
self-contained and MUST NOT depend on paths in the VisionTest source repository.

#### Scenario: Installed JAR initializes offline

- **Given** the fat JAR contains `agent-instructions.md` and no network is available
- **When** init writes a supported agent skill
- **Then** the generated file SHALL contain the hardcoded frontmatter followed by the embedded instruction body

#### Scenario: Generated skill identifies its source version

- **Given** the CLI runs from a packaged JAR with `Implementation-Version` set
- **When** init writes a supported agent skill
- **Then** the generated file SHALL state that version between its frontmatter and instructions

#### Scenario: Embedded resource is absent

- **Given** the runtime classpath does not contain `agent-instructions.md`
- **When** init tries to load it
- **Then** it MUST fail before writing files with a generic failure message and exit code 1

### Requirement: Reinitialization overwrites deterministically

Init MUST overwrite an existing mapped `SKILL.md` with the complete current generated content. Repeating the same invocation against unchanged embedded instructions MUST produce identical file content.

#### Scenario: Existing skill file

- **Given** a mapped skill file already exists
- **When** the same agent is initialized again
- **Then** the file SHALL be replaced without prompting and its content SHALL equal a fresh initialization

### Requirement: Init has no device-platform dependency

The `init` command MUST NOT define or require `--platform`, instantiate device components, contact an automation server, or require a connected device.

#### Scenario: Init without platform

- **Given** a supported `--agent` value and a writable project directory
- **When** `visiontest init` runs without `--platform`
- **Then** it SHALL perform project-local initialization normally

## Usage guidance

The embedded skill MUST describe only the CLI workflow. MCP setup and usage
remain in the product documentation, not in the distributed skill. Performance
tracing guidance likewise belongs in the performance documentation. Keep the
skill focused on routine testing, with no dependency on those external references.
CLI examples MUST distinguish Android APK installation from iOS bundle setup.
Require checking operation results and expected app state rather than assuming a
successful call proves app behavior. Claims about properties unavailable through
CLI queries require inspecting image evidence.

The guidance MUST prioritize targeted element queries for known selectors and
filtered element discovery for unfamiliar screens. Built-in waiting and targeted
interactions SHOULD replace manual polling and redundant lookup/wait/action
sequences. Full hierarchy inspection SHALL be reserved for information missing
from narrower queries. Screenshots SHALL be restricted to documenting observed
app bugs or assertions that available CLI queries cannot establish. Guidance MUST
reject routine before/after and per-step screenshots, including flow screenshot
steps and observation options. A generic evidence request, report template or
"visual verification" label MUST NOT justify an image when structured results
already answer the actual assertion. Agents SHALL identify the bug or unresolved
fact before capture, capture a UI bug's failing state before recovery when possible,
and retain structured evidence if capture fails. Guidance MUST distinguish element
presence from onscreen visibility and bounds from proof against occlusion.
Failure evidence SHOULD prefer compact interactive elements with the outer iOS
app scope. `--on-failure screenshot` MUST NOT be a generic default; guidance SHALL
require a known UI bug reproduction or an assertion needing image evidence to
justify it before execution. A functional wait timeout alone SHALL NOT qualify.
Output reduction MUST preserve operation outcomes and errors and MUST NOT treat
a partial view as proof of absence. Token and latency savings require measurement.

When accessibility data identifies an offscreen target and its scrollable region,
the guidance SHALL permit scrolling without a preliminary screenshot. It MUST
distinguish tree presence from actionability and Android's visible-element
filtering from iOS snapshot inclusion. Element swipes SHALL target the visible
container; they MUST NOT be described as automatic scroll-until-found operations.
Agents SHALL verify progress and bound repeated gestures.

The guidance SHALL focus execution on the requested assertions and reuse verified
session setup until a relevant change or failure invalidates it. A reused app
build MUST contain the code under test. Intermediate checks MAY be omitted when
a specific downstream assertion covers the same requirement; requested checks
and test isolation MUST be preserved. Actions on one device SHALL be serialized.
Guidance MUST separate batching from recording. Known exploratory actions SHALL
use `flow --json` without `--record`, ending at decisions requiring new UI
information. Setup, discovery and recovery SHALL remain unrecorded. If no
destination selector is known, a final structured observation SHALL guide the
next decision without treating action success as proof of the app outcome.
Recording SHALL begin only for a requested test whose starting state, complete
action sequence, selectors, test data and expected assertions are established.
Agents SHALL identify the scenario being verified and use one recorded flow
through its final outcome wait, rather than videos per action or exploratory
segment. Before EVERY recorded flow, agents MUST terminate the app, inspect that
result, relaunch it and inspect the launch result. This includes first executions,
consecutive recorded tests and explicit reruns; an already-ready screen or reusable
app state MUST NOT waive restart. A healthy automation server SHALL remain running.
Navigation from the fresh launch to the test state SHALL be included in the recorded
scenario. Restart MUST NOT be described as clearing persisted data. Unrecorded
exploratory flows SHALL NOT require automatic restart. A defined scenario MAY be
recorded on its first execution; a rehearsal
or prior successful run MUST NOT be required. Unexpected UI requiring discovery
SHALL return the agent to unrecorded exploration, not successive recorded fragments.
Standalone actions SHALL be limited to exploration, recovery or operations
unsupported by flow. Completed checks MUST NOT be replayed just to use flow or
obtain a cleaner recording; pending tests MAY require restoring the starting state.
Before recording, agents SHALL review the final steps, selectors, assertions and
budgets, reading a saved flow after its last edit, including scripted edits.
Requested assertions MUST be preserved; a removed readiness check SHALL be covered
elsewhere. Scenario assertion timeouts SHALL be explicit and justified by test
requirements and evidence. An established exploratory timeout MUST NOT silently
become the default when copied into a flow; overall budgets SHALL NOT be described
as extending element waits. Failed waits MUST NOT trigger arbitrary timeout
increases or unchanged retries solely for recording. Contradictory prerequisites
SHALL be resolved before recording. Missing prerequisites block a test; an unmet
assertion with valid prerequisites fails it. Expected outcomes MUST NOT be replaced
with observed outcomes merely to pass.
Retries SHALL follow new evidence or a corrective action. Failed prerequisites
SHALL block dependent steps rather than produce misleading additional failures.

The guidance MUST reuse a healthy, compatible automation server on the same
target across consecutive tests and retries. Server cleanup SHALL happen at the end
of the requested batch or testing task, not after each test, and SHALL stop only
a server started by the agent unless the user asks to leave it running.
Servers already running before the session SHALL remain active.

## Verification

- Production command and resource packaging: `app/src/main/kotlin/dev/visiontest/cli/commands/InitCommand.kt`, `app/src/main/kotlin/dev/visiontest/cli/VisionTestCli.kt`, `app/src/main/resources/agent-instructions.md`
- Executable tests: `app/src/test/kotlin/dev/visiontest/cli/InitCommandTest.kt`, `app/src/test/kotlin/dev/visiontest/cli/InitCommandE2ETest.kt`, `app/src/test/kotlin/dev/visiontest/cli/VisionTestCliTest.kt`

## Native lifecycle and evidence guidance

The embedded skill SHALL lead with the routine CLI workflow and follow the
application repository's build/install instructions, including artifact, variant,
simulator build and configuration. It SHALL contain an offline-usable native
Android/iOS reference for opening URLs, terminating apps, clearing Android app
data and uninstalling apps. Native commands SHALL use the chosen serial/UDID
explicitly, with the established package/bundle ID, and explain argument quoting,
exit status and diagnostics. Android URL examples SHALL preserve quoting through
both host and device shells. Accepted launch requests MUST be followed by an
application outcome check. Deep links MUST NOT bypass navigation under test.

Reset/uninstall SHALL be scenario-specific. iOS uninstall with project-guided
reinstall MUST NOT be described as equivalent to Android data clearing or as a
complete reset of Keychain, shared or remote state. Guidance MUST retain the
single-target limitation and MUST NOT claim backend identity verification.

The skill SHALL explicitly replace fixed UI sleeps and agent-written polling
with built-in selector waits and `wait_for_element` for supported conditions.
This rule MUST appear in the initial workflow and explicitly cover app startup,
navigation, swipes, animations and loading. It MUST prohibit emitting `sleep 0`
as a placeholder or separator as well as positive-duration sleeps.
The skill SHALL demonstrate launching
an app, inspecting the launch result and awaiting a known ready selector without
a fixed delay. If no ready selector is known, agents SHALL immediately use compact
discovery and derive a condition from observed selectors, or report the unsupported
readiness check rather than invent a selector, sleep or manually poll. A targeted
action's own actionability wait SHOULD avoid a redundant preliminary wait.
Dependent standalone commands MUST NOT be blindly chained with `;` or rely on
`&&` alone, since normally returned operation failures may retain exit 0.
The guidance SHALL demonstrate an unrecorded exploratory swipe followed by a
known selector tap without a delay, and discovery after the swipe when the target
is unknown. It SHALL clarify that display-wide `swipe_direction` and `screenshot`
do not accept `--bundle-id`; scoped element actions retain it. This restriction
SHALL accompany the main selector guidance, explicitly stating that `--bundle-id`
is command-specific rather than a global iOS option. Agents SHALL check options
against the exact command and consult its help when uncertain.
It SHALL distinguish immediate lookup, target actionability and subsequent app
outcomes, without claiming all operations wait or that internal polling is absent.
Structured find/wait results, including flow waits, SHALL count as functional UI
evidence without repeating checks or taking screenshots to confirm them.

The skill SHALL show native app termination after the final requested flow when
the app should be closed and no pending test needs its state. Termination SHALL
follow flow process completion, recording finalization, result inspection and
necessary failure evidence collection. It MUST NOT be presented as a flow step
or nonexistent CLI command. Examples SHALL use the established serial/UDID and
report native cleanup failures separately from test results. A completed UI wait
alone MUST NOT trigger termination while the recording is still being finalized.
Guidance SHALL show the mandatory termination, result inspection, separate launch
and recorded flow sequence in its main recorded-test example. Only successful
termination or a specific diagnostic confirming an already-stopped app SHALL
permit launch; other native failures MUST NOT be suppressed. A returned launch
failure MUST block the recorded flow even with exit 0. An explicit rerun request
SHALL count as a new pending test even if the previous execution passed.
Agents SHALL reuse established selectors and
assertions, use actionability waits or an initial known condition inside the flow,
and rediscover only when new evidence invalidates the known path. Restart alone
MUST NOT trigger sleeps, repeated discovery or redundant waits before gestures.
After new exploration, agents MUST restart again before a recorded test. A prior
flow's recording finalization and necessary failure evidence collection SHALL
finish before termination for the next test.

Agent guidance MUST require `--record` for complete, defined scenarios that still
need testing. The CLI flag remains opt-in. Unrecorded exploration SHALL be normal,
not a recording omission. The skill SHALL limit exceptions for ready tests to user
opt-out, concrete recorder limitations, unsupported flow operations and incompatible
time budgets, with the specific reason reported before unrecorded execution.
Partial recordings MUST NOT be presented as complete scenario evidence, and
assertions MUST NOT be omitted to fit recording budgets. Convenience or a generic
desire for speed MUST NOT qualify. Guidance SHALL preserve recording failures rather
than silently retrying unrecorded or dropping the requirement. Video SHALL NOT
be treated as automatic verification or require routine video analysis. Completed
actions MUST NOT be replayed solely to repair evidence collection.

Guidance SHALL retain stdout results, stderr diagnostics and command exit status.
It MUST NOT recommend filtering VisionTest output with `grep -v INFO` or discarding
stderr. Returned operation errors and uncertain action outcomes remain significant.
Extracted label matches MUST NOT substitute for selector assertions or inspection
of complete structured results and errors.
