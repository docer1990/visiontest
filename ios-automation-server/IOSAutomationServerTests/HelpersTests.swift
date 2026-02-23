import XCTest
import CoreGraphics

final class HelpersTests: XCTestCase {

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

    func testIntParamBoolConvertsToOne() {
        let params: [String: Any] = ["x": true]
        // Bool is bridged to NSNumber in Swift, so it is treated as Int(1)
        XCTAssertEqual(intParam(params, "x"), 1)
    }
}
