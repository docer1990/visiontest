#!/bin/sh
# VisionTest MCP Server Installer
# Usage: curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash
#
# Environment variables:
#   VISIONTEST_DIR  — override install directory (default: ~/.local/share/visiontest)

set -eu
umask 077

REPO="docer1990/visiontest"
BIN_DIR="$HOME/.local/bin"

# ---------- validate install dir ----------

VISIONTEST_HOME="${VISIONTEST_DIR:-$HOME/.local/share/visiontest}"

# Reject path traversal segments before checking prefix
case "$VISIONTEST_HOME" in
    *..*)
        printf '  \033[1;31mx\033[0m VISIONTEST_DIR must not contain ".." (got: %s)\n' "$VISIONTEST_HOME" >&2
        exit 1
        ;;
esac

# Ensure install dir is under $HOME
case "$VISIONTEST_HOME" in
    "$HOME"/*) ;; # OK — under home directory
    *)
        printf '  \033[1;31mx\033[0m VISIONTEST_DIR must be under $HOME (got: %s)\n' "$VISIONTEST_HOME" >&2
        exit 1
        ;;
esac

# ---------- helpers ----------

info()  { printf '  \033[1;34m>\033[0m %s\n' "$*"; }
warn()  { printf '  \033[1;33m!\033[0m %s\n' "$*"; }
error() { printf '  \033[1;31mx\033[0m %s\n' "$*" >&2; }
ok()    { printf '  \033[1;32m✓\033[0m %s\n' "$*"; }

# ---------- OS / arch detection ----------

detect_platform() {
    OS="$(uname -s)"
    ARCH="$(uname -m)"

    case "$OS" in
        Darwin) PLATFORM="macOS" ;;
        Linux)  PLATFORM="Linux" ;;
        *)      error "Unsupported OS: $OS"; exit 1 ;;
    esac

    case "$ARCH" in
        x86_64|amd64)  ARCH="x86_64" ;;
        arm64|aarch64) ARCH="arm64" ;;
        *)             error "Unsupported architecture: $ARCH"; exit 1 ;;
    esac

    info "Detected $PLATFORM ($ARCH)"
}

# ---------- Java check ----------

check_java() {
    if command -v java >/dev/null 2>&1; then
        JAVA_VER=$(java -version 2>&1 | head -1 | sed 's/.*"\(1\.\)\?\([0-9]*\).*/\2/')
        if [ "$JAVA_VER" -ge 17 ] 2>/dev/null; then
            ok "Java $JAVA_VER found"
            return
        fi
        error "Java 17+ is required (found version $JAVA_VER)"
    else
        error "Java is not installed"
    fi

    echo ""
    echo "  Install Java 17+ using one of:"
    if [ "$PLATFORM" = "macOS" ]; then
        echo "    brew install openjdk@17"
        echo "    sdk install java 17.0.13-tem   # via SDKMAN"
    else
        echo "    sudo apt install openjdk-17-jdk   # Debian/Ubuntu"
        echo "    sudo dnf install java-17-openjdk  # Fedora/RHEL"
        echo "    sdk install java 17.0.13-tem      # via SDKMAN"
    fi
    exit 1
}

# ---------- fetch latest release ----------

fetch_latest_version() {
    info "Fetching latest release..."

    # Try GitHub API (works without auth for public repos)
    if command -v curl >/dev/null 2>&1; then
        LATEST_TAG=$(curl -fsSL "https://api.github.com/repos/$REPO/releases/latest" \
            | grep '"tag_name"' | head -1 | sed 's/.*"tag_name":[[:space:]]*"\([^"]*\)".*/\1/')
    elif command -v wget >/dev/null 2>&1; then
        LATEST_TAG=$(wget --server-response -qO- "https://api.github.com/repos/$REPO/releases/latest" 2>/dev/null \
            | grep '"tag_name"' | head -1 | sed 's/.*"tag_name":[[:space:]]*"\([^"]*\)".*/\1/')
    else
        error "Neither curl nor wget found"; exit 1
    fi

    if [ -z "$LATEST_TAG" ]; then
        error "Could not determine latest release. Check https://github.com/$REPO/releases"
        exit 1
    fi

    # Validate tag format (v followed by semver-like: v1.0.0, v0.1.0-beta, etc.)
    case "$LATEST_TAG" in
        v[0-9]*) ;; # OK
        *)
            error "Unexpected release tag format: $LATEST_TAG"
            exit 1
            ;;
    esac

    # Reject tags with path traversal or shell metacharacters
    case "$LATEST_TAG" in
        *..* | */* | *\\* | *\;* | *\|* | *\&* | *\$* | *\(* | *\)* | *\<* | *\>*)
            error "Release tag contains invalid characters: $LATEST_TAG"
            exit 1
            ;;
    esac

    ok "Latest version: $LATEST_TAG"
}

# ---------- download JAR ----------

download_jar() {
    DOWNLOAD_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/visiontest.jar"

    mkdir -p "$VISIONTEST_HOME"
    chmod 700 "$VISIONTEST_HOME"
    info "Downloading visiontest.jar..."

    CHECKSUM_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/visiontest.jar.sha256"

    if command -v curl >/dev/null 2>&1; then
        curl -fSL --progress-bar -o "$VISIONTEST_HOME/visiontest.jar" "$DOWNLOAD_URL"
        curl -fsSL -o "$VISIONTEST_HOME/visiontest.jar.sha256" "$CHECKSUM_URL"
    else
        wget -q --show-progress -O "$VISIONTEST_HOME/visiontest.jar" "$DOWNLOAD_URL"
        wget -q -O "$VISIONTEST_HOME/visiontest.jar.sha256" "$CHECKSUM_URL"
    fi

    # Verify checksum
    info "Verifying checksum..."
    EXPECTED_SHA=$(cut -d ' ' -f 1 "$VISIONTEST_HOME/visiontest.jar.sha256")
    if command -v sha256sum >/dev/null 2>&1; then
        ACTUAL_SHA=$(sha256sum "$VISIONTEST_HOME/visiontest.jar" | cut -d ' ' -f 1)
    elif command -v shasum >/dev/null 2>&1; then
        ACTUAL_SHA=$(shasum -a 256 "$VISIONTEST_HOME/visiontest.jar" | cut -d ' ' -f 1)
    else
        error "Cannot verify checksum: neither 'sha256sum' nor 'shasum' is available."
        error "Please install one of these tools and rerun the installer."
        rm -f "$VISIONTEST_HOME/visiontest.jar" "$VISIONTEST_HOME/visiontest.jar.sha256"
        exit 1
    fi

    if [ "$ACTUAL_SHA" != "$EXPECTED_SHA" ]; then
        rm -f "$VISIONTEST_HOME/visiontest.jar"
        error "Checksum verification failed!"
        error "  Expected: $EXPECTED_SHA"
        error "  Got:      $ACTUAL_SHA"
        exit 1
    fi
    ok "Checksum verified"

    chmod 600 "$VISIONTEST_HOME/visiontest.jar"
    printf '%s\n' "$LATEST_TAG" > "$VISIONTEST_HOME/version.txt"
    ok "Installed to $VISIONTEST_HOME/visiontest.jar"
}

# ---------- create wrapper script ----------

create_wrapper() {
    mkdir -p "$BIN_DIR"
    chmod 755 "$BIN_DIR"

    cat > "$BIN_DIR/visiontest" <<WRAPPER
#!/bin/sh
exec java -jar "$VISIONTEST_HOME/visiontest.jar" "\$@"
WRAPPER

    chmod 755 "$BIN_DIR/visiontest"
    ok "Created wrapper at $BIN_DIR/visiontest"
}

# ---------- ensure PATH ----------

ensure_path() {
    case ":$PATH:" in
        *":$BIN_DIR:"*) return ;;
    esac

    warn "$BIN_DIR is not on your PATH"

    for rc in "$HOME/.bashrc" "$HOME/.zshrc"; do
        if [ -f "$rc" ]; then
            if ! grep -q "$BIN_DIR" "$rc" 2>/dev/null; then
                printf '\n# Added by VisionTest installer\nexport PATH="%s:$PATH"\n' "$BIN_DIR" >> "$rc"
                info "Updated $rc"
            fi
        fi
    done

    # Also create .zshrc if on macOS and it doesn't exist (zsh is default)
    if [ "$PLATFORM" = "macOS" ] && [ ! -f "$HOME/.zshrc" ]; then
        printf '# Added by VisionTest installer\nexport PATH="%s:$PATH"\n' "$BIN_DIR" > "$HOME/.zshrc"
        info "Created $HOME/.zshrc"
    fi

    export PATH="$BIN_DIR:$PATH"
}

# ---------- Claude Desktop config ----------

configure_claude_desktop() {
    if [ "$PLATFORM" = "macOS" ]; then
        CONFIG_DIR="$HOME/Library/Application Support/Claude"
    else
        CONFIG_DIR="$HOME/.config/Claude"
    fi
    CONFIG_FILE="$CONFIG_DIR/claude_desktop_config.json"

    if [ ! -d "$CONFIG_DIR" ]; then
        # Claude Desktop not installed — skip silently
        return
    fi

    echo ""
    info "Claude Desktop detected at $CONFIG_DIR"
    printf "  Configure VisionTest as an MCP server in Claude Desktop? [Y/n] "

    # When piped from curl, stdin is the script itself — reattach to terminal
    REPLY="y"
    if [ -t 0 ]; then
        read -r REPLY
    elif [ -e /dev/tty ]; then
        read -r REPLY < /dev/tty
    fi

    case "$REPLY" in
        [nN]*) info "Skipped Claude Desktop configuration"; return ;;
    esac

    # Back up existing config
    if [ -f "$CONFIG_FILE" ]; then
        cp "$CONFIG_FILE" "$CONFIG_FILE.backup.$(date +%s)"
        info "Backed up existing config"
    fi

    # Build entry and merge config in Python to guarantee valid JSON escaping
    if command -v python3 >/dev/null 2>&1; then
        python3 - "$CONFIG_FILE" "$VISIONTEST_HOME/visiontest.jar" <<'PYEOF'
import json, sys, os

config_path = sys.argv[1]
jar_path = sys.argv[2]

entry = {
    "command": "java",
    "args": ["-jar", jar_path]
}

if os.path.isfile(config_path):
    with open(config_path) as f:
        try:
            config = json.load(f)
        except json.JSONDecodeError:
            config = {}
else:
    config = {}

config.setdefault("mcpServers", {})
if "visiontest" in config["mcpServers"]:
    print("  \033[1;33m!\033[0m Existing visiontest config found — updating JAR path")
config["mcpServers"]["visiontest"] = entry

with open(config_path, "w") as f:
    json.dump(config, f, indent=2)
    f.write("\n")
PYEOF
        ok "Added visiontest to Claude Desktop config"
    else
        warn "Could not update Claude Desktop config (python3 not found). Add manually:"
        echo "  $CONFIG_FILE"
    fi
}

# ---------- main ----------

main() {
    echo ""
    printf '  \033[1mVisionTest MCP Server Installer\033[0m\n'
    echo ""

    detect_platform
    check_java
    fetch_latest_version
    download_jar
    create_wrapper
    ensure_path
    configure_claude_desktop

    echo ""
    ok "VisionTest $LATEST_TAG installed successfully!"
    echo ""
    echo "  Run the MCP server:"
    echo "    visiontest"
    echo ""
    echo "  For Claude Code, add with:"
    echo "    claude mcp add visiontest java -- -jar $VISIONTEST_HOME/visiontest.jar"
    echo ""
    echo "  To update later, re-run this script."
    echo ""
}

main
