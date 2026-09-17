import Foundation

// MARK: - JSON-RPC 2.0 Models

enum JsonRpcParamsState {
    case absent
    case object([String: Any])
    case invalid
}

struct JsonRpcRequest {
    let jsonrpc: String
    let method: String
    let paramsState: JsonRpcParamsState
    let id: Any?

    var params: [String: Any]? {
        guard case .object(let params) = paramsState else { return nil }
        return params
    }

    static func parse(from data: Data) -> JsonRpcRequest? {
        guard let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            return nil
        }
        guard let method = json["method"] as? String else {
            return nil
        }
        let paramsState: JsonRpcParamsState
        if let rawParams = json["params"] {
            if let params = rawParams as? [String: Any] {
                paramsState = .object(params)
            } else {
                paramsState = .invalid
            }
        } else {
            paramsState = .absent
        }
        return JsonRpcRequest(
            jsonrpc: json["jsonrpc"] as? String ?? "",
            method: method,
            paramsState: paramsState,
            id: json["id"]
        )
    }
}

struct JsonRpcError {
    let code: Int
    let message: String
    let data: Any?

    // Standard JSON-RPC error codes
    static let PARSE_ERROR = -32700
    static let INVALID_REQUEST = -32600
    static let METHOD_NOT_FOUND = -32601
    static let INVALID_PARAMS = -32602
    static let INTERNAL_ERROR = -32603

    // Custom error codes
    static let XCUITEST_ERROR = -32000
    static let DEVICE_ERROR = -32001

    static func parseError(_ data: Any? = nil) -> JsonRpcError {
        JsonRpcError(code: PARSE_ERROR, message: "Parse error", data: data)
    }

    static func invalidRequest(_ data: Any? = nil) -> JsonRpcError {
        JsonRpcError(code: INVALID_REQUEST, message: "Invalid Request", data: data)
    }

    static func methodNotFound(_ method: String) -> JsonRpcError {
        JsonRpcError(code: METHOD_NOT_FOUND, message: "Method not found: \(method)", data: nil)
    }

    static func invalidParams(_ data: Any? = nil) -> JsonRpcError {
        JsonRpcError(code: INVALID_PARAMS, message: "Invalid params", data: data)
    }

    static func internalError(_ data: Any? = nil) -> JsonRpcError {
        JsonRpcError(code: INTERNAL_ERROR, message: "Internal error", data: data)
    }

    static func xcuiTestError(_ message: String) -> JsonRpcError {
        JsonRpcError(code: XCUITEST_ERROR, message: message, data: nil)
    }

    func toDictionary() -> [String: Any] {
        var dict: [String: Any] = ["code": code, "message": message]
        if let data = data {
            dict["data"] = data
        }
        return dict
    }
}

enum JsonRpcResponse {
    static func success(result: Any, id: Any?) -> [String: Any] {
        var response: [String: Any] = ["jsonrpc": "2.0", "result": result]
        response["id"] = id ?? NSNull()
        return response
    }

    static func error(_ error: JsonRpcError, id: Any?) -> [String: Any] {
        var response: [String: Any] = ["jsonrpc": "2.0", "error": error.toDictionary()]
        response["id"] = id ?? NSNull()
        return response
    }
}

class InvalidParamsException: Error {
    let message: String
    init(_ message: String) {
        self.message = message
    }
}
