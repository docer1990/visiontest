import XCTest

final class AutomationModelsTests: XCTestCase {

    // MARK: - UiHierarchyResult

    func testUiHierarchyResultSuccess() {
        let result = UiHierarchyResult(success: true, hierarchy: "<xml/>", error: nil)
        let dict = result.toDictionary()
        XCTAssertEqual(dict["success"] as? Bool, true)
        XCTAssertEqual(dict["hierarchy"] as? String, "<xml/>")
        XCTAssertNil(dict["error"])
    }

    func testUiHierarchyResultNilFieldsExcluded() {
        let result = UiHierarchyResult(success: false, hierarchy: nil, error: "timeout")
        let dict = result.toDictionary()
        XCTAssertEqual(dict["success"] as? Bool, false)
        XCTAssertNil(dict["hierarchy"])
        XCTAssertEqual(dict["error"] as? String, "timeout")
    }

    // MARK: - DeviceInfoResult

    func testDeviceInfoResultAllFields() {
        let result = DeviceInfoResult(
            displayWidth: 390,
            displayHeight: 844,
            displayRotation: 0,
            productName: "iPhone 16",
            osVersion: "18.0",
            success: true,
            error: nil
        )
        let dict = result.toDictionary()
        XCTAssertEqual(dict["displayWidth"] as? Int, 390)
        XCTAssertEqual(dict["displayHeight"] as? Int, 844)
        XCTAssertEqual(dict["displayRotation"] as? Int, 0)
        XCTAssertEqual(dict["productName"] as? String, "iPhone 16")
        XCTAssertEqual(dict["osVersion"] as? String, "18.0")
        XCTAssertEqual(dict["success"] as? Bool, true)
        XCTAssertNil(dict["error"])
    }

    func testDeviceInfoResultWithError() {
        let result = DeviceInfoResult(
            displayWidth: 0, displayHeight: 0, displayRotation: 0,
            productName: "", osVersion: "", success: false, error: "failed"
        )
        let dict = result.toDictionary()
        XCTAssertEqual(dict["error"] as? String, "failed")
    }

    // MARK: - OperationResult

    func testOperationResultSuccess() {
        let result = OperationResult(success: true, error: nil)
        let dict = result.toDictionary()
        XCTAssertEqual(dict["success"] as? Bool, true)
        XCTAssertNil(dict["error"])
    }

    func testOperationResultError() {
        let result = OperationResult(success: false, error: "tap failed")
        let dict = result.toDictionary()
        XCTAssertEqual(dict["success"] as? Bool, false)
        XCTAssertEqual(dict["error"] as? String, "tap failed")
    }

    // MARK: - ElementResult

    func testElementResultFoundFalseMinimalFields() {
        let result = ElementResult(
            found: false, text: nil, identifier: nil, elementType: nil,
            label: nil, value: nil, bounds: nil, isEnabled: nil, error: nil
        )
        let dict = result.toDictionary()
        XCTAssertEqual(dict["found"] as? Bool, false)
        XCTAssertNil(dict["text"])
        XCTAssertNil(dict["resourceId"])
        XCTAssertNil(dict["className"])
        XCTAssertNil(dict["contentDescription"])
        XCTAssertNil(dict["value"])
        XCTAssertNil(dict["bounds"])
        XCTAssertNil(dict["isEnabled"])
    }

    func testElementResultKeyMappings() {
        let result = ElementResult(
            found: true,
            text: "Login",
            identifier: "btn_login",
            elementType: "Button",
            label: "Login Button",
            value: "enabled",
            bounds: "[0,0][100,50]",
            isEnabled: true,
            error: nil
        )
        let dict = result.toDictionary()
        XCTAssertEqual(dict["found"] as? Bool, true)
        XCTAssertEqual(dict["text"] as? String, "Login")
        XCTAssertEqual(dict["resourceId"] as? String, "btn_login")
        XCTAssertEqual(dict["className"] as? String, "Button")
        XCTAssertEqual(dict["contentDescription"] as? String, "Login Button")
        XCTAssertEqual(dict["value"] as? String, "enabled")
        XCTAssertEqual(dict["bounds"] as? String, "[0,0][100,50]")
        XCTAssertEqual(dict["isEnabled"] as? Bool, true)
    }

    // MARK: - InteractiveElement

    func testInteractiveElementOptionalFieldsExcluded() {
        let element = InteractiveElement(
            text: nil, identifier: nil, elementType: nil, label: nil,
            value: nil, bounds: nil, centerX: nil, centerY: nil, isEnabled: true
        )
        let dict = element.toDictionary()
        XCTAssertEqual(dict["isEnabled"] as? Bool, true)
        XCTAssertNil(dict["text"])
        XCTAssertNil(dict["resourceId"])
        XCTAssertNil(dict["className"])
        XCTAssertNil(dict["contentDescription"])
        XCTAssertNil(dict["centerX"])
        XCTAssertNil(dict["centerY"])
    }

    func testInteractiveElementAllFields() {
        let element = InteractiveElement(
            text: "Submit",
            identifier: "btn_submit",
            elementType: "Button",
            label: "Submit Form",
            value: nil,
            bounds: "[10,20][110,70]",
            centerX: 60,
            centerY: 45,
            isEnabled: true
        )
        let dict = element.toDictionary()
        XCTAssertEqual(dict["text"] as? String, "Submit")
        XCTAssertEqual(dict["resourceId"] as? String, "btn_submit")
        XCTAssertEqual(dict["className"] as? String, "Button")
        XCTAssertEqual(dict["contentDescription"] as? String, "Submit Form")
        XCTAssertEqual(dict["bounds"] as? String, "[10,20][110,70]")
        XCTAssertEqual(dict["centerX"] as? Int, 60)
        XCTAssertEqual(dict["centerY"] as? Int, 45)
    }

    // MARK: - InteractiveElementsResult

    func testInteractiveElementsResultEmpty() {
        let result = InteractiveElementsResult(success: true, elements: [], count: 0, error: nil)
        let dict = result.toDictionary()
        XCTAssertEqual(dict["success"] as? Bool, true)
        XCTAssertEqual(dict["count"] as? Int, 0)
        let elements = dict["elements"] as? [[String: Any]]
        XCTAssertNotNil(elements)
        XCTAssertEqual(elements?.count, 0)
    }

    func testInteractiveElementsResultWithElements() {
        let elem = InteractiveElement(
            text: "OK", identifier: nil, elementType: "Button",
            label: nil, value: nil, bounds: "[0,0][50,30]",
            centerX: 25, centerY: 15, isEnabled: true
        )
        let result = InteractiveElementsResult(success: true, elements: [elem], count: 1, error: nil)
        let dict = result.toDictionary()
        XCTAssertEqual(dict["count"] as? Int, 1)
        let elements = dict["elements"] as? [[String: Any]]
        XCTAssertEqual(elements?.count, 1)
        XCTAssertEqual(elements?.first?["text"] as? String, "OK")
    }

    func testInteractiveElementsResultWithError() {
        let result = InteractiveElementsResult(success: false, elements: [], count: 0, error: "snapshot failed")
        let dict = result.toDictionary()
        XCTAssertEqual(dict["error"] as? String, "snapshot failed")
    }

    // MARK: - SwipeDirection

    func testSwipeDirectionRawValues() {
        XCTAssertEqual(SwipeDirection.up.rawValue, "UP")
        XCTAssertEqual(SwipeDirection.down.rawValue, "DOWN")
        XCTAssertEqual(SwipeDirection.left.rawValue, "LEFT")
        XCTAssertEqual(SwipeDirection.right.rawValue, "RIGHT")
    }

    func testSwipeDirectionInitFromRawValue() {
        XCTAssertEqual(SwipeDirection(rawValue: "UP"), .up)
        XCTAssertEqual(SwipeDirection(rawValue: "DOWN"), .down)
        XCTAssertEqual(SwipeDirection(rawValue: "LEFT"), .left)
        XCTAssertEqual(SwipeDirection(rawValue: "RIGHT"), .right)
        XCTAssertNil(SwipeDirection(rawValue: "DIAGONAL"))
    }

    // MARK: - SwipeDistance

    func testSwipeDistancePercent() {
        XCTAssertEqual(SwipeDistance.short.percent, 0.20, accuracy: 0.001)
        XCTAssertEqual(SwipeDistance.medium.percent, 0.40, accuracy: 0.001)
        XCTAssertEqual(SwipeDistance.long.percent, 0.60, accuracy: 0.001)
    }

    func testSwipeDistanceInvalidRawValue() {
        XCTAssertNil(SwipeDistance(rawValue: "TINY"))
    }

    func testSwipeDistanceRawValues() {
        XCTAssertEqual(SwipeDistance.short.rawValue, "SHORT")
        XCTAssertEqual(SwipeDistance.medium.rawValue, "MEDIUM")
        XCTAssertEqual(SwipeDistance.long.rawValue, "LONG")
    }

    // MARK: - SwipeSpeed

    func testSwipeSpeedDuration() {
        XCTAssertEqual(SwipeSpeed.slow.duration, 2.5, accuracy: 0.001)
        XCTAssertEqual(SwipeSpeed.normal.duration, 1.0, accuracy: 0.001)
        XCTAssertEqual(SwipeSpeed.fast.duration, 0.25, accuracy: 0.001)
    }

    func testSwipeSpeedInvalidRawValue() {
        XCTAssertNil(SwipeSpeed(rawValue: "TURBO"))
    }

    func testSwipeSpeedRawValues() {
        XCTAssertEqual(SwipeSpeed.slow.rawValue, "SLOW")
        XCTAssertEqual(SwipeSpeed.normal.rawValue, "NORMAL")
        XCTAssertEqual(SwipeSpeed.fast.rawValue, "FAST")
    }
}
