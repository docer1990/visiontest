import Foundation

// MARK: - Result Types (mirrors UiAutomatorModels.kt)

struct UiHierarchyResult {
    let success: Bool
    let hierarchy: String?
    let error: String?

    func toDictionary() -> [String: Any] {
        var dict: [String: Any] = ["success": success]
        if let hierarchy = hierarchy { dict["hierarchy"] = hierarchy }
        if let error = error { dict["error"] = error }
        return dict
    }
}

struct DeviceInfoResult {
    let displayWidth: Int
    let displayHeight: Int
    let displayRotation: Int
    let productName: String
    let osVersion: String
    let success: Bool
    let error: String?

    func toDictionary() -> [String: Any] {
        var dict: [String: Any] = [
            "displayWidth": displayWidth,
            "displayHeight": displayHeight,
            "displayRotation": displayRotation,
            "productName": productName,
            "osVersion": osVersion,
            "success": success
        ]
        if let error = error { dict["error"] = error }
        return dict
    }
}

struct OperationResult {
    let success: Bool
    let error: String?

    func toDictionary() -> [String: Any] {
        var dict: [String: Any] = ["success": success]
        if let error = error { dict["error"] = error }
        return dict
    }
}

struct ElementResult {
    let found: Bool
    let text: String?
    let identifier: String?
    let elementType: String?
    let label: String?
    let value: String?
    let bounds: String?
    let isEnabled: Bool?
    let error: String?

    func toDictionary() -> [String: Any] {
        var dict: [String: Any] = ["found": found]
        if let text = text { dict["text"] = text }
        if let identifier = identifier { dict["resourceId"] = identifier }
        if let elementType = elementType { dict["className"] = elementType }
        if let label = label { dict["contentDescription"] = label }
        if let value = value { dict["value"] = value }
        if let bounds = bounds { dict["bounds"] = bounds }
        if let isEnabled = isEnabled { dict["isEnabled"] = isEnabled }
        if let error = error { dict["error"] = error }
        return dict
    }
}

struct InteractiveElement {
    let text: String?
    let identifier: String?
    let elementType: String?
    let label: String?
    let value: String?
    let bounds: String?
    let centerX: Int?
    let centerY: Int?
    let isEnabled: Bool

    func toDictionary() -> [String: Any] {
        var dict: [String: Any] = ["isEnabled": isEnabled]
        if let text = text { dict["text"] = text }
        if let identifier = identifier { dict["resourceId"] = identifier }
        if let elementType = elementType { dict["className"] = elementType }
        if let label = label { dict["contentDescription"] = label }
        if let value = value { dict["value"] = value }
        if let bounds = bounds { dict["bounds"] = bounds }
        if let centerX = centerX { dict["centerX"] = centerX }
        if let centerY = centerY { dict["centerY"] = centerY }
        return dict
    }
}

struct InteractiveElementsResult {
    let success: Bool
    let elements: [InteractiveElement]
    let count: Int
    let error: String?

    func toDictionary() -> [String: Any] {
        var dict: [String: Any] = [
            "success": success,
            "elements": elements.map { $0.toDictionary() },
            "count": count
        ]
        if let error = error { dict["error"] = error }
        return dict
    }
}

enum SwipeDirection: String {
    case up = "UP"
    case down = "DOWN"
    case left = "LEFT"
    case right = "RIGHT"
}

enum SwipeDistance: String {
    case short_ = "SHORT"   // 20% of screen
    case medium = "MEDIUM"  // 40% of screen
    case long_ = "LONG"     // 60% of screen

    var percent: Double {
        switch self {
        case .short_: return 0.20
        case .medium: return 0.40
        case .long_: return 0.60
        }
    }
}

enum SwipeSpeed: String {
    case slow = "SLOW"
    case normal = "NORMAL"
    case fast = "FAST"

    /// Duration in seconds for the swipe gesture
    var duration: TimeInterval {
        switch self {
        case .slow: return 2.5
        case .normal: return 1.0
        case .fast: return 0.25
        }
    }
}
