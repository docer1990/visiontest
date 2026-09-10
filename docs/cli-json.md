# CLI JSON output

Add `--json` to `get_interactive_elements`, `get_device_info`, `find_element`,
`list_apps`, `info_app`, or `available_device`. Every command requires
`--platform android|ios` (`-p`). Each successful invocation writes one JSON object and a
newline to stdout. Diagnostics use stderr. Without `--json`, existing text
output is preserved.

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
