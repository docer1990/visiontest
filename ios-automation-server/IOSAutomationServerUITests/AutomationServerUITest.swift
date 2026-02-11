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

    private static let defaultPort: UInt16 = 9009
    private static let envPortKey = "PORT"

    override class var defaultTestSuite: XCTestSuite {
        // Disable test timeout — the server needs to run indefinitely
        let suite = super.defaultTestSuite
        return suite
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

        // No host app launch needed — we use springboard for all queries,
        // so whatever app is in the foreground stays there.
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
