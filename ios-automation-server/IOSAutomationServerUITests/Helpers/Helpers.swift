import Foundation
import CoreGraphics

// MARK: - Pure helper functions extracted for testability
// These functions have no XCUITest or Swifter dependencies.

/// Escapes XML special characters in a string.
/// Returns empty string for nil input.
func escapeXML(_ text: String?) -> String {
    guard let text = text else { return "" }
    return text
        .replacingOccurrences(of: "&", with: "&amp;")
        .replacingOccurrences(of: "<", with: "&lt;")
        .replacingOccurrences(of: ">", with: "&gt;")
        .replacingOccurrences(of: "\"", with: "&quot;")
        .replacingOccurrences(of: "'", with: "&apos;")
}

/// Formats a CGRect as Android-style bounds string: "[left,top][right,bottom]"
func boundsString(from frame: CGRect) -> String {
    let left = Int(frame.minX)
    let top = Int(frame.minY)
    let right = Int(frame.maxX)
    let bottom = Int(frame.maxY)
    return "[\(left),\(top)][\(right),\(bottom)]"
}

/// Extracts an Int from a heterogeneous params dictionary.
/// Handles Int, Double (truncated), and String (parsed) values.
/// Returns nil for missing keys, nil params, or unconvertible values.
func intParam(_ params: [String: Any]?, _ key: String) -> Int? {
    guard let value = params?[key] else { return nil }
    if let intVal = value as? Int { return intVal }
    if let doubleVal = value as? Double { return Int(doubleVal) }
    if let strVal = value as? String { return Int(strVal) }
    return nil
}

/// Endpoints leave 15% margins on each side, matching Android element swipes.
func elementSwipeEndpoints(frame: CGRect, direction: SwipeDirection) -> (start: CGPoint, end: CGPoint)? {
    guard frame.origin.x.isFinite, frame.origin.y.isFinite,
          frame.size.width.isFinite, frame.size.height.isFinite,
          frame.size.width > 0, frame.size.height > 0,
          frame.maxX.isFinite, frame.maxY.isFinite else { return nil }
    let center = CGPoint(x: frame.midX, y: frame.midY)
    let horizontal = frame.width * 0.35
    let vertical = frame.height * 0.35
    let start: CGPoint, end: CGPoint
    switch direction {
    case .up:
        start = CGPoint(x: center.x, y: center.y + vertical)
        end = CGPoint(x: center.x, y: center.y - vertical)
    case .down:
        start = CGPoint(x: center.x, y: center.y - vertical)
        end = CGPoint(x: center.x, y: center.y + vertical)
    case .left:
        start = CGPoint(x: center.x + horizontal, y: center.y)
        end = CGPoint(x: center.x - horizontal, y: center.y)
    case .right:
        start = CGPoint(x: center.x - horizontal, y: center.y)
        end = CGPoint(x: center.x + horizontal, y: center.y)
    }
    guard start != end, frame.contains(start), frame.contains(end) else { return nil }
    return (start, end)
}

/// Validates JSON-RPC input without accessing the simulator.
struct ElementSwipeRequest {
    let direction: SwipeDirection
    let speed: SwipeSpeed
    let text: String?
    let textContains: String?
    let identifier: String?
    let elementType: String?
    let label: String?
    let bundleId: String?

    init(params: [String: Any]?) throws {
        guard let rawDirection = params?["direction"] as? String,
              let direction = SwipeDirection(rawValue: rawDirection.uppercased()) else {
            throw InvalidParamsException("Missing or invalid direction. Must be: up, down, left, right")
        }
        if let value = params?["speed"], !(value is String) {
            throw InvalidParamsException("Invalid speed. Must be: slow, normal, fast")
        }
        let rawSpeed = params?["speed"] as? String ?? "normal"
        guard let speed = SwipeSpeed(rawValue: rawSpeed.uppercased()) else {
            throw InvalidParamsException("Invalid speed. Must be: slow, normal, fast")
        }
        self.direction = direction
        self.speed = speed
        text = try validatedString(params, "text")
        textContains = try validatedString(params, "textContains")
        identifier = try validatedString(params, "resourceId")
        elementType = try validatedString(params, "className")
        label = try validatedString(params, "contentDescription")
        bundleId = try validatedString(params, "bundleId")
        guard [text, textContains, identifier, elementType, label].contains(where: { $0 != nil }) else {
            throw InvalidParamsException("At least one selector required: text, textContains, resourceId, className, or contentDescription")
        }
    }
}

private func validatedString(_ params: [String: Any]?, _ key: String) throws -> String? {
    guard let rawValue = params?[key] else { return nil }
    guard let value = rawValue as? String,
          !value.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
        throw InvalidParamsException("'\(key)' must be a nonblank string")
    }
    return value
}

/// Validates and describes a native element-tap request without accessing XCUITest.
struct ElementTapRequest {
    static let defaultTimeoutMs = 10_000
    static let maximumTimeoutMs = 30_000

    let text: String?
    let textContains: String?
    let identifier: String?
    let elementType: String?
    let label: String?
    let bundleId: String?
    let timeoutMs: Int

    init(params: [String: Any]?) throws {
        text = try validatedString(params, "text")
        textContains = try validatedString(params, "textContains")
        identifier = try validatedString(params, "resourceId")
        elementType = try validatedString(params, "className")
        label = try validatedString(params, "contentDescription")
        bundleId = try validatedString(params, "bundleId")
        guard [text, textContains, identifier, elementType, label].contains(where: { $0 != nil }) else {
            throw InvalidParamsException("At least one selector required: text, textContains, resourceId, className, or contentDescription")
        }
        if let value = params?["timeoutMs"] {
            guard let timeout = strictInteger(value), (1...Self.maximumTimeoutMs).contains(timeout) else {
                throw InvalidParamsException("'timeoutMs' must be an integer between 1 and \(Self.maximumTimeoutMs)")
            }
            timeoutMs = timeout
        } else {
            timeoutMs = Self.defaultTimeoutMs
        }
    }

    var selectorDescription: String {
        if let text = text { return "text='\(text)'" }
        if let textContains = textContains { return "textContains='\(textContains)'" }
        if let identifier = identifier { return "resourceId='\(identifier)'" }
        if let elementType = elementType { return "className='\(elementType)'" }
        return "contentDescription='\(label ?? "")'"
    }
}

private func strictInteger(_ value: Any) -> Int? {
    guard let number = value as? NSNumber,
          CFGetTypeID(number) != CFBooleanGetTypeID() else { return nil }
    let typeEncoding = String(cString: number.objCType)
    switch typeEncoding {
    case "c", "s", "i", "l", "q":
        let integer = number.int64Value
        guard integer >= Int64(Int.min), integer <= Int64(Int.max) else { return nil }
        return Int(integer)
    case "C", "S", "I", "L", "Q":
        let integer = number.uint64Value
        guard integer <= UInt64(Int.max) else { return nil }
        return Int(integer)
    default:
        return nil
    }
}

struct ElementSelectors {
    let text: String?
    let textContains: String?
    let identifier: String?
    let elementType: String?
    let label: String?

    init(params: [String: Any]?, prefix: String = "") throws {
        text = try validatedString(params, "\(prefix)Text".lowercasingFirstCharacter(if: prefix.isEmpty))
        textContains = try validatedString(params, "\(prefix)TextContains".lowercasingFirstCharacter(if: prefix.isEmpty))
        identifier = try validatedString(params, "\(prefix)ResourceId".lowercasingFirstCharacter(if: prefix.isEmpty))
        elementType = try validatedString(params, "\(prefix)ClassName".lowercasingFirstCharacter(if: prefix.isEmpty))
        label = try validatedString(params, "\(prefix)ContentDescription".lowercasingFirstCharacter(if: prefix.isEmpty))
    }

    var hasAny: Bool {
        [text, textContains, identifier, elementType, label].contains { $0 != nil }
    }

    var description: String {
        if let text { return "text='\(text)'" }
        if let textContains { return "textContains='\(textContains)'" }
        if let identifier { return "resourceId='\(identifier)'" }
        if let elementType { return "className='\(elementType)'" }
        return "contentDescription='\(label ?? "")'"
    }
}

private extension String {
    func lowercasingFirstCharacter(if condition: Bool) -> String {
        guard condition, let first else { return self }
        return first.lowercased() + dropFirst()
    }
}

enum GestureTarget {
    case coordinates(CGPoint)
    case element(ElementSelectors, bundleId: String?, timeoutMs: Int)
}

struct GestureRequest {
    static let defaultTimeoutMs = 10_000
    static let maximumTimeoutMs = 30_000

    let target: GestureTarget

    init(params: [String: Any]?) throws {
        let selectors = try ElementSelectors(params: params)
        let bundleId = try validatedString(params, "bundleId")
        let hasCoordinate = params?["x"] != nil || params?["y"] != nil
        guard hasCoordinate != selectors.hasAny else {
            throw InvalidParamsException("Provide exactly one target: coordinates or selectors")
        }
        if hasCoordinate {
            guard let xValue = params?["x"], let yValue = params?["y"],
                  let x = strictInteger(xValue), let y = strictInteger(yValue), x >= 0, y >= 0 else {
                throw InvalidParamsException("Both 'x' and 'y' must be nonnegative integers")
            }
            guard params?["timeoutMs"] == nil, bundleId == nil else {
                throw InvalidParamsException("timeoutMs and bundleId are only valid with selectors")
            }
            target = .coordinates(CGPoint(x: x, y: y))
        } else {
            target = .element(selectors, bundleId: bundleId, timeoutMs: try Self.timeout(params))
        }
    }

    static func timeout(_ params: [String: Any]?) throws -> Int {
        guard let value = params?["timeoutMs"] else { return defaultTimeoutMs }
        guard let timeout = strictInteger(value), (1...maximumTimeoutMs).contains(timeout) else {
            throw InvalidParamsException("'timeoutMs' must be an integer between 1 and \(maximumTimeoutMs)")
        }
        return timeout
    }
}

struct TargetedInputRequest {
    let text: String
    let selectors: ElementSelectors?
    let bundleId: String?
    let timeoutMs: Int?

    init(params: [String: Any]?) throws {
        guard let text = params?["text"] as? String else {
            throw InvalidParamsException("'text' must be a string")
        }
        self.text = text
        bundleId = try validatedString(params, "bundleId")
        let parsedSelectors = try ElementSelectors(params: params, prefix: "target")
        selectors = parsedSelectors.hasAny ? parsedSelectors : nil
        if parsedSelectors.hasAny {
            timeoutMs = try GestureRequest.timeout(params)
        } else {
            guard params?["timeoutMs"] == nil else {
                throw InvalidParamsException("'timeoutMs' is only valid with target selectors")
            }
            timeoutMs = nil
        }
    }
}

enum AlertAction: String {
    case accept
    case dismiss
}

struct HandleAlertRequest {
    let action: AlertAction
    let buttonLabel: String?
    let bundleId: String?

    init(params: [String: Any]?) throws {
        guard let rawAction = params?["action"] as? String,
              let action = AlertAction(rawValue: rawAction.lowercased()) else {
            throw InvalidParamsException("'action' must be accept or dismiss")
        }
        self.action = action
        buttonLabel = try validatedString(params, "buttonLabel")
        bundleId = try validatedString(params, "bundleId")
    }
}

struct AlertButton {
    let label: String
    let actionable: Bool
}

func selectAlertButton(_ buttons: [AlertButton], action: AlertAction, label: String?) -> AlertButton? {
    let actionable = buttons.filter(\.actionable)
    if let label { return actionable.first { $0.label == label } }
    return action == .accept ? actionable.last : actionable.first
}

func verifyKeyboardDismissal(wasVisible: Bool, isVisible: Bool) -> OperationResult {
    guard wasVisible else {
        return OperationResult(success: false, error: "No software keyboard is visible")
    }
    guard !isVisible else {
        return OperationResult(success: false, error: "Software keyboard remained visible")
    }
    return OperationResult(success: true, error: nil)
}

enum ElementTapReadiness {
    case absent
    case blocked
    case ready
}

func isElementTapReady(exists: Bool, isEnabled: Bool, isHittable: Bool) -> Bool {
    exists && isEnabled && isHittable
}

/// Waits for an element to become tappable while keeping timing deterministic in unit tests.
func performElementTap(
    request: ElementTapRequest,
    now: () -> TimeInterval,
    wait: (TimeInterval) -> Void = { RunLoop.current.run(until: Date(timeIntervalSinceNow: $0)) },
    readiness: () -> ElementTapReadiness,
    tap: () -> Void
) -> OperationResult {
    let deadline = now() + Double(request.timeoutMs) / 1_000
    var observedBlockedElement = false

    while true {
        switch readiness() {
        case .ready:
            guard now() <= deadline else { break }
            tap()
            return OperationResult(success: true, error: nil)
        case .blocked:
            observedBlockedElement = true
        case .absent:
            break
        }

        let remaining = deadline - now()
        guard remaining > 0 else { break }
        wait(min(0.5, remaining))
        guard now() <= deadline else { break }
    }

    let status = observedBlockedElement ? "Element found but not tappable" : "Element not found"
    return OperationResult(
        success: false,
        error: "\(status) after \(request.timeoutMs)ms for \(request.selectorDescription)"
    )
}

func performTargetedInput(
    request: TargetedInputRequest,
    now: () -> TimeInterval,
    wait: (TimeInterval) -> Void = { RunLoop.current.run(until: Date(timeIntervalSinceNow: $0)) },
    readiness: () -> ElementTapReadiness,
    tap: () -> Void,
    hasFocus: () -> Bool,
    typeText: () -> Void
) -> OperationResult {
    guard let timeoutMs = request.timeoutMs else {
        typeText()
        return OperationResult(success: true, error: nil)
    }
    let deadline = now() + Double(timeoutMs) / 1_000
    var observedBlocked = false
    while true {
        switch readiness() {
        case .ready where now() <= deadline:
            tap()
            while !hasFocus() {
                let remaining = deadline - now()
                guard remaining > 0 else {
                    return OperationResult(success: false, error: "Element did not acquire editable focus")
                }
                wait(min(0.5, remaining))
            }
            guard now() <= deadline else {
                return OperationResult(success: false, error: "Element did not acquire editable focus")
            }
            typeText()
            return OperationResult(success: true, error: nil)
        case .blocked:
            observedBlocked = true
        case .absent, .ready:
            break
        }
        let remaining = deadline - now()
        guard remaining > 0 else {
            let status = observedBlocked ? "Element found but not actionable" : "Element not found"
            return OperationResult(success: false, error: "\(status) within \(timeoutMs)ms")
        }
        wait(min(0.5, remaining))
    }
}

/// Rejects missing/unusable element frames before dispatching any gesture.
func performElementSwipe(
    frame: CGRect?, visibleFrame: CGRect? = nil,
    direction: SwipeDirection, speed: SwipeSpeed,
    gesture: (CGPoint, CGPoint, TimeInterval) -> OperationResult
) -> OperationResult {
    guard let frame = frame else {
        return OperationResult(success: false, error: "Element not found")
    }
    let gestureFrame = visibleFrame.map { frame.intersection($0) } ?? frame
    guard let points = elementSwipeEndpoints(frame: gestureFrame, direction: direction) else {
        return OperationResult(success: false, error: "Element has no usable bounds")
    }
    return gesture(points.start, points.end, speed.duration)
}
