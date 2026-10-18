import XCTest

final class NativeTimingTests: XCTestCase {
    func testNativeDurationUsesInjectedMonotonicClock() throws {
        var now: UInt64 = 100
        let timing = NativeTiming(
            invocationId: String(repeating: "a", count: 32),
            requestSpanId: String(repeating: "b", count: 32),
            nowNs: { now }
        )
        timing.measure("operation") { now = 140 }
        let span = try XCTUnwrap(timing.snapshot().spans.first { $0.stage == "operation" })
        XCTAssertEqual(span.durationNs, 40)
    }

    func testSnapshotIsFrozenAndEncodesBoundedWireEnvelope() throws {
        var now: UInt64 = 10
        let timing = NativeTiming(invocationId: String(repeating: "a", count: 32),
                                  requestSpanId: String(repeating: "b", count: 32), nowNs: { now })
        timing.measure("operation") { now = 25 }
        let first = timing.snapshot()
        now = 90
        timing.measure("ui_sync") { now = 120 }
        let second = timing.snapshot()
        XCTAssertEqual(first.spans, second.spans)
        XCTAssertEqual(first.spans.count, 2)
        XCTAssertEqual(first.spans[1].durationNs, 15)
        XCTAssertTrue(first.missingStages.contains("ui_sync"))
        let encoded = try XCTUnwrap(timing.encodeHeader())
        let data = try XCTUnwrap(Data(base64Encoded: encoded.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/") + String(repeating: "=", count: (4 - encoded.count % 4) % 4)))
        let envelope = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        XCTAssertEqual(envelope["version"] as? Int, 1)
        XCTAssertEqual(envelope["invocationId"] as? String, String(repeating: "a", count: 32))
    }

    func testThrownOperationIsRecordedAndUnsupportedStagesAreIgnored() {
        enum Failure: Error { case expected }
        var now: UInt64 = 3
        let timing = NativeTiming(invocationId: String(repeating: "a", count: 32),
                                  requestSpanId: String(repeating: "b", count: 32), nowNs: { now })
        XCTAssertThrowsError(try timing.measure("operation") { now = 9; throw Failure.expected })
        timing.measure("not-a-stage") { now = 12 }
        let span = timing.snapshot().spans.last
        XCTAssertEqual(span?.outcome, "thrown")
        XCTAssertEqual(span?.durationNs, 6)
    }

    func testTraceRequestHeaderRequiresVersionAndLowercaseHexIds() {
        let valid = "v1;\(String(repeating: "a", count: 32));\(String(repeating: "b", count: 32))"
        XCTAssertNotNil(NativeTiming.parseRequestHeader(valid))
        XCTAssertNil(NativeTiming.parseRequestHeader("v2;\(String(repeating: "a", count: 32));\(String(repeating: "b", count: 32))"))
        XCTAssertNil(NativeTiming.parseRequestHeader("v1;\(String(repeating: "A", count: 32));\(String(repeating: "b", count: 32))"))
        XCTAssertNil(NativeTiming.parseRequestHeader(String(repeating: "x", count: 8_193)))
        XCTAssertNil(NativeTiming.parseRequestHeader(nil))
    }

    func testPollAttemptsAndWaitsAreAggregated() throws {
        let timing = NativeTiming(invocationId: String(repeating: "a", count: 32),
                                  requestSpanId: String(repeating: "b", count: 32))
        timing.beginPolling()
        timing.recordPollAttempt()
        timing.recordPollAttempt()
        timing.recordPollWait(0.001)
        timing.endPolling()
        let snapshot = timing.snapshot()
        let poll = try XCTUnwrap(snapshot.spans.first { $0.stage == "poll" })
        XCTAssertEqual(poll.metrics["pollCount"], 2)
        XCTAssertEqual(poll.metrics["waitNs"], 1_000_000)
        XCTAssertEqual(snapshot.spans.filter { $0.stage == "poll" }.count, 1)
        XCTAssertFalse(snapshot.spans.contains { $0.stage == "poll_wait" })
    }

    func testAggregateSpanLimitIsThirtyTwo() {
        var now: UInt64 = 0
        let timing = NativeTiming(invocationId: String(repeating: "a", count: 32),
                                  requestSpanId: String(repeating: "b", count: 32), nowNs: { now })
        for _ in 0..<40 { timing.measure("poll") { now += 1 } }
        XCTAssertEqual(timing.snapshot().spans.count, 32)
    }

    func testConcurrentRequestsWithSameRpcIdKeepIndependentCorrelation() throws {
        let first = NativeTiming(invocationId: String(repeating: "a", count: 32),
                                 requestSpanId: String(repeating: "b", count: 32))
        let second = NativeTiming(invocationId: String(repeating: "c", count: 32),
                                  requestSpanId: String(repeating: "d", count: 32))
        first.measure("decode") {}
        second.measure("operation") {}
        let firstEnvelope = try decodedHeader(first.encodeHeader())
        let secondEnvelope = try decodedHeader(second.encodeHeader())
        XCTAssertEqual(firstEnvelope["invocationId"] as? String, String(repeating: "a", count: 32))
        XCTAssertEqual(firstEnvelope["requestSpanId"] as? String, String(repeating: "b", count: 32))
        XCTAssertEqual(secondEnvelope["invocationId"] as? String, String(repeating: "c", count: 32))
        XCTAssertEqual(secondEnvelope["requestSpanId"] as? String, String(repeating: "d", count: 32))
    }

    func testOverlappingPollingKeepsMetricsIndependentAfterOneRequestFreezes() throws {
        var now: UInt64 = 0
        let first = NativeTiming(invocationId: String(repeating: "a", count: 32),
                                 requestSpanId: String(repeating: "b", count: 32), nowNs: { now })
        let second = NativeTiming(invocationId: String(repeating: "c", count: 32),
                                  requestSpanId: String(repeating: "d", count: 32), nowNs: { now })
        first.beginPolling()
        first.recordPollAttempt()
        first.recordPollWait(0.001)
        second.beginPolling()
        second.recordPollAttempt()
        second.recordPollAttempt()
        second.recordPollWait(0.002)
        now = 3_000_000
        let frozen = first.snapshot()
        second.endPolling()
        first.recordPollAttempt()
        first.recordPollWait(0.003)
        first.endPolling()

        XCTAssertEqual(first.snapshot().spans, frozen.spans)
        XCTAssertTrue(frozen.missingStages.contains("poll"))
        let poll = try XCTUnwrap(second.snapshot().spans.first { $0.stage == "poll" })
        XCTAssertEqual(poll.metrics, ["pollCount": 2, "waitNs": 2_000_000])
        XCTAssertEqual(poll.durationNs, 3_000_000)
    }

    func testCompletionAfterSnapshotCannotMutateFrozenResponse() throws {
        let timing = NativeTiming(invocationId: String(repeating: "a", count: 32),
                                  requestSpanId: String(repeating: "b", count: 32))
        let entered = DispatchSemaphore(value: 0)
        let release = DispatchSemaphore(value: 0)
        let finished = expectation(description: "late operation finished")
        DispatchQueue.global().async {
            timing.measure("operation") {
                entered.signal()
                release.wait()
            }
            finished.fulfill()
        }
        XCTAssertEqual(entered.wait(timeout: .now() + 2), .success)
        let before = timing.snapshot()
        release.signal()
        wait(for: [finished], timeout: 1)
        XCTAssertEqual(before.spans.count, 1)
        XCTAssertEqual(timing.snapshot().spans, before.spans)
        XCTAssertEqual(timing.snapshot().missingStages, before.missingStages)
    }

    private func decodedHeader(_ header: String?) throws -> [String: Any] {
        let header = try XCTUnwrap(header)
        let padded = header.replacingOccurrences(of: "-", with: "+")
            .replacingOccurrences(of: "_", with: "/")
            + String(repeating: "=", count: (4 - header.count % 4) % 4)
        let data = try XCTUnwrap(Data(base64Encoded: padded))
        return try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }
}
