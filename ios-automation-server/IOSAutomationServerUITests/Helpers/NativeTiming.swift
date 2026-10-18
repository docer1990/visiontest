import Foundation

struct NativeTimingSpan: Equatable {
    let spanId: String
    let parentSpanId: String?
    let stage: String
    let startOffsetNs: UInt64
    let durationNs: UInt64
    let outcome: String
    let metrics: [String: UInt64]

    fileprivate var json: [String: Any] {
        var value: [String: Any] = [
            "spanId": spanId,
            "parentSpanId": parentSpanId as Any? ?? NSNull(),
            "stage": stage,
            "startOffsetNs": NSNumber(value: startOffsetNs),
            "durationNs": NSNumber(value: durationNs),
            "outcome": outcome
        ]
        if !metrics.isEmpty {
            value["metrics"] = metrics.mapValues(NSNumber.init(value:))
        }
        return value
    }
}

struct NativeTimingSnapshot {
    let version: Int
    let invocationId: String
    let requestSpanId: String
    let spans: [NativeTimingSpan]
    let missingStages: [String]

    fileprivate var json: [String: Any] {
        [
            "version": version,
            "invocationId": invocationId,
            "requestSpanId": requestSpanId,
            "spans": spans.map(\.json),
            "missingStages": missingStages
        ]
    }
}

/// Request-owned, Foundation-only timing recorder shared by the UI and unit-test targets.
final class NativeTiming {
    static let stages = [
        "request", "decode", "queue_wait", "operation", "ui_sync", "poll",
        "poll_wait", "screenshot_capture", "png_encode", "base64_encode",
        "response_encode", "screenshot_capture_encode"
    ]

    private static let metrics = Set(["requestBytes", "responseBytes", "decodedBytes", "pollCount", "waitNs"])
    private static let maxSpans = 32
    private static let maxHeaderBytes = 8_192
    private static let maxJSONInteger = UInt64(Int64.max)

    let invocationId: String
    let requestSpanId: String
    private let nowNs: () -> UInt64
    private let lock = NSLock()
    private let originNs: UInt64
    private var records: [NativeTimingSpan] = []
    private var requestMetrics: [String: UInt64] = [:]
    private struct Polling {
        let startOffsetNs: UInt64
        var count: UInt64 = 0
        var waitNs: UInt64 = 0
    }
    private var polling: Polling?
    private var requestOutcome = "returned"
    private var frozen: NativeTimingSnapshot?

    init(invocationId: String, requestSpanId: String, nowNs: @escaping () -> UInt64 = {
        DispatchTime.now().uptimeNanoseconds
    }) {
        self.invocationId = invocationId
        self.requestSpanId = requestSpanId
        self.nowNs = nowNs
        self.originNs = nowNs()
    }

    @discardableResult
    func measure<T>(_ stage: String, _ block: () throws -> T) rethrows -> T {
        guard Self.stages.contains(stage), stage != "request" else { return try block() }
        let spanId = Self.randomId()
        let start = Self.relative(nowNs(), origin: originNs)
        do {
            let value = try block()
            append(stage: stage, spanId: spanId, start: start, end: Self.relative(nowNs(), origin: originNs), outcome: "returned")
            return value
        } catch {
            append(stage: stage, spanId: spanId, start: start, end: Self.relative(nowNs(), origin: originNs), outcome: "thrown")
            throw error
        }
    }

    func addMetric(_ name: String, value: UInt64, toSpanId spanId: String? = nil) {
        guard Self.metrics.contains(name), value <= Self.maxJSONInteger else { return }
        lock.lock()
        defer { lock.unlock() }
        guard frozen == nil else { return }
        guard let index = records.lastIndex(where: { spanId == nil || $0.spanId == spanId }) else {
            guard spanId == nil else { return }
            let (sum, overflow) = (requestMetrics[name] ?? 0).addingReportingOverflow(value)
            requestMetrics[name] = overflow ? Self.maxJSONInteger : min(sum, Self.maxJSONInteger)
            return
        }
        var updated = records[index]
        var values = updated.metrics
        let (sum, overflow) = (values[name] ?? 0).addingReportingOverflow(value)
        values[name] = overflow ? Self.maxJSONInteger : min(sum, Self.maxJSONInteger)
        updated = NativeTimingSpan(spanId: updated.spanId, parentSpanId: updated.parentSpanId,
                                   stage: updated.stage, startOffsetNs: updated.startOffsetNs,
                                   durationNs: updated.durationNs, outcome: updated.outcome, metrics: values)
        records[index] = updated
    }

    func snapshot() -> NativeTimingSnapshot {
        lock.lock()
        defer { lock.unlock() }
        if let frozen { return frozen }
        let elapsed = Self.relative(nowNs(), origin: originNs)
        var spans = [NativeTimingSpan(spanId: requestSpanId, parentSpanId: nil, stage: "request",
                                      startOffsetNs: 0, durationNs: elapsed, outcome: requestOutcome, metrics: requestMetrics)]
        spans.append(contentsOf: records)
        let measured = Set(spans.map(\.stage))
        let result = NativeTimingSnapshot(version: 1, invocationId: invocationId, requestSpanId: requestSpanId,
                                          spans: Array(spans.prefix(Self.maxSpans)),
                                          missingStages: Self.stages.filter { !measured.contains($0) })
        frozen = result
        return result
    }

    func encodeHeader() -> String? {
        let snapshot = snapshot()
        guard let data = try? JSONSerialization.data(withJSONObject: snapshot.json, options: [.sortedKeys]) else { return nil }
        let encoded = data.base64EncodedString()
            .replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_")
            .replacingOccurrences(of: "=", with: "")
        return encoded.utf8.count <= Self.maxHeaderBytes ? encoded : nil
    }

    func recordQueueWait(startedAt startNs: UInt64) {
        let now = nowNs()
        append(stage: "queue_wait", spanId: Self.randomId(), start: Self.relative(startNs, origin: originNs),
               end: Self.relative(now, origin: originNs), outcome: "returned")
    }

    func markRequestOutcome(_ outcome: String) {
        guard ["returned", "thrown", "timeout", "cancelled"].contains(outcome) else { return }
        lock.lock()
        defer { lock.unlock() }
        guard frozen == nil else { return }
        requestOutcome = outcome
    }

    func beginPolling() {
        lock.lock()
        defer { lock.unlock() }
        guard frozen == nil, polling == nil else { return }
        polling = Polling(startOffsetNs: Self.relative(nowNs(), origin: originNs))
    }

    func endPolling() {
        lock.lock()
        defer { lock.unlock() }
        guard let polling else { return }
        self.polling = nil
        guard frozen == nil, records.count + 1 < Self.maxSpans else { return }
        let end = Self.relative(nowNs(), origin: originNs)
        var metrics = ["pollCount": polling.count]
        if polling.waitNs > 0 { metrics["waitNs"] = polling.waitNs }
        records.append(NativeTimingSpan(spanId: Self.randomId(), parentSpanId: requestSpanId, stage: "poll",
                                        startOffsetNs: polling.startOffsetNs,
                                        durationNs: end >= polling.startOffsetNs ? end - polling.startOffsetNs : 0,
                                        outcome: "returned", metrics: metrics))
    }

    func recordPollAttempt() {
        lock.lock()
        defer { lock.unlock() }
        guard frozen == nil, let polling else { return }
        self.polling?.count = min(polling.count + 1, Self.maxJSONInteger)
    }

    func recordPollWait(_ seconds: TimeInterval) {
        guard seconds.isFinite, seconds > 0 else { return }
        let sample = UInt64(min(seconds * 1_000_000_000, Double(Self.maxJSONInteger)))
        lock.lock()
        defer { lock.unlock() }
        guard frozen == nil, let polling else { return }
        let (sum, overflow) = polling.waitNs.addingReportingOverflow(sample)
        self.polling?.waitNs = overflow ? Self.maxJSONInteger : min(sum, Self.maxJSONInteger)
    }

    static func parseRequestHeader(_ value: String?) -> (invocationId: String, requestSpanId: String)? {
        guard let value, value.utf8.count <= Self.maxHeaderBytes else { return nil }
        let fields = value.split(separator: ";", omittingEmptySubsequences: false)
        guard fields.count == 3, fields[0] == "v1", isId(String(fields[1])), isId(String(fields[2])) else { return nil }
        return (String(fields[1]), String(fields[2]))
    }

    private func append(stage: String, spanId: String, start: UInt64, end: UInt64, outcome: String) {
        lock.lock()
        defer { lock.unlock() }
        guard frozen == nil, records.count + 1 < Self.maxSpans else { return }
        records.append(NativeTimingSpan(spanId: spanId, parentSpanId: requestSpanId, stage: stage,
                                        startOffsetNs: start, durationNs: end >= start ? end - start : 0,
                                        outcome: outcome, metrics: [:]))
    }

    private static func relative(_ value: UInt64, origin: UInt64) -> UInt64 {
        min(value >= origin ? value - origin : 0, maxJSONInteger)
    }

    private static func randomId() -> String {
        UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
    }

    private static func isId(_ value: String) -> Bool {
        value.utf8.count == 32 && value.utf8.allSatisfy { (48...57).contains($0) || (97...102).contains($0) }
    }
}
