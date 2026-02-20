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
