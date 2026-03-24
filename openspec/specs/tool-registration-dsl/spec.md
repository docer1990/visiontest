## ADDED Requirements

### Requirement: ToolScope absorbs tool registration boilerplate
The `ToolScope` class SHALL wrap `Server.addTool()` with automatic timeout enforcement, error handling via `ErrorHandler.handleToolError()`, and `CallToolResult` wrapping. Tool handlers SHALL only provide the business logic as a `suspend (CallToolRequest?) -> String` lambda.

#### Scenario: Tool registered via ToolScope executes within timeout
- **WHEN** a tool is registered via `ToolScope.tool()` with a 10s timeout and the handler returns in 5s
- **THEN** the tool SHALL return a `CallToolResult` containing a single `TextContent` with the handler's return value

#### Scenario: Tool registered via ToolScope times out
- **WHEN** a tool is registered via `ToolScope.tool()` with a 10s timeout and the handler takes longer than 10s
- **THEN** the tool SHALL return an error `CallToolResult` produced by `ErrorHandler.handleToolError()` with a `TimeoutCancellationException`

#### Scenario: Tool registered via ToolScope handles exceptions
- **WHEN** a tool handler throws any `Exception`
- **THEN** the tool SHALL return an error `CallToolResult` produced by `ErrorHandler.handleToolError()` with the thrown exception and the tool name as context

#### Scenario: Tool registered with custom timeout
- **WHEN** a tool is registered with `timeoutMs = 30000`
- **THEN** the tool SHALL use 30s as its timeout instead of the default

### Requirement: ToolRegistrar interface for modular registration
Each platform tool group SHALL implement the `ToolRegistrar` interface with a single `registerTools(scope: ToolScope)` method. `ToolFactory.registerAllTools()` SHALL iterate over all registrars and delegate to each.

#### Scenario: All tools registered via registrars
- **WHEN** `ToolFactory.registerAllTools(server)` is called
- **THEN** all 36 MCP tools SHALL be registered on the server with identical names, descriptions, and input schemas as the current monolithic implementation

#### Scenario: Registrar receives ToolScope
- **WHEN** a `ToolRegistrar.registerTools(scope)` is called
- **THEN** the scope SHALL provide the server, logger, and default timeout configured in `ToolFactory`

### Requirement: CallToolRequest parameter extraction helpers
Extension functions on `CallToolRequest?` SHALL provide type-safe parameter extraction: `requireString(key)`, `requireInt(key)`, `optionalString(key)`, `optionalInt(key)`.

#### Scenario: requireString returns value when present
- **WHEN** `request.requireString("packageName")` is called and `packageName` exists in the request arguments
- **THEN** it SHALL return the string value

#### Scenario: requireString throws when missing
- **WHEN** `request.requireString("packageName")` is called and `packageName` is not in the request arguments
- **THEN** it SHALL throw `IllegalArgumentException` with a message containing the key name

#### Scenario: requireInt parses integer from string
- **WHEN** `request.requireInt("x")` is called and the argument value is `"100"`
- **THEN** it SHALL return `100` as an `Int`

#### Scenario: requireInt throws on non-integer
- **WHEN** `request.requireInt("x")` is called and the argument value is `"abc"`
- **THEN** it SHALL throw `IllegalArgumentException` with a message indicating the key must be an integer

#### Scenario: optionalString returns null when missing
- **WHEN** `request.optionalString("text")` is called and `text` is not in the request arguments
- **THEN** it SHALL return `null`

### Requirement: ToolHelpers object for pure utility functions
The functions `extractProperty`, `extractPattern`, and `formatAppInfo` SHALL be moved to a `ToolHelpers` object in the `tools/` package with identical behavior.

#### Scenario: extractProperty finds property value
- **WHEN** `ToolHelpers.extractProperty("[ro.product.model]: [Pixel 6]", "ro.product.model")` is called
- **THEN** it SHALL return `"Pixel 6"`

#### Scenario: extractProperty returns Unknown for missing property
- **WHEN** `ToolHelpers.extractProperty("", "ro.product.model")` is called
- **THEN** it SHALL return `"Unknown"`

#### Scenario: formatAppInfo extracts and formats app information
- **WHEN** `ToolHelpers.formatAppInfo(rawDumpsysOutput, "com.example.app")` is called with valid dumpsys output
- **THEN** it SHALL return a formatted string containing version name, version code, SDK targets, install dates, and up to 10 permissions

### Requirement: Four platform-specific registrars
The system SHALL provide exactly four `ToolRegistrar` implementations:
- `AndroidDeviceToolRegistrar` — registers 4 Android device management tools
- `AndroidAutomationToolRegistrar` — registers 14 Android UI automation tools
- `IOSDeviceToolRegistrar` — registers 4 iOS device management tools
- `IOSAutomationToolRegistrar` — registers 10 iOS UI automation tools plus server lifecycle management

#### Scenario: Android device tools registered
- **WHEN** `AndroidDeviceToolRegistrar.registerTools(scope)` is called
- **THEN** tools `available_device_android`, `list_apps_android`, `info_app_android`, `launch_app_android` SHALL be registered

#### Scenario: Android automation tools registered
- **WHEN** `AndroidAutomationToolRegistrar.registerTools(scope)` is called
- **THEN** all 14 Android automation tools SHALL be registered including `install_automation_server`, `start_automation_server`, `get_ui_hierarchy`, `find_element`, `android_tap_by_coordinates`, `android_swipe`, `android_swipe_direction`, `android_swipe_on_element`, `android_press_back`, `android_press_home`, `android_input_text`, `android_get_device_info`, `get_interactive_elements`, and `automation_server_status`

#### Scenario: iOS device tools registered
- **WHEN** `IOSDeviceToolRegistrar.registerTools(scope)` is called
- **THEN** tools `ios_available_device`, `ios_list_apps`, `ios_info_app`, `ios_launch_app` SHALL be registered

#### Scenario: iOS automation tools registered
- **WHEN** `IOSAutomationToolRegistrar.registerTools(scope)` is called
- **THEN** all 10 iOS automation tools SHALL be registered including `ios_start_automation_server`, `ios_automation_server_status`, `ios_get_ui_hierarchy`, `ios_get_interactive_elements`, `ios_tap_by_coordinates`, `ios_swipe`, `ios_swipe_direction`, `ios_find_element`, `ios_get_device_info`, `ios_press_home`, `ios_input_text`, and `ios_stop_automation_server`

### Requirement: ToolFactory remains the public entry point
`ToolFactory` SHALL maintain its existing constructor signature and `registerAllTools(server: Server)` method. `Main.kt` SHALL require zero changes.

#### Scenario: ToolFactory constructor compatibility
- **WHEN** `ToolFactory(android, ios, logger)` is constructed (using defaults for optional params)
- **THEN** it SHALL compile and function identically to the pre-refactor version

#### Scenario: registerAllTools delegates to registrars
- **WHEN** `toolFactory.registerAllTools(server)` is called
- **THEN** it SHALL create a `ToolScope` and pass it to each of the four registrars
