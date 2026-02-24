#!/bin/bash
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

# Resolve physical path to avoid symlink escapes out of $HOME
RESOLVED_VISIONTEST_HOME="$VISIONTEST_HOME"
if command -v realpath >/dev/null 2>&1; then
    if RESOLVED_TMP=$(realpath -m "$VISIONTEST_HOME" 2>/dev/null); then
        RESOLVED_VISIONTEST_HOME="$RESOLVED_TMP"
    fi
else
    # Fallback: if directory exists, resolve via pwd -P
    if [ -d "$VISIONTEST_HOME" ]; then
        RESOLVED_VISIONTEST_HOME=$(cd "$VISIONTEST_HOME" 2>/dev/null && pwd -P || printf '%s' "$VISIONTEST_HOME")
    fi
fi

# Reject install dirs that are symlinks themselves
if [ -L "$VISIONTEST_HOME" ]; then
    printf '  \033[1;31mx\033[0m VISIONTEST_DIR must not be a symlink (got: %s)\n' "$VISIONTEST_HOME" >&2
    exit 1
fi

# Reject path traversal segments before checking prefix
case "$RESOLVED_VISIONTEST_HOME" in
    *..*)
        printf '  \033[1;31mx\033[0m VISIONTEST_DIR must not contain ".." (resolved: %s)\n' "$RESOLVED_VISIONTEST_HOME" >&2
        exit 1
        ;;
esac

# Ensure resolved install dir is a subdirectory under $HOME (not $HOME itself)
case "$RESOLVED_VISIONTEST_HOME" in
    "$HOME"/?*) ;; # OK — under home directory with at least one path component
    *)
        printf '  \033[1;31mx\033[0m VISIONTEST_DIR must be under \$HOME (resolved: %s)\n' "$RESOLVED_VISIONTEST_HOME" >&2
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
        rm -f "$VISIONTEST_HOME/visiontest.jar" "$VISIONTEST_HOME/visiontest.jar.sha256"
        error "Checksum verification failed!"
        error "  Expected: $EXPECTED_SHA"
        error "  Got:      $ACTUAL_SHA"
        error "The download may be incomplete (e.g. network interruption). Please retry."
        exit 1
    fi
    ok "Checksum verified"

    chmod 600 "$VISIONTEST_HOME/visiontest.jar"
    printf '%s\n' "$LATEST_TAG" > "$VISIONTEST_HOME/version.txt"
    chmod 600 "$VISIONTEST_HOME/version.txt"
    ok "Installed to $VISIONTEST_HOME/visiontest.jar"
}

# ---------- create wrapper script ----------

create_wrapper() {
    mkdir -p "$BIN_DIR"
    chmod 755 "$BIN_DIR"

    cat > "$BIN_DIR/visiontest" <<WRAPPER
#!/bin/sh
VISIONTEST_HOME="$VISIONTEST_HOME"
if [ ! -f "\$VISIONTEST_HOME/visiontest.jar" ]; then
    DEFAULT_HOME="\$HOME/.local/share/visiontest"
    if [ -f "\$DEFAULT_HOME/visiontest.jar" ]; then
        VISIONTEST_HOME="\$DEFAULT_HOME"
    fi
fi
exec java -jar "\$VISIONTEST_HOME/visiontest.jar" "\$@"
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
            if ! grep -Fq "$BIN_DIR" "$rc" 2>/dev/null; then
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
