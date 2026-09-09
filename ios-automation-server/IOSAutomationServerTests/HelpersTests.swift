import XCTest
import CoreGraphics

final class HelpersTests: XCTestCase {

    func testElementTapRequestValidatesSelectorsBundleAndStrictTimeoutBeforeLookup() throws {
        let invalid: [[String: Any]] = [
            [:],
            ["text": "   "],
            ["text": 3],
            ["text": "ready", "bundleId": "  "],
            ["text": "ready", "bundleId": 3],
            ["text": "ready", "timeoutMs": 1.5],
            ["text": "ready", "timeoutMs": 1.0],
            ["text": "ready", "timeoutMs": 1e3],
            ["text": "ready", "timeoutMs": "1000"],
            ["text": "ready", "timeoutMs": true],
            ["text": "ready", "timeoutMs": 0],
            ["text": "ready", "timeoutMs": 30_001]
        ]
        for params in invalid {
            XCTAssertThrowsError(try ElementTapRequest(params: params))
        }

        let request = try ElementTapRequest(params: [
            "textContains": "Ready", "bundleId": "com.example.app", "timeoutMs": 2_000
        ])
        XCTAssertEqual(request.textContains, "Ready")
        XCTAssertEqual(request.bundleId, "com.example.app")
        XCTAssertEqual(request.timeoutMs, 2_000)
    }

    func testElementTapRequestUsesTenSecondDefaultAndSelectorDescription() throws {
        let request = try ElementTapRequest(params: ["resourceId": "continue-button"])
        XCTAssertEqual(request.timeoutMs, 10_000)
        XCTAssertEqual(request.selectorDescription, "resourceId='continue-button'")
    }

    func testElementTapRequestRejectsOutOfRangeJsonIntegerWithoutTrapping() throws {
        let invalidData = Data("{\"text\":\"ready\",\"timeoutMs\":9223372036854775808}".utf8)
        let invalidParams = try XCTUnwrap(
            try JSONSerialization.jsonObject(with: invalidData) as? [String: Any]
        )
        XCTAssertThrowsError(try ElementTapRequest(params: invalidParams))

        for timeout in ["-1", "0"] {
            let data = Data("{\"text\":\"ready\",\"timeoutMs\":\(timeout)}".utf8)
            let params = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
            XCTAssertThrowsError(try ElementTapRequest(params: params))
        }

        for timeout in ["1", "30000"] {
            let data = Data("{\"text\":\"ready\",\"timeoutMs\":\(timeout)}".utf8)
            let params = try XCTUnwrap(try JSONSerialization.jsonObject(with: data) as? [String: Any])
            XCTAssertEqual(try ElementTapRequest(params: params).timeoutMs, Int(timeout))
        }
    }

    func testElementTapReadinessRequiresExistingEnabledAndHittableElement() {
        XCTAssertTrue(isElementTapReady(exists: true, isEnabled: true, isHittable: true))
        XCTAssertFalse(isElementTapReady(exists: false, isEnabled: true, isHittable: true))
        XCTAssertFalse(isElementTapReady(exists: true, isEnabled: false, isHittable: true))
        XCTAssertFalse(isElementTapReady(exists: true, isEnabled: true, isHittable: false))
    }

    func testElementTapImmediatelyTapsReadyElement() throws {
        let request = try ElementTapRequest(params: ["text": "Continue"])
        var clock: TimeInterval = 0
        var taps = 0
        var lookups = 0
        let result = performElementTap(
            request: request,
            now: { clock },
            wait: { clock += $0 },
            readiness: {
                lookups += 1
                return .ready
            },
            tap: { taps += 1 }
        )
        XCTAssertTrue(result.success)
        XCTAssertEqual(lookups, 1)
        XCTAssertEqual(taps, 1)
    }

    func testElementTapWaitsAtFiveHundredMillisecondCadenceUntilReady() throws {
        let request = try ElementTapRequest(params: ["text": "Continue", "timeoutMs": 2_000])
        var clock: TimeInterval = 0
        var taps = 0
        var observations: [ElementTapReadiness] = [.absent, .blocked, .ready]
        var waits: [TimeInterval] = []
        let result = performElementTap(
            request: request,
            now: { clock },
            wait: { interval in waits.append(interval); clock += interval },
            readiness: { observations.removeFirst() },
            tap: { taps += 1 }
        )
        XCTAssertTrue(result.success)
        XCTAssertEqual(waits, [0.5, 0.5])
        XCTAssertEqual(taps, 1)
    }

    func testElementTapTimeoutDistinguishesAbsentAndBlockedWithoutTapping() throws {
        for (readiness, expected) in [(ElementTapReadiness.absent, "Element not found"),
                                      (.blocked, "Element found but not tappable")] {
            let request = try ElementTapRequest(params: ["text": "Continue", "timeoutMs": 1_000])
            var clock: TimeInterval = 0
            var taps = 0
            let result = performElementTap(
                request: request,
                now: { clock },
                wait: { clock += $0 },
                readiness: { readiness },
                tap: { taps += 1 }
            )
            XCTAssertFalse(result.success)
            XCTAssertTrue(result.error?.contains(expected) == true)
            XCTAssertTrue(result.error?.contains("1000ms") == true)
            XCTAssertTrue(result.error?.contains("text='Continue'") == true)
            XCTAssertEqual(taps, 0)
        }
    }

    func testElementTapDoesNotTapWhenReadinessChangesAfterDeadline() throws {
        let request = try ElementTapRequest(params: ["text": "Continue", "timeoutMs": 500])
        var clock: TimeInterval = 0
        var taps = 0
        let result = performElementTap(
            request: request,
            now: { clock },
            wait: { clock += $0 },
            readiness: { clock < 0.5 ? .blocked : .ready },
            tap: { taps += 1 }
        )
        XCTAssertFalse(result.success)
        XCTAssertEqual(taps, 0)
    }

    func testElementSwipeEndpointsUseSeventyPercentOfBounds() throws {
        let frame = CGRect(x: 100, y: 200, width: 200, height: 100)
        let expected: [(SwipeDirection, CGPoint, CGPoint)] = [
            (.up, CGPoint(x: 200, y: 285), CGPoint(x: 200, y: 215)),
            (.down, CGPoint(x: 200, y: 215), CGPoint(x: 200, y: 285)),
            (.left, CGPoint(x: 270, y: 250), CGPoint(x: 130, y: 250)),
            (.right, CGPoint(x: 130, y: 250), CGPoint(x: 270, y: 250))
        ]
        for (direction, start, end) in expected {
            let points = try XCTUnwrap(elementSwipeEndpoints(frame: frame, direction: direction))
            XCTAssertEqual(points.start, start)
            XCTAssertEqual(points.end, end)
        }
    }

    func testElementSwipeRejectsUnusableBounds() {
        for frame in [CGRect.zero, CGRect(x: 0, y: 0, width: -1, height: 10),
                      CGRect(x: CGFloat.nan, y: 0, width: 100, height: 100),
                      CGRect(x: 0, y: 0, width: CGFloat.infinity, height: 100)] {
            XCTAssertNil(elementSwipeEndpoints(frame: frame, direction: .up))
        }
    }

    func testElementSwipeRequestValidatesBeforeLookup() throws {
        for params: [String: Any] in [[:], ["direction": "diagonal", "text": "x"],
                                    ["direction": "up", "text": "x", "speed": "warp"],
                                    ["direction": "up", "text": "x", "speed": 42],
                                    ["direction": "up", "bundleId": "com.example"]] {
            XCTAssertThrowsError(try ElementSwipeRequest(params: params))
        }
        let request = try ElementSwipeRequest(params: ["direction": "LEFT", "resourceId": "photos", "bundleId": "com.example"])
        XCTAssertEqual(request.direction, .left)
        XCTAssertEqual(request.speed, .normal)
        XCTAssertEqual(request.identifier, "photos")
        XCTAssertEqual(request.bundleId, "com.example")
    }

    func testElementSwipeRequestRejectsInvalidAndBlankStringParameters() {
        let names = [
            "text", "textContains", "resourceId", "className",
            "contentDescription", "bundleId"
        ]
        for name in names {
            let validSelector = name == "text" ? "textContains" : "text"
            var wrongType: [String: Any] = ["direction": "up", validSelector: "valid"]
            wrongType[name] = 42
            XCTAssertThrowsError(
                try ElementSwipeRequest(params: wrongType),
                "Expected non-string \(name) to fail"
            )
            var blank: [String: Any] = ["direction": "up", validSelector: "valid"]
            blank[name] = "   "
            XCTAssertThrowsError(
                try ElementSwipeRequest(params: blank),
                "Expected blank \(name) to fail"
            )
        }
    }

    func testElementSwipeDoesNotGestureForMissingOrInvalidFrame() {
        for frame: CGRect? in [nil, .zero, CGRect(x: CGFloat.nan, y: 0, width: 20, height: 20)] {
            var gestures = 0
            let result = performElementSwipe(frame: frame, direction: .up, speed: .normal) { _, _, _ in
                gestures += 1
                return OperationResult(success: true, error: nil)
            }
            XCTAssertFalse(result.success)
            XCTAssertNotNil(result.error)
            XCTAssertEqual(gestures, 0)
        }
    }

    func testElementSwipeClipsGestureToVisibleScreen() {
        var capturedStart: CGPoint?
        var capturedEnd: CGPoint?
        let result = performElementSwipe(
            frame: CGRect(x: -100, y: 20, width: 200, height: 100),
            visibleFrame: CGRect(x: 0, y: 0, width: 100, height: 200),
            direction: .left,
            speed: .normal
        ) { start, end, _ in
            capturedStart = start
            capturedEnd = end
            return OperationResult(success: true, error: nil)
        }
        XCTAssertTrue(result.success)
        XCTAssertEqual(capturedStart, CGPoint(x: 85, y: 70))
        XCTAssertEqual(capturedEnd, CGPoint(x: 15, y: 70))
    }

    func testElementSwipeRejectsFullyOffscreenElement() {
        var gestures = 0
        let result = performElementSwipe(
            frame: CGRect(x: -200, y: 20, width: 100, height: 100),
            visibleFrame: CGRect(x: 0, y: 0, width: 100, height: 200),
            direction: .left,
            speed: .normal
        ) { _, _, _ in
            gestures += 1
            return OperationResult(success: true, error: nil)
        }
        XCTAssertFalse(result.success)
        XCTAssertEqual(gestures, 0)
    }

    func testElementSwipeUsesExistingSpeedDurations() {
        for (speed, duration): (SwipeSpeed, TimeInterval) in [(.slow, 2.5), (.normal, 1.0), (.fast, 0.25)] {
            var gestures = 0
            let result = performElementSwipe(frame: CGRect(x: 100, y: 200, width: 200, height: 100),
                                             direction: .left, speed: speed) { start, end, actualDuration in
                gestures += 1
                XCTAssertEqual(start, CGPoint(x: 270, y: 250))
                XCTAssertEqual(end, CGPoint(x: 130, y: 250))
                XCTAssertEqual(actualDuration, duration)
                return OperationResult(success: true, error: nil)
            }
            XCTAssertTrue(result.success)
            XCTAssertEqual(gestures, 1)
        }
    }

    // MARK: - escapeXML

    func testEscapeXMLNilReturnsEmpty() {
        XCTAssertEqual(escapeXML(nil), "")
    }

    func testEscapeXMLPlainTextUnchanged() {
        XCTAssertEqual(escapeXML("hello world"), "hello world")
    }

    func testEscapeXMLAmpersand() {
        XCTAssertEqual(escapeXML("a&b"), "a&amp;b")
    }

    func testEscapeXMLLessThan() {
        XCTAssertEqual(escapeXML("a<b"), "a&lt;b")
    }

    func testEscapeXMLGreaterThan() {
        XCTAssertEqual(escapeXML("a>b"), "a&gt;b")
    }

    func testEscapeXMLDoubleQuote() {
        XCTAssertEqual(escapeXML("a\"b"), "a&quot;b")
    }

    func testEscapeXMLSingleQuote() {
        XCTAssertEqual(escapeXML("a'b"), "a&apos;b")
    }

    func testEscapeXMLMultipleReplacements() {
        XCTAssertEqual(
            escapeXML("<div class=\"test\">&'value'</div>"),
            "&lt;div class=&quot;test&quot;&gt;&amp;&apos;value&apos;&lt;/div&gt;"
        )
    }

    func testEscapeXMLEmptyString() {
        XCTAssertEqual(escapeXML(""), "")
    }

    // MARK: - boundsString

    func testBoundsStringOriginZero() {
        let rect = CGRect(x: 0, y: 0, width: 100, height: 200)
        XCTAssertEqual(boundsString(from: rect), "[0,0][100,200]")
    }

    func testBoundsStringWithOffset() {
        let rect = CGRect(x: 10, y: 20, width: 80, height: 160)
        XCTAssertEqual(boundsString(from: rect), "[10,20][90,180]")
    }

    func testBoundsStringZeroSize() {
        let rect = CGRect(x: 50, y: 50, width: 0, height: 0)
        XCTAssertEqual(boundsString(from: rect), "[50,50][50,50]")
    }

    func testBoundsStringFractionalTruncates() {
        let rect = CGRect(x: 10.7, y: 20.9, width: 80.5, height: 160.3)
        // Int() truncates: left=10, top=20, right=Int(10.7+80.5)=91, bottom=Int(20.9+160.3)=181
        XCTAssertEqual(boundsString(from: rect), "[10,20][91,181]")
    }

    func testBoundsStringLargeValues() {
        let rect = CGRect(x: 0, y: 0, width: 1080, height: 1920)
        XCTAssertEqual(boundsString(from: rect), "[0,0][1080,1920]")
    }

    // MARK: - intParam

    func testIntParamIntValue() {
        let params: [String: Any] = ["x": 42]
        XCTAssertEqual(intParam(params, "x"), 42)
    }

    func testIntParamDoubleValue() {
        let params: [String: Any] = ["x": 1.0]
        XCTAssertEqual(intParam(params, "x"), 1)
    }

    func testIntParamDoubleTruncates() {
        let params: [String: Any] = ["x": 1.9]
        XCTAssertEqual(intParam(params, "x"), 1)
    }

    func testIntParamStringValue() {
        let params: [String: Any] = ["x": "42"]
        XCTAssertEqual(intParam(params, "x"), 42)
    }

    func testIntParamInvalidStringReturnsNil() {
        let params: [String: Any] = ["x": "notanint"]
        XCTAssertNil(intParam(params, "x"))
    }

    func testIntParamMissingKeyReturnsNil() {
        let params: [String: Any] = ["y": 10]
        XCTAssertNil(intParam(params, "x"))
    }

    func testIntParamNilParamsReturnsNil() {
        XCTAssertNil(intParam(nil, "x"))
    }

    func testIntParamEmptyDictReturnsNil() {
        let params: [String: Any] = [:]
        XCTAssertNil(intParam(params, "x"))
    }

    func testIntParamNegativeDoubleTruncatesTowardZero() {
        let params: [String: Any] = ["x": -1.9]
        XCTAssertEqual(intParam(params, "x"), -1)
    }

    func testIntParamBoolReturnsNil() {
        let params: [String: Any] = ["x": true]
        // Bool values are not castable to Int using `as? Int`, so this should return nil
        XCTAssertNil(intParam(params, "x"))
    }
}
