import Foundation
import Swifter

/// HTTP server with JSON-RPC 2.0 dispatch for iOS automation.
/// Mirrors JsonRpcServerInstrumented.kt from the Android automation server.
class JsonRpcServer {

    private let port: UInt16
    private let bridge: XCUITestBridge
    private let server: HttpServer

    init(port: UInt16, bridge: XCUITestBridge) {
        self.port = port
        self.bridge = bridge
        self.server = HttpServer()
        setupRoutes()
    }

    private func setupRoutes() {
        // Health check endpoint
        server.GET["/health"] = { [weak self] _ in
            guard let self = self else { return .ok(.text("")) }
            let response: [String: Any] = ["status": "ok", "port": Int(self.port)]
            return self.jsonResponse(response)
        }

        // JSON-RPC endpoint
        server["/jsonrpc"] = { [weak self] request in
            guard let self = self else { return .internalServerError }

            guard request.method == "POST" else {
                return .raw(405, "Method Not Allowed", ["Content-Type": "application/json"], { writer in
                    try writer.write(Data("{\"error\":\"Only POST allowed\"}".utf8))
                })
            }

            let bodyData = Data(request.body)
            // XCUITest APIs must be called on the main thread
            let response: [String: Any]
            do {
                response = try self.runOnMainThread {
                    self.handleRequest(bodyData)
                }
            } catch {
                NSLog("JsonRpcServer: Main thread execution failed: \(error)")
                response = JsonRpcResponse.error(.xcuiTestError("Main thread timeout after 30s"), id: nil)
            }
            return self.jsonResponse(response)
        }
    }

    func start() throws {
        try server.start(port, forceIPv4: true)
        NSLog("JsonRpcServer: Started on port \(port)")
    }

    func stop() {
        server.stop()
        NSLog("JsonRpcServer: Stopped")
    }

    // MARK: - JSON-RPC Dispatch

    internal func handleRequest(_ data: Data) -> [String: Any] {
        guard let request = JsonRpcRequest.parse(from: data) else {
            return JsonRpcResponse.error(.parseError("Failed to parse request"), id: nil)
        }

        guard request.jsonrpc == "2.0" else {
            return JsonRpcResponse.error(.invalidRequest("Invalid jsonrpc version"), id: request.id)
        }

        do {
            let result = try executeMethod(request.method, params: request.params)
            return JsonRpcResponse.success(result: result, id: request.id)
        } catch is MethodNotFoundException {
            return JsonRpcResponse.error(.methodNotFound(request.method), id: request.id)
        } catch let error as InvalidParamsException {
            return JsonRpcResponse.error(.invalidParams(error.message), id: request.id)
        } catch {
            return JsonRpcResponse.error(.xcuiTestError(error.localizedDescription), id: request.id)
        }
    }

    private func executeMethod(_ method: String, params: [String: Any]?) throws -> Any {
        NSLog("JsonRpcServer: Executing method: \(method)")

        switch method {
        // UI Hierarchy
        case "ui.dumpHierarchy":
            let bundleId = params?["bundleId"] as? String
            return bridge.dumpHierarchy(bundleId: bundleId).toDictionary()

        case "ui.getInteractiveElements":
            let includeDisabled = params?["includeDisabled"] as? Bool ?? false
            let bundleId = params?["bundleId"] as? String
            return bridge.getInteractiveElements(includeDisabled: includeDisabled, bundleId: bundleId).toDictionary()

        // Device methods
        case "device.getInfo":
            return bridge.getDeviceInfo().toDictionary()

        case "device.pressHome":
            return bridge.pressHome().toDictionary()

        // Tap
        case "ui.tapByCoordinates":
            guard let x = intParam(params, "x") else {
                throw InvalidParamsException("Missing 'x' parameter")
            }
            guard let y = intParam(params, "y") else {
                throw InvalidParamsException("Missing 'y' parameter")
            }
            return bridge.tapByCoordinates(x: x, y: y).toDictionary()

        // Find element
        case "ui.findElement":
            let text = params?["text"] as? String
            let textContains = params?["textContains"] as? String
            let identifier = params?["resourceId"] as? String
            let elementType = params?["className"] as? String
            let label = params?["contentDescription"] as? String

            if text == nil && textContains == nil && identifier == nil
                && elementType == nil && label == nil {
                throw InvalidParamsException(
                    "At least one selector required: text, textContains, resourceId, className, or contentDescription"
                )
            }

            let bundleId = params?["bundleId"] as? String
            return bridge.findElement(
                text: text,
                textContains: textContains,
                identifier: identifier,
                elementType: elementType,
                label: label,
                bundleId: bundleId
            ).toDictionary()

        // Swipe by coordinates
        case "ui.swipe":
            guard let startX = intParam(params, "startX") else {
                throw InvalidParamsException("Missing 'startX' parameter")
            }
            guard let startY = intParam(params, "startY") else {
                throw InvalidParamsException("Missing 'startY' parameter")
            }
            guard let endX = intParam(params, "endX") else {
                throw InvalidParamsException("Missing 'endX' parameter")
            }
            guard let endY = intParam(params, "endY") else {
                throw InvalidParamsException("Missing 'endY' parameter")
            }
            // iOS uses duration instead of steps; map steps to duration if provided
            let steps = intParam(params, "steps") ?? 20
            let duration = Double(steps) * 0.05

            return bridge.swipe(
                startX: startX, startY: startY,
                endX: endX, endY: endY,
                duration: duration
            ).toDictionary()

        // Input text
        case "ui.inputText":
            guard let text = params?["text"] as? String else {
                throw InvalidParamsException("Missing 'text' parameter")
            }
            return bridge.inputText(text: text).toDictionary()

        // Swipe by direction
        case "ui.swipeByDirection":
            guard let dirStr = (params?["direction"] as? String)?.uppercased(),
                  let direction = SwipeDirection(rawValue: dirStr) else {
                throw InvalidParamsException("Missing or invalid 'direction' parameter. Must be: UP, DOWN, LEFT, RIGHT")
            }

            let distStr = (params?["distance"] as? String)?.uppercased() ?? "MEDIUM"
            guard let distance = SwipeDistance(rawValue: distStr) else {
                throw InvalidParamsException("Invalid distance: \(distStr). Must be: SHORT, MEDIUM, LONG")
            }

            let spdStr = (params?["speed"] as? String)?.uppercased() ?? "NORMAL"
            guard let speed = SwipeSpeed(rawValue: spdStr) else {
                throw InvalidParamsException("Invalid speed: \(spdStr). Must be: SLOW, NORMAL, FAST")
            }

            return bridge.swipeByDirection(direction: direction, distance: distance, speed: speed).toDictionary()

        default:
            throw MethodNotFoundException(method)
        }
    }

    // MARK: - Helpers

    /// Executes a block synchronously on the main thread.
    /// XCUITest APIs (XCUIDevice, XCUIElement, etc.) must be called from the main thread.
    /// - Throws: `MainThreadTimeoutError` if the block doesn't complete within 30 seconds.
    private func runOnMainThread<T>(_ block: @escaping () -> T) throws -> T {
        if Thread.isMainThread {
            return block()
        }
        var result: T?
        let semaphore = DispatchSemaphore(value: 0)
        DispatchQueue.main.async {
            result = block()
            semaphore.signal()
        }
        let timeout = semaphore.wait(timeout: .now() + 30)
        if timeout == .timedOut {
            NSLog("JsonRpcServer: WARNING - runOnMainThread timed out after 30s")
            throw MainThreadTimeoutError()
        }
        return result!
    }

    private func jsonResponse(_ dict: [String: Any]) -> HttpResponse {
        do {
            let data = try JSONSerialization.data(withJSONObject: dict, options: [.prettyPrinted, .sortedKeys])
            return .raw(200, "OK", ["Content-Type": "application/json"]) { writer in
                try writer.write(data)
            }
        } catch {
            return .internalServerError
        }
    }
}

// MARK: - Exception Types

class MethodNotFoundException: Error {
    let method: String
    init(_ method: String) {
        self.method = method
    }
}

class InvalidParamsException: Error {
    let message: String
    init(_ message: String) {
        self.message = message
    }
}

class MainThreadTimeoutError: Error {}
