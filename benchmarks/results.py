import json
import math
from pathlib import Path


_STATUSES = ("passed", "failed", "timeout", "setup_failed")


def _population(samples):
    durations = []
    successful = []
    counts = {status: 0 for status in _STATUSES}
    for sample in samples:
        status = sample.get("status")
        if not isinstance(status, str) or status not in counts:
            raise ValueError(f"unsupported sample status: {status}")
        counts[status] += 1
        duration = sample.get("duration_ns")
        if status == "setup_failed" and duration is not None:
            raise ValueError("setup_failed samples must not have an operation duration")
        if duration is not None:
            if isinstance(duration, bool) or not isinstance(duration, int) or duration < 0:
                raise ValueError("duration_ns must be a nonnegative integer or null")
            durations.append(duration)
            if status == "passed":
                successful.append(duration)
        elif status == "setup_failed":
            continue
    boundaries = {}
    for boundary in ('operation', 'process'):
        values = [sample.get(boundary + '_duration_ns') for sample in samples]
        if any(value is not None and (type(value) is not int or value < 0) for value in values):
            raise ValueError(boundary + '_duration_ns must be a nonnegative integer or null')
        boundaries[boundary + '_timed'] = _percentiles([value for value in values if value is not None])
    return {
        **boundaries,
        "attempts": len(samples),
        **counts,
        "all_timed": _percentiles(durations),
        "successful": _percentiles(successful),
    }


def _percentiles(values):
    if not values:
        return {"count": 0, "median_ns": None, "p95_ns": None}
    ordered = sorted(values)
    size = len(ordered)
    middle = size // 2
    median = ordered[middle] if size % 2 else (ordered[middle - 1] + ordered[middle]) / 2
    nearest_rank_index = math.ceil(0.95 * size) - 1
    return {"count": size, "median_ns": median, "p95_ns": ordered[nearest_rank_index]}


def summarize(samples: list[dict]) -> dict:
    """Summarize every measured attempt and keep warmups in their own population."""
    measured = []
    warmups = []
    for sample in samples:
        if not isinstance(sample, dict):
            raise ValueError("each sample must be an object")
        is_warmup = sample.get("warmup", False)
        if not isinstance(is_warmup, bool):
            raise ValueError("warmup must be a boolean")
        (warmups if is_warmup else measured).append(sample)
    overall = _population(samples)
    overall["measured"] = _population(measured)
    overall["warmups"] = _population(warmups)
    setup_durations = []
    for sample in samples:
        duration = sample.get("setup_duration_ns")
        if duration is not None:
            if isinstance(duration, bool) or not isinstance(duration, int) or duration < 0:
                raise ValueError("setup_duration_ns must be a nonnegative integer or null")
            setup_durations.append(duration)
    overall["setup_timed"] = _percentiles(setup_durations)
    overall["operation_outcomes"] = _counts(samples, "operation_outcome")
    overall["assertion_outcomes"] = _counts(samples, "assertion_outcome")
    for sample in samples:
        if sample.get("trace_complete") not in (True, False, None):
            raise ValueError("trace_complete must be a boolean or null")
    overall["trace_completeness"] = {
        "complete": sum(sample.get("trace_complete") is True for sample in samples),
        "incomplete": sum(sample.get("trace_complete") is False for sample in samples),
        "unavailable": sum(sample.get("trace_complete") is None for sample in samples),
    }
    gaps = {}
    for sample in samples:
        values = sample.get("measurement_gaps", [])
        if not isinstance(values, list) or any(not isinstance(value, str) for value in values):
            raise ValueError("measurement_gaps must be an array of strings")
        for value in values:
            gaps[value] = gaps.get(value, 0) + 1
    overall["measurement_gaps"] = gaps
    return overall


def _counts(samples, field):
    counts = {}
    for sample in samples:
        value = sample.get(field)
        if value is not None:
            if not isinstance(value, str):
                raise ValueError(f"{field} values must be strings")
            counts[value] = counts.get(value, 0) + 1
    return counts


def write_samples(path: Path, samples: list[dict]) -> None:
    """Rewrite JSONL from the accumulated records, including unsuccessful attempts."""
    with Path(path).open("w", encoding="utf-8", newline="\n") as output:
        for sample in samples:
            output.write(json.dumps(sample, sort_keys=True, separators=(",", ":")))
            output.write("\n")
