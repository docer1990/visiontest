# VisionTest Mobile Automation

VisionTest provides a CLI for automating Android devices and iOS simulators. Every command requires `--platform android` or `--platform ios` (alias `-p`). The CLI reuses the same backend as the MCP server tools.

## Standard Automation Loop

```
1. Start the server     → visiontest start_automation_server -p <platform>
2. Take a screenshot    → visiontest screenshot -p <platform>
3. Inspect elements     → visiontest get_interactive_elements -p <platform>
4. Interact             → visiontest tap_by_coordinates -p <platform> <x> <y>
5. Repeat from step 2
```

## Commands

### Setup
| Command | Platforms | Description |
|---------|-----------|-------------|
| `install_automation_server` | android | Install automation APKs on device |
| `start_automation_server` | android, ios | Start the automation server |
| `automation_server_status` | android, ios | Check if server is running |

### Inspection
| Command | Platforms | Description |
|---------|-----------|-------------|
| `get_interactive_elements [--include-disabled]` | android, ios | List tappable elements with coordinates |
| `get_ui_hierarchy` | android, ios | Full UI tree as XML |
| `get_device_info` | android, ios | Display size, rotation, SDK/iOS version |
| `screenshot [--output PATH]` | android, ios | Save PNG (default: `./screenshots/`) |

### Interaction
| Command | Platforms | Description |
|---------|-----------|-------------|
| `tap_by_coordinates <x> <y>` | android, ios | Tap at screen coordinates |
| `input_text <text>` | android, ios | Type into focused element |
| `swipe_direction <up\|down\|left\|right> [--distance short\|medium\|long] [--speed slow\|normal\|fast]` | android, ios | Swipe gesture |

### Navigation
| Command | Platforms | Description |
|---------|-----------|-------------|
| `press_back` | android | Press back button |
| `press_home` | android, ios | Press home button |

### Apps
| Command | Platforms | Description |
|---------|-----------|-------------|
| `launch_app <id>` | android, ios | Launch by package name or bundle ID |

## The `--platform` Flag

Every command requires `--platform` (or `-p`). There is no default and no auto-detection. Android-only commands (`install_automation_server`, `press_back`) reject `--platform ios`.

## Exit Codes

| Code | Meaning | What to do |
|------|---------|------------|
| 0 | Success | Continue |
| 1 | Generic failure | Read stderr, retry or escalate |
| 2 | Usage error | Fix the command arguments |
| 3 | Server not reachable | Run `start_automation_server` first |
| 4 | Device not found | Connect a device or boot a simulator |
| 5 | Platform not supported | Use the correct `--platform` value |

## Flutter Apps

In Flutter apps, text labels appear in `content-desc` (contentDescription) rather than `text`. If `get_interactive_elements` returns elements without visible text, look at the `contentDescription` field instead.

## Example Session

```bash
# Android workflow
visiontest install_automation_server -p android
visiontest start_automation_server -p android
visiontest screenshot -p android
visiontest get_interactive_elements -p android
visiontest tap_by_coordinates -p android 540 1200
visiontest input_text -p android "hello"
visiontest screenshot -p android --output ./after.png

# iOS workflow
visiontest start_automation_server -p ios
visiontest screenshot -p ios
visiontest get_interactive_elements -p ios
visiontest tap_by_coordinates -p ios 200 400
```
