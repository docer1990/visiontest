# Vision Test - MCP Server for Mobile Automation

A platform-agnostic interface for mobile automation that enables LLMs and agents to interact with native mobile applications and devices. Currently supports Android devices (emulators and physical), with iOS support planned.

## Overview

Vision Test is an MCP (Model Context Protocol) server that provides a standardized way for AI agents and Large Language Models to interact with mobile devices. This project allows for:

- Device detection and information retrieval
- Application management (listing, info retrieval, launching)
- Command execution on connected devices
- Scalable automation across multiple device types

## Features

- **Device Management**: Detect and interact with connected Android devices
- **App Management**: List installed apps, get detailed app information, and launch apps
- **Robust Error Handling**: Comprehensive exception framework with descriptive error messages and codes
- **Performance Optimizations**: Device list caching to reduce ADB command overhead
- **Retry Logic**: Automatic retries with exponential backoff for flaky device operations
- **Structured Responses**: Formatted, human-readable output for device and app information

## Prerequisites

- **JDK 11 or higher**
- **Kotlin 1.6+**
- **Android Platform Tools**: Contains the Android Debug Bridge (ADB) for device communication
   - [Download Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)
   - Add the platform-tools directory to your system PATH
- **Connected Android device(s) or emulator(s)** with Developer Options and USB Debugging enabled
- **MCP supported foundational models or agents**:
   - Claude MCP
   - OpenAI Agent SDK
   - Copilot Studio
   - Any LLM or agent that implements the Model Context Protocol

## Installation

### Setting up Android Platform Tools

1. Download Android Platform Tools:
   - Visit [Android Platform Tools](https://developer.android.com/tools/releases/platform-tools)
   - Download the appropriate package for your operating system

2. Extract the package and add to PATH:
   - Extract the downloaded zip file
   - Add the `platform-tools` directory to your system PATH
   - Verify installation by running `adb version` in your terminal

3. Configure your Android device:
   - Enable Developer Options (tap Build Number 7 times in Settings > About phone)
   - Enable USB Debugging in Developer Options
   - Connect device via USB and accept the debugging prompt on the device

### Setting up the Vision Test project

1. Clone the repository:
   ```bash
   git clone https://github.com/yourusername/vision-test.git
   cd vision-test
   ```

2. Build the project:
   ```bash
   ./gradlew build
   ```

### Setting up MCP Claude Desktop

1. Download and install Claude Desktop with MCP support
   - Install from your organization's approved source
   - Ensure you have the correct permissions to use the MCP features

2. Configure Claude Desktop for external tools:
   - Open Claude Desktop settings
   - Navigate to the Tools section
   - Enable "External MCP Tools"
   - Add the Vision Test server as a tool provider
   - Set the connection parameters to use the Vision Test server

## Usage

### Running the Server

```bash
./gradlew run
```

This will start the MCP server, which will begin listening for connections on the standard input/output streams.

### Connecting with Claude Desktop

1. Start the Vision Test server
2. Open Claude Desktop
3. In a conversation, you can now access the Android device tools
4. Claude will be able to use commands like `available_device`, `list_apps`, etc.

### Available Tools

The server exposes the following tools:


#### available_device

Returns detailed information about the first available Android device.

#### list_apps

Lists all installed applications on the connected device.

#### info_app

Returns formatted information about a specific application.

#### launch_app

Launches an app on the device.


## Configuration

### Server Configuration

The server can be configured using the `AppConfig` class. Default values:

- ADB Timeout: 5000ms
- Device Cache Validity: 1000ms
- Tool Execution Timeout: 10000ms

You can customize these values by:

1. **Environment Variables**:
   - `VISION_TEST_SERVER_NAME`: Name of the server (default: "vision-test")
   - `VISION_TEST_SERVER_VERSION`: Version of the server (default: "1.0.0")
   - `VISION_TEST_ADB_TIMEOUT`: ADB command timeout in milliseconds (default: 5000)
   - `VISION_TEST_DEVICE_CACHE_VALIDITY`: Device cache validity period in milliseconds (default: 1000)
   - `VISION_TEST_TOOL_TIMEOUT`: Tool execution timeout in milliseconds (default: 10000)
   - `VISION_TEST_ENABLE_LOGGING`: Enable detailed logging (default: true)

2. **Properties File** (future implementation):
   Create a `config.properties` file with the following format:
   ```properties
   server.name=vision-test
   server.version=1.0.0
   adb.timeout=5000
   device.cache.validity=1000
   tool.timeout=10000
   logging.enabled=true
   ```

### Claude MCP Configuration

To configure Claude to work with the Vision Test server:

1. Ensure Claude Desktop is running with MCP support enabled

2. Create an MCP configuration file:
   - Create a file named `mcpServers.json` in the appropriate configuration directory:
      - Windows: `%APPDATA%\Claude\mcpServers.json`
      - macOS: `~/Library/Application Support/Claude/mcpServers.json`
      - Linux: `~/.config/Claude/mcpServers.json`

   - Add the following configuration, replacing the path with your actual path:
   ```json
   {
     "mcpServers": {
       "visionTest": {
         "command": "java",
         "args": [
           "-jar",
           "/ABSOLUTE/PATH/TO/PARENT/FOLDER/visiontest/app/build/libs/visiontest.jar"
         ]
       }
     }
   }
   ```

3. Restart Claude Desktop to load the new configuration

4. In the Claude settings, verify the external tool is available:
   - Tool Provider: "visionTest" should now appear in the list
   - You can test the connection by using the "Test Connection" feature

## Architecture

### Core Components

- **Android**: Main interface to Android devices via ADB
- **ToolFactory**: Registers and manages tools for the MCP server
- **ErrorHandler**: Centralized error handling with retries
- **Exceptions**: Custom exception hierarchy for different error conditions

### Flow

1. The server initializes and connects to ADB
2. Tools are registered with the MCP server
3. The server listens for commands via standard input
4. Commands are processed and routed to appropriate handlers
5. Results are returned via standard output

## Error Handling

The system includes a comprehensive error handling mechanism with specific error codes:

- `ERR_NO_DEVICE`: No Android device is available
- `ERR_CMD_FAILED`: Command execution failed
- `ERR_PKG_NOT_FOUND`: Package not found on device
- `ERR_APP_INFO`: Error retrieving app information
- `ERR_APP_LIST`: Error listing apps
- `ERR_TIMEOUT`: Operation timed out
- `ERR_INVALID_ARG`: Invalid argument provided
- `ERR_ADB_INIT`: ADB initialization failed
- `ERR_UNKNOWN`: Unknown error

## Extension

### Adding New Tools

To add a new tool:

1. Define the tool functionality in the `ToolFactory` class
2. Register the tool in the `registerAllTools` method
3. Implement any required Android interface methods

## Future Plans

- iOS device support
- More advanced device interactions (screenshots, UI automation)
- Plugin system for easier extension
- Performance monitoring and logging
- Pagination for large result sets

## Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## License

This project is licensed under the MIT License - see the LICENSE file for details.