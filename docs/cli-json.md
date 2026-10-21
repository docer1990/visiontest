# CLI JSON output

Add `--json` to `get_interactive_elements`, `get_device_info`, `find_element`,
`list_apps`, `info_app`, or `available_device`. Every command requires
`--platform android|ios` (`-p`). Each successful invocation writes one JSON object and a
newline to stdout. Diagnostics use stderr. Without `--json`, existing text
output is preserved.

`flow --json` has its own execution-report contract, described below. Its exit
status also reflects returned operation failures.

## Flow reports

Preflight errors leave stdout empty. Once execution starts, `flow --json` emits
one complete object and a newline on success or failure. Diagnostics go to stderr.
There is no JSON-RPC framing and no image base64.

| Field | Type and meaning |
| --- | --- |
| `schemaVersion` | Integer, currently `1` |
| `platform` | `"android"` or `"ios"` |
| `success` | Boolean; all steps and requested final observations succeeded |
| `exitCode` | Integer, the process exit status |
| `steps` | Array of step records in submitted order |
| `failedStep` | Failed or uncertain step id, or null when no step failed |
| `skippedSteps` | Array of ids of steps not executed after failure |
| `observations` | Array of explicitly requested final observation records |
| `evidence` | Array of requested failure evidence records |
| `error` | Overall error object, or null on success |

Each step has `id` (`step-1`, `step-2`, ...), `command`, `status`, `certainty`,
`result`, and `error`. Status is `succeeded`, `failed`, `uncertain`, or `skipped`.
Certainty is `confirmed`, `unknown`, or `not_executed`. A confirmed failure does
not guarantee an action had no partial effect. A possibly dispatched action
without an established result has status `uncertain` and certainty `unknown`.
Skipped steps have null result/error and certainty `not_executed`.

Results are operation objects without transport framing. Action results require
boolean `success` and optional string `error`. Returned RPC errors appear as
`{"error":{"code":-32601,"message":"Unknown method"}}` and fail the flow.
Malformed responses fail rather than being treated as success or absence.

Wait results include the unwrapped element result plus `condition` (`appeared` or
`gone`) and boolean `satisfied`. Screenshot results are
`{"success":true,"path":"/absolute/path.png"}` or
`{"success":false,"error":"..."}`. No path is claimed on failure.

An error object contains string `type` and `message`. Exception types identify
the underlying failure; callers should use status, certainty and exitCode for
control flow rather than matching English messages.

Each observation record has `kind` (`screenshot`, `hierarchy`, or
`interactive-elements`), `status` (`succeeded`, `failed`, or `skipped`), `result`,
and `error`. Screenshot and hierarchy results contain `success` and an absolute
`path`. Interactive-element results follow the inspection schema below. Failed
final observations make the overall flow unsuccessful without changing successful
steps; `failedStep` remains null. Failure evidence errors do not change the
original overall error or exit code.

```json
{
  "schemaVersion": 1,
  "platform": "ios",
  "success": true,
  "exitCode": 0,
  "steps": [
    {
      "id": "step-1",
      "command": "press_home",
      "status": "succeeded",
      "certainty": "confirmed",
      "result": {"success": true},
      "error": null
    }
  ],
  "failedStep": null,
  "skippedSteps": [],
  "observations": [],
  "evidence": [],
  "error": null
}
```

Unlike individual commands, a returned `success:false` or RPC error exits 1.
Thrown failures retain mapped codes 1 through 5. Only a fully successful flow
exits 0. See [the flow guide](cli-flow.md) for timeout precedence, input grammar,
supported steps, evidence budgets and recovery.

## UI inspection

The CLI emits the automation server's `result` object without the JSON-RPC
envelope (`jsonrpc`, transport `id`, or the enclosing `result` key). Fields are
case-sensitive. Optional element fields can be absent when unavailable; callers
must tolerate additional fields. The platform-specific schemas below describe
the current contract. Every field without an optional qualifier is required,
including when `success` or `found` is false. The CLI validates these fields
before printing. Optional fields may be absent; when present, they must have
the documented type. JSON null is not a valid value for these typed inspection
fields. The CLI accepts integers in the signed 32-bit range
(-2147483648 through 2147483647). Strings are not coerced to numbers or booleans. Unknown additional
fields remain accepted and are preserved in the output.

| Command | Both platforms | Android additions | iOS additions |
| --- | --- | --- | --- |
| `get_device_info` | `success`: boolean; `displayWidth`, `displayHeight`, `displayRotation`: integers; `productName`: string; optional `error`: string | `sdkVersion`: integer | `osVersion`: string |
| `find_element` | `found`: boolean; optional strings `text`, `resourceId`, `className`, `contentDescription`, `bounds`, `error`; optional `isEnabled`: boolean | Optional `isClickable`: boolean | Optional `value`: string |
| `get_interactive_elements` | `success`: boolean; `count`: integer; `elements`: array of objects; optional `error`: string | See element properties below | See element properties below |

Each interactive element can contain string fields `text`, `resourceId`,
`className`, `contentDescription`, `bounds`; integer `centerX`, `centerY`; and
boolean `isEnabled`. These element properties are optional; each array entry
must be an object. Android also returns boolean `isClickable`, `isCheckable`,
`isScrollable`, `isLongClickable`. iOS can return string `value`.

On iOS, `resourceId` means accessibility identifier, `className` means element
type, and `contentDescription` means accessibility label. Bounds use
`[left,top][right,bottom]`. Coordinates use each platform's native automation
coordinate space; they are suitable for that platform's tap/swipe commands.

```bash
visiontest get_device_info -p android --json
# {"displayWidth":1080,"displayHeight":2400,"displayRotation":0,"productName":"example","sdkVersion":35,"success":true}

visiontest get_device_info -p ios --json
# {"displayWidth":402,"displayHeight":874,"displayRotation":0,"productName":"iPhone","osVersion":"26.5","success":true}

visiontest find_element -p android --text "Login" --json
# {"found":false}

visiontest find_element -p ios --resource-id login --bundle-id com.example.app --json
# {"found":true,"resourceId":"login","className":"Button","bounds":"[20,40][120,80]","isEnabled":true}

visiontest get_interactive_elements -p ios --json
# {"success":true,"elements":[],"count":0}
```

## App lists

`list_apps` returns `{"apps":[...]}`, containing Android package names or iOS
bundle IDs as strings. The empty result is `{"apps":[]}` on either platform.
Array order is not guaranteed.

```bash
visiontest list_apps -p android --json
# {"apps":["com.example.app"]}
visiontest list_apps -p ios --json
# {"apps":["com.apple.mobilesafari"]}
```

## App and device metadata

`info_app` returns the application ID and the backend's raw details in a string
field. Android details originate from package inspection; iOS details originate
from simulator app information. Treat raw details as opaque text: their internal
format is not a versioned VisionTest schema.

| Command | Fields |
| --- | --- |
| `info_app` | `id`: string; `rawInfo`: string |
| `available_device -p android` | `id`, `name`, `type`, `state`: strings; `modelName`, `osVersion`, `sdkVersion`: string or null |
| `available_device -p ios` | `id`, `name`, `type`, `state`: strings; `modelName`, `osVersion`: string or null |

`available_device` describes the first available device or simulator. It keeps
the existing selection policy; explicit selection is tracked separately in P6.
Device identity and platform metadata are strings, with unavailable optional
metadata represented as JSON null.

The current `type` values are `ANDROID`, `IOS_SIMULATOR`, and `IOS_DEVICE`.

```bash
visiontest info_app -p android com.example.app --json
# {"id":"com.example.app","rawInfo":"package inspection output"}

visiontest available_device -p ios --json
# {"id":"SIMULATOR-UDID","name":"iPhone 17","type":"IOS_SIMULATOR","state":"Booted","osVersion":"26.5","modelName":"iPhone 17"}
```

## Failures and scripting

Exit codes keep their existing meaning: 0 for a normally returned result, 1 for
a generic failure, 2 for invalid arguments, 3 for an unreachable automation
server, 4 for a missing device/simulator, and 5 for an unsupported platform.

An operation can return `found: false` or `success: false` and still exit 0.
Scripts must inspect these fields. Thrown failures write stderr and leave stdout
empty. Invalid JSON or an invalid response structure fails with code 1 instead
of emitting partial JSON. This includes missing required fields, incorrect field
types, and invalid nested element structures.

A valid JSON-RPC error is an alternative response for all three UI inspection
commands. The CLI emits `{"error":{"code":-32601,"message":"Unknown method"}}`
and exits 0, following the normally returned failure rule. `code` must be an
integer in the same signed 32-bit range and `message` a string. Optional `data` may contain any JSON value;
additional error fields are preserved. Transport framing is omitted. An error
that lacks either required field, has an incorrect type, or occurs alongside a
`result` member is invalid and fails with exit 1, stderr diagnostics, and empty
stdout. Scripts must check for this top-level error object before reading the
command's result fields.

```bash
visiontest find_element -p android --text "Login" --json | jq -e '.found == true'
visiontest get_interactive_elements -p ios --json | jq -e '.success == true'
```

Use your shell's pipeline failure handling (for example `set -o pipefail` in
Bash) when the script must also propagate VisionTest's nonzero exit status.

## Compact interactive elements

`get_interactive_elements --compact` emits one unwrapped JSON object with or
without `--json`. Existing full output remains the default when compact is absent.
For example:

```bash
visiontest get_interactive_elements -p android --compact
visiontest get_interactive_elements -p ios --bundle-id com.example.app \
  --compact --type Button --region 0,0,400,800 --include-bounds
```

`--type` matches native `className` exactly, without role inference. `--region`
accepts `x,y,width,height` and includes centers in a half-open rectangle: left/top
included, right/bottom excluded. Android units are display pixels; iOS units are
screen points. No scaling or orientation conversion occurs. Components are signed
32-bit integers; x/y must be nonnegative, width/height positive, and right/bottom
must not exceed 2147483647. Blank types, invalid regions, and modifiers without
`--compact` fail argument validation before device access. Every source element
must supply integer `centerX` and `centerY` for a region query; missing centers
fail explicitly even when another filter would exclude that element.

Successful `compact-v1` output has this shape (example values):

```json
{"success":true,"format":"compact-v1","count":1,"observedCount":4,"elements":[{"selector":{"resourceId":"save"},"label":"Save","type":"Button","enabled":true}]}
```

| Field | Meaning |
| --- | --- |
| `count` | Number returned after host filtering |
| `observedCount` | Native candidates before host filtering; not a total tree count |
| `selector` | One-key native action argument map: first nonblank `resourceId`, then `contentDescription`, then `text`; null if unavailable |
| `selectorMatches` | Only present when the selector matches more than one unfiltered observed element |
| `label` | Nonempty text, otherwise nonempty contentDescription; optional |
| `type` | Native className; optional |
| `contentDescription`, `value` | Distinct description and iOS value retained when different from other presentation fields |
| `enabled` | Observed isEnabled, when supplied |
| `clickable`, `checkable`, `scrollable`, `longClickable` | Observed Android capability booleans, when supplied |
| `bounds`, `centerX`, `centerY` | Supplied native geometry, only with `--include-bounds` |

Selector keys use existing camelCase action arguments for MCP. CLI equivalents
are `--resource-id`, `--content-description`, and `--text`; text-input target flags have
`--target-` prefixes. No selector is composed or invented. Duplicate counts use
native matching semantics (iOS text matches label OR value) before filtering.
Absence of `selectorMatches` is not proof of native-tree uniqueness or actionability;
actions resolve their targets again. An element with `selector:null` remains in
results and may need geometry or full inspection.

Nothing is truncated. Optional missing fields stay absent, equivalent presentation
fields and empty strings are omitted, and unknown native attributes remain in
full inspection. Omit filters to retrieve all native candidates; omit compact to
retrieve full detail. Source elements are validated before filtering. Returned
operation failures and RPC errors retain their unwrapped failure objects instead
of becoming empty successes. Standalone returned failures still exit zero; thrown
failures and flow failures retain their documented exit behavior.

MCP retains `get_interactive_elements` and `ios_get_interactive_elements`, adding
`compact`, `type`, `region` (the same string), and `includeBounds`. Existing
`includeDisabled` and iOS `bundleId` are retained. Flow applies these options to
requested interactive-element observations and failure evidence; see
[flow observations](cli-flow.md#observations). Payload measurements and their
limitations are recorded in the [compact evaluation](benchmarks/compact-inspection.md).
