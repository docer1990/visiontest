import re


_TOKEN = re.compile(r"^\{([a-z][A-Za-z0-9]*)\}$")


def validate_target(platform: str, selected: str, available: list[str]) -> None:
    if platform not in {"android", "ios"}:
        raise ValueError("platform must be android or ios")
    if not selected or not isinstance(selected, str):
        raise ValueError("selected target is required")
    active = list(dict.fromkeys(available))
    if len(active) != 1 or active[0] != selected:
        raise ValueError(f"expected only selected {platform} target {selected}; found {active}")


def resolve_argv(argv: list[str], tokens: dict[str, str]) -> list[str]:
    resolved = []
    for value in argv:
        match = _TOKEN.fullmatch(value)
        if "{" in value or "}" in value:
            if match is None or match.group(1) not in tokens:
                raise ValueError(f"unsupported or embedded manifest token: {value}")
            value = tokens[match.group(1)]
        resolved.append(value)
    return resolved


def validate_fixture_identity(platform: str, identity: str) -> None:
    expected = "com.example.visiontest.benchmark"
    if platform not in {"android", "ios"} or identity != expected:
        raise ValueError("fixture identity must be com.example.visiontest.benchmark")
