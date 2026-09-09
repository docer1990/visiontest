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
    wait: (TimeInterval) -> Void,
    readiness: () -> ElementTapReadiness,
    tap: () -> Void
) -> OperationResult {
    let deadline = now() + Double(request.timeoutMs) / 1_000
    var observedBlockedElement = false

    while true {
        switch readiness() {
        case .ready:
            guard now() < deadline else { break }
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
        guard now() < deadline else { break }
    }

    let status = observedBlockedElement ? "Element found but not tappable" : "Element not found"
    return OperationResult(
        success: false,
        error: "\(status) after \(request.timeoutMs)ms for \(request.selectorDescription)"
    )
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
