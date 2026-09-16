import XCTest

/// Entry point XCUITest that starts the JSON-RPC automation server.
/// Mirrors AutomationServerTest.kt from the Android automation server.
///
/// Usage (via xcodebuild):
/// ```
/// xcodebuild test \
///   -project IOSAutomationServer.xcodeproj \
///   -scheme IOSAutomationServer \
///   -destination 'platform=iOS Simulator,name=iPhone 16' \
///   -only-testing:IOSAutomationServerUITests/AutomationServerUITest/testRunAutomationServer
/// ```
class AutomationServerUITest: XCTestCase {

    func testAlertResultSurvivesDismissal() throws {
        let app = XCUIApplication(bundleIdentifier: "com.example.IOSAutomationServer")
        app.launchArguments = ["--review-alert"]
        app.launch()
        XCTAssertTrue(app.alerts.firstMatch.waitForExistence(timeout: 5))
        let request = try HandleAlertRequest(params: [
            "action": "accept", "buttonLabel": "Accept fixture", "bundleId": "com.example.IOSAutomationServer",
        ])

        let result = XCUITestBridge().handleAlert(request)

        XCTAssertTrue(result.success)
        XCTAssertEqual(result.message, "Tapped alert button 'Accept fixture'")
        XCTAssertFalse(app.alerts.firstMatch.exists)
    }

    func testSelectorGestureWithoutBundleIdUsesForegroundApplication() throws {
        let safari = XCUIApplication(bundleIdentifier: "com.apple.mobilesafari")
        safari.activate()
        XCTAssertTrue(safari.textFields.firstMatch.waitForExistence(timeout: 2))
        let request = try GestureRequest(params: [
            "className": "TextField",
            "timeoutMs": 500,
        ])

        let result = XCUITestBridge().doubleTap(request)

        XCTAssertTrue(result.success, result.error ?? "Expected selector gesture to succeed")
    }

    func testInteractionJsonRpcRoutesUseTheSharedTargetResolver() throws {
        let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
        var scopes: [String?] = []
        let bridge = XCUITestBridge(interactionTargetOverride: {
            scopes.append($0)
            return springboard
        })
        let server = JsonRpcServer(port: 0, bridge: bridge)
        let requests: [(String, [String: Any])] = [
            ("ui.longPress", ["text": "missing", "timeoutMs": 1]),
            ("ui.doubleTap", ["resourceId": "missing", "bundleId": "gesture.app", "timeoutMs": 1]),
            ("ui.dismissKeyboard", [:]),
            ("ui.handleAlert", ["action": "accept", "bundleId": "alert.app"]),
            ("ui.inputText", [
                "text": "value", "targetText": "missing", "bundleId": "input.app", "timeoutMs": 1,
            ]),
        ]

        for (index, request) in requests.enumerated() {
            let response = server.handleRequest(try jsonRpcRequest(
                method: request.0,
                params: request.1,
                id: index + 1
            ))
            XCTAssertEqual(response["jsonrpc"] as? String, "2.0")
            XCTAssertEqual(response["id"] as? Int, index + 1)
            XCTAssertNotNil(response["result"] as? [String: Any])
            XCTAssertNil(response["error"])
        }

        XCTAssertEqual(scopes.count, 5)
        XCTAssertNil(scopes[0])
        XCTAssertEqual(scopes[1], "gesture.app")
        XCTAssertNil(scopes[2])
        XCTAssertEqual(scopes[3], "alert.app")
        XCTAssertEqual(scopes[4], "input.app")
    }

    func testInteractionJsonRpcRoutesRejectInvalidParameters() throws {
        let server = JsonRpcServer(port: 0, bridge: XCUITestBridge())
        let requests: [(String, [String: Any])] = [
            ("ui.longPress", [:]),
            ("ui.doubleTap", ["x": 1]),
            ("ui.dismissKeyboard", ["bundleId": " "]),
            ("ui.handleAlert", ["action": "later"]),
            ("ui.inputText", ["text": "value", "timeoutMs": 1]),
        ]

        for (index, request) in requests.enumerated() {
            let response = server.handleRequest(try jsonRpcRequest(
                method: request.0,
                params: request.1,
                id: index + 1
            ))
            let error = try XCTUnwrap(response["error"] as? [String: Any])
            XCTAssertEqual(error["code"] as? Int, JsonRpcError.INVALID_PARAMS)
            XCTAssertNil(response["result"])
        }
    }

    private static let defaultPort: UInt16 = 9009
    private static let envPortKey = "PORT"

    private func jsonRpcRequest(method: String, params: [String: Any], id: Int) throws -> Data {
        try JSONSerialization.data(withJSONObject: [
            "jsonrpc": "2.0",
            "method": method,
            "params": params,
            "id": id,
        ])
    }

    func testRunAutomationServer() {
        // Read port from environment
        let port: UInt16
        if let portString = ProcessInfo.processInfo.environment[AutomationServerUITest.envPortKey],
           let envPort = UInt16(portString) {
            port = envPort
        } else {
            port = AutomationServerUITest.defaultPort
        }

        NSLog("==============================================")
        NSLog("Starting iOS Automation Server (XCUITest)")
        NSLog("Port: \(port)")
        NSLog("==============================================")

        // The bridge resolves the foreground application without launching a host app.
        let bridge = XCUITestBridge()
        let server = JsonRpcServer(port: port, bridge: bridge)

        // Start the server
        do {
            try server.start()
        } catch {
            XCTFail("Failed to start server: \(error)")
            return
        }

        NSLog("JSON-RPC server started successfully")
        NSLog("Health check: http://localhost:\(port)/health")
        NSLog("JSON-RPC endpoint: http://localhost:\(port)/jsonrpc")
        NSLog("Waiting indefinitely... (kill xcodebuild to stop)")

        // Keep the main thread alive and responsive using RunLoop.
        // Unlike DispatchSemaphore.wait(), this allows the main thread to
        // process dispatched blocks (needed for XCUITest API calls).
        while true {
            RunLoop.current.run(mode: .default, before: .distantFuture)
        }
    }
}
