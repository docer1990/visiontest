import XCTest

final class JsonRpcModelsTests: XCTestCase {

    // MARK: - JsonRpcRequest.parse

    func testParseValidRequest() {
        let json = """
        {"jsonrpc":"2.0","method":"ui.dumpHierarchy","params":{"bundleId":"com.example"},"id":1}
        """.data(using: .utf8)!

        let request = JsonRpcRequest.parse(from: json)
        XCTAssertNotNil(request)
        XCTAssertEqual(request?.jsonrpc, "2.0")
        XCTAssertEqual(request?.method, "ui.dumpHierarchy")
        XCTAssertEqual(request?.params?["bundleId"] as? String, "com.example")
        XCTAssertEqual(request?.id as? Int, 1)
    }

    func testParseMissingMethodReturnsNil() {
        let json = """
        {"jsonrpc":"2.0","params":{},"id":1}
        """.data(using: .utf8)!

        XCTAssertNil(JsonRpcRequest.parse(from: json))
    }

    func testParseEmptyDataReturnsNil() {
        XCTAssertNil(JsonRpcRequest.parse(from: Data()))
    }

    func testParseMalformedJsonReturnsNil() {
        let json = "not json".data(using: .utf8)!
        XCTAssertNil(JsonRpcRequest.parse(from: json))
    }

    func testParseOptionalParamsNilWhenAbsent() {
        let json = """
        {"jsonrpc":"2.0","method":"device.getInfo"}
        """.data(using: .utf8)!

        let request = JsonRpcRequest.parse(from: json)
        XCTAssertNotNil(request)
        XCTAssertNil(request?.params)
        XCTAssertNil(request?.id)
    }

    func testParseIdAsString() {
        let json = """
        {"jsonrpc":"2.0","method":"test","id":"abc-123"}
        """.data(using: .utf8)!

        let request = JsonRpcRequest.parse(from: json)
        XCTAssertEqual(request?.id as? String, "abc-123")
    }

    func testParseIdAsInt() {
        let json = """
        {"jsonrpc":"2.0","method":"test","id":42}
        """.data(using: .utf8)!

        let request = JsonRpcRequest.parse(from: json)
        XCTAssertEqual(request?.id as? Int, 42)
    }

    func testParseDefaultsJsonrpcToEmptyWhenMissing() {
        let json = """
        {"method":"test"}
        """.data(using: .utf8)!

        let request = JsonRpcRequest.parse(from: json)
        XCTAssertNotNil(request)
        XCTAssertEqual(request?.jsonrpc, "")
    }

    // MARK: - JsonRpcError factory methods

    func testParseError() {
        let error = JsonRpcError.parseError()
        XCTAssertEqual(error.code, -32700)
        XCTAssertEqual(error.message, "Parse error")
        XCTAssertNil(error.data)
    }

    func testParseErrorWithData() {
        let error = JsonRpcError.parseError("detail")
        XCTAssertEqual(error.code, -32700)
        XCTAssertEqual(error.data as? String, "detail")
    }

    func testInvalidRequest() {
        let error = JsonRpcError.invalidRequest()
        XCTAssertEqual(error.code, -32600)
        XCTAssertEqual(error.message, "Invalid Request")
    }

    func testMethodNotFound() {
        let error = JsonRpcError.methodNotFound("foo.bar")
        XCTAssertEqual(error.code, -32601)
        XCTAssertTrue(error.message.contains("foo.bar"))
    }

    func testInvalidParams() {
        let error = JsonRpcError.invalidParams()
        XCTAssertEqual(error.code, -32602)
        XCTAssertEqual(error.message, "Invalid params")
    }

    func testInternalError() {
        let error = JsonRpcError.internalError()
        XCTAssertEqual(error.code, -32603)
        XCTAssertEqual(error.message, "Internal error")
    }

    func testXcuiTestError() {
        let error = JsonRpcError.xcuiTestError("custom message")
        XCTAssertEqual(error.code, -32000)
        XCTAssertEqual(error.message, "custom message")
        XCTAssertNil(error.data)
    }

    // MARK: - JsonRpcError.toDictionary

    func testToDictionaryContainsCodeAndMessage() {
        let error = JsonRpcError.parseError()
        let dict = error.toDictionary()
        XCTAssertEqual(dict["code"] as? Int, -32700)
        XCTAssertEqual(dict["message"] as? String, "Parse error")
        XCTAssertNil(dict["data"])
    }

    func testToDictionaryIncludesDataWhenNonNil() {
        let error = JsonRpcError.parseError("detail info")
        let dict = error.toDictionary()
        XCTAssertEqual(dict["data"] as? String, "detail info")
    }

    // MARK: - JsonRpcResponse

    func testSuccessResponse() {
        let result: [String: Any] = ["key": "value"]
        let response = JsonRpcResponse.success(result: result, id: 1)

        XCTAssertEqual(response["jsonrpc"] as? String, "2.0")
        XCTAssertNotNil(response["result"])
        XCTAssertEqual(response["id"] as? Int, 1)
        XCTAssertNil(response["error"])
    }

    func testSuccessResponseNilIdBecomesNSNull() {
        let response = JsonRpcResponse.success(result: "ok", id: nil)
        XCTAssertTrue(response["id"] is NSNull)
    }

    func testErrorResponse() {
        let error = JsonRpcError.methodNotFound("test.method")
        let response = JsonRpcResponse.error(error, id: 5)

        XCTAssertEqual(response["jsonrpc"] as? String, "2.0")
        XCTAssertNotNil(response["error"])
        XCTAssertNil(response["result"])
        XCTAssertEqual(response["id"] as? Int, 5)

        let errorDict = response["error"] as? [String: Any]
        XCTAssertEqual(errorDict?["code"] as? Int, -32601)
    }

    func testErrorResponseNilIdBecomesNSNull() {
        let error = JsonRpcError.internalError()
        let response = JsonRpcResponse.error(error, id: nil)
        XCTAssertTrue(response["id"] is NSNull)
    }

    // MARK: - Error code constants

    func testErrorCodeConstants() {
        XCTAssertEqual(JsonRpcError.PARSE_ERROR, -32700)
        XCTAssertEqual(JsonRpcError.INVALID_REQUEST, -32600)
        XCTAssertEqual(JsonRpcError.METHOD_NOT_FOUND, -32601)
        XCTAssertEqual(JsonRpcError.INVALID_PARAMS, -32602)
        XCTAssertEqual(JsonRpcError.INTERNAL_ERROR, -32603)
        XCTAssertEqual(JsonRpcError.XCUITEST_ERROR, -32000)
        XCTAssertEqual(JsonRpcError.DEVICE_ERROR, -32001)
    }
}
