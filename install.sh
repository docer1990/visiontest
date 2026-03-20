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

# Treat blank/whitespace-only VISIONTEST_DIR as unset
_VISIONTEST_DIR="${VISIONTEST_DIR:-}"
# Trim leading whitespace
_VISIONTEST_DIR="${_VISIONTEST_DIR#"${_VISIONTEST_DIR%%[![:space:]]*}"}"
# Trim trailing whitespace
_VISIONTEST_DIR="${_VISIONTEST_DIR%"${_VISIONTEST_DIR##*[![:space:]]}"}"
VISIONTEST_HOME="${_VISIONTEST_DIR:-$HOME/.local/share/visiontest}"
unset _VISIONTEST_DIR

# Resolve physical path to avoid symlink escapes out of $HOME
# python3 is the most portable; realpath (without -m) works on both GNU and BSD;
# pwd -P is the last resort for existing directories.
RESOLVED_VISIONTEST_HOME="$VISIONTEST_HOME"
if command -v python3 >/dev/null 2>&1; then
    if RESOLVED_TMP=$(python3 -c 'import os,sys; print(os.path.realpath(sys.argv[1]))' "$VISIONTEST_HOME" 2>/dev/null); then
        RESOLVED_VISIONTEST_HOME="$RESOLVED_TMP"
    fi
elif command -v realpath >/dev/null 2>&1; then
    if RESOLVED_TMP=$(realpath "$VISIONTEST_HOME" 2>/dev/null); then
        RESOLVED_VISIONTEST_HOME="$RESOLVED_TMP"
    fi
else
    # Fallback when neither python3 nor realpath is available
    if [ -d "$VISIONTEST_HOME" ]; then
        # If the directory already exists, resolve it directly
        RESOLVED_VISIONTEST_HOME=$(cd "$VISIONTEST_HOME" 2>/dev/null && pwd -P || printf '%s' "$VISIONTEST_HOME")
    elif [ ! -e "$VISIONTEST_HOME" ]; then
        # If the target does not exist yet (e.g., first install), resolve its parent
        PARENT_DIR=$(dirname "$VISIONTEST_HOME")
        if [ -d "$PARENT_DIR" ]; then
            RESOLVED_PARENT=$(cd "$PARENT_DIR" 2>/dev/null && pwd -P || printf '%s' "$PARENT_DIR")
            RESOLVED_VISIONTEST_HOME="$RESOLVED_PARENT/$(basename "$VISIONTEST_HOME")"
        fi
    fi
fi

# Reject install dirs that are symlinks themselves (original or resolved path)
if [ -L "$VISIONTEST_HOME" ] || { [ "$RESOLVED_VISIONTEST_HOME" != "$VISIONTEST_HOME" ] && [ -e "$RESOLVED_VISIONTEST_HOME" ] && [ -L "$RESOLVED_VISIONTEST_HOME" ]; }; then
    printf '  \033[1;31mx\033[0m VISIONTEST_DIR must not be a symlink (got: %s)\n' "$VISIONTEST_HOME" >&2
    exit 1
fi

# Ensure resolved install dir is a subdirectory under $HOME (not $HOME itself)
# Note: path traversal via ".." is already neutralized by the realpath resolution above.
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
        # Prefer java.version property (reliable across vendors); fall back to parsing -version output
        JAVA_VER=$(java -XshowSettings:properties -version 2>&1 | grep '^ *java\.version = ' | sed -E 's/.*= (1\.)?([0-9]*).*/\2/')
        if [ -z "$JAVA_VER" ]; then
            JAVA_VER=$(java -version 2>&1 | head -1 | sed -E 's/.*"(1\.)?([0-9]*).*/\2/')
        fi
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

    # Validate tag format strictly:
    # - must start with 'v'
    # - followed by a digit and at least one more alphanumeric/punctuation char
    #   (e.g. v0.1.0, v1.0-beta — rejects bare 'v' or single-char like 'v1')
    # - remaining characters must be only [A-Za-z0-9._-]
    case "$LATEST_TAG" in
        v[0-9][0-9A-Za-z._-]*)
            ;; # OK
        *)
            error "Unexpected or invalid release tag format: $LATEST_TAG"
            exit 1
            ;;
    esac

    ok "Latest version: $LATEST_TAG"
}

# ---------- download and verify helper ----------

# Downloads a file and its SHA-256 checksum, verifies integrity, moves to dest.
# Usage: download_and_verify <download_url> <checksum_url> <dest_path> <label>
download_and_verify() {
    local DV_URL="$1"
    local DV_CHECKSUM_URL="$2"
    local DV_DEST="$3"
    local DV_LABEL="$4"

    local DV_TEMP_FILE DV_TEMP_SHA
    DV_TEMP_FILE=$(mktemp "$RESOLVED_VISIONTEST_HOME/${DV_LABEL}.XXXXXX")
    DV_TEMP_SHA=$(mktemp "$RESOLVED_VISIONTEST_HOME/${DV_LABEL}.sha256.XXXXXX")
    # Track temp files for cleanup
    CLEANUP_FILES+=("$DV_TEMP_FILE" "$DV_TEMP_SHA")

    if command -v curl >/dev/null 2>&1; then
        curl -fSL --progress-bar -o "$DV_TEMP_FILE" "$DV_URL"
        curl -fsSL -o "$DV_TEMP_SHA" "$DV_CHECKSUM_URL"
    else
        wget -q --show-progress -O "$DV_TEMP_FILE" "$DV_URL"
        wget -q -O "$DV_TEMP_SHA" "$DV_CHECKSUM_URL"
    fi

    info "Verifying $DV_LABEL checksum..."
    local DV_EXPECTED DV_ACTUAL
    DV_EXPECTED=$(cut -d ' ' -f 1 "$DV_TEMP_SHA")
    if command -v sha256sum >/dev/null 2>&1; then
        DV_ACTUAL=$(sha256sum "$DV_TEMP_FILE" | cut -d ' ' -f 1)
    elif command -v shasum >/dev/null 2>&1; then
        DV_ACTUAL=$(shasum -a 256 "$DV_TEMP_FILE" | cut -d ' ' -f 1)
    else
        error "Cannot verify checksum: neither 'sha256sum' nor 'shasum' is available."
        error "Please install one of these tools and rerun the installer."
        exit 1
    fi

    if [ "$DV_ACTUAL" != "$DV_EXPECTED" ]; then
        error "Checksum verification failed for $DV_LABEL!"
        error "  Expected: $DV_EXPECTED"
        error "  Got:      $DV_ACTUAL"
        error "The download may be incomplete (e.g. network interruption). Please retry."
        exit 1
    fi
    ok "$DV_LABEL checksum verified"

    chmod 600 "$DV_TEMP_FILE"
    mv -f "$DV_TEMP_FILE" "$DV_DEST"
    mv -f "$DV_TEMP_SHA" "${DV_DEST}.sha256"
}

# ---------- download JAR ----------

download_jar() {
    # Use resolved path for all filesystem operations to close TOCTOU gaps
    mkdir -p "$RESOLVED_VISIONTEST_HOME"
    chmod 700 "$RESOLVED_VISIONTEST_HOME"

    # Initialize cleanup tracking
    CLEANUP_FILES=()
    trap 'if [ "${#CLEANUP_FILES[@]}" -gt 0 ]; then rm -f -- "${CLEANUP_FILES[@]}"; fi' EXIT

    info "Downloading visiontest.jar..."
    download_and_verify \
        "https://github.com/$REPO/releases/download/$LATEST_TAG/visiontest.jar" \
        "https://github.com/$REPO/releases/download/$LATEST_TAG/visiontest.jar.sha256" \
        "$RESOLVED_VISIONTEST_HOME/visiontest.jar" \
        "visiontest.jar"

    printf '%s\n' "$LATEST_TAG" > "$RESOLVED_VISIONTEST_HOME/version.txt"
    chmod 600 "$RESOLVED_VISIONTEST_HOME/version.txt"
    ok "Installed to $RESOLVED_VISIONTEST_HOME/visiontest.jar"
}

# ---------- download APKs ----------

download_apks() {
    info "Downloading Android automation APKs..."

    download_and_verify \
        "https://github.com/$REPO/releases/download/$LATEST_TAG/automation-server.apk" \
        "https://github.com/$REPO/releases/download/$LATEST_TAG/automation-server.apk.sha256" \
        "$RESOLVED_VISIONTEST_HOME/automation-server.apk" \
        "automation-server.apk"

    download_and_verify \
        "https://github.com/$REPO/releases/download/$LATEST_TAG/automation-server-test.apk" \
        "https://github.com/$REPO/releases/download/$LATEST_TAG/automation-server-test.apk.sha256" \
        "$RESOLVED_VISIONTEST_HOME/automation-server-test.apk" \
        "automation-server-test.apk"

    ok "APKs installed to $RESOLVED_VISIONTEST_HOME/"
}

# ---------- download iOS bundle (macOS arm64 only) ----------

download_ios_bundle() {
    if [ "$PLATFORM" != "macOS" ]; then
        info "Skipping iOS automation bundle (macOS only)"
        return
    fi

    if [ "$ARCH" != "arm64" ]; then
        info "Skipping iOS automation bundle (pre-built bundle is arm64 only)"
        info "For iOS automation on Intel Mac, build from source: clone the repo and use Xcode"
        return
    fi

    info "Downloading iOS automation bundle..."

    download_and_verify \
        "https://github.com/$REPO/releases/download/$LATEST_TAG/ios-automation-server.tar.gz" \
        "https://github.com/$REPO/releases/download/$LATEST_TAG/ios-automation-server.tar.gz.sha256" \
        "$RESOLVED_VISIONTEST_HOME/ios-automation-server.tar.gz" \
        "ios-automation-server.tar.gz"

    # Extract preserving directory structure for xcodebuild test-without-building
    # Remove previous bundle to avoid stale .xctestrun files from older SDK versions
    rm -rf "$RESOLVED_VISIONTEST_HOME/ios-automation-server"
    mkdir -p "$RESOLVED_VISIONTEST_HOME/ios-automation-server"
    chmod 700 "$RESOLVED_VISIONTEST_HOME/ios-automation-server"

    # Validate archive entries: reject absolute paths, parent traversal, and symlinks
    IOS_ARCHIVE="$RESOLVED_VISIONTEST_HOME/ios-automation-server.tar.gz"
    if tar -tzf "$IOS_ARCHIVE" | grep -qE '(^/|\.\./)'; then
        error "iOS bundle archive contains unsafe paths (absolute or parent traversal)"
        rm -f "$IOS_ARCHIVE"
        exit 1
    fi
    tar -xzf "$IOS_ARCHIVE" --no-same-owner \
        -C "$RESOLVED_VISIONTEST_HOME/ios-automation-server"
    rm -f "$IOS_ARCHIVE"
    rm -f "${IOS_ARCHIVE}.sha256"

    ok "iOS bundle installed to $RESOLVED_VISIONTEST_HOME/ios-automation-server/"
}

# ---------- create wrapper script ----------

create_wrapper() {
    mkdir -p "$BIN_DIR"
    chmod 755 "$BIN_DIR"

    # Shell-escape the resolved path so it can be safely embedded in the wrapper
    # Turns ' into '\'' for safe single-quote embedding
    ESCAPED_VISIONTEST_HOME=$(printf '%s' "$RESOLVED_VISIONTEST_HOME" | sed "s/'/'\\\\''/g")

    cat > "$BIN_DIR/visiontest" <<WRAPPER
#!/bin/sh
VISIONTEST_HOME='$ESCAPED_VISIONTEST_HOME'
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
        printf '# Added by VisionTest installer\nexport PATH="%s:$PATH"\n' "$BIN_DIR" >> "$HOME/.zshrc"
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
    download_apks
    download_ios_bundle
    # Disarm the cleanup trap since all downloads succeeded
    trap - EXIT
    create_wrapper
    ensure_path

    echo ""
    ok "VisionTest $LATEST_TAG installed successfully!"
    echo ""
    echo "  Installed:"
    echo "    JAR:  $RESOLVED_VISIONTEST_HOME/visiontest.jar"
    echo "    APKs: $RESOLVED_VISIONTEST_HOME/automation-server.apk"
    echo "          $RESOLVED_VISIONTEST_HOME/automation-server-test.apk"
    if [ "$PLATFORM" = "macOS" ] && [ "$ARCH" = "arm64" ]; then
        echo "    iOS:  $RESOLVED_VISIONTEST_HOME/ios-automation-server/"
    elif [ "$PLATFORM" = "macOS" ]; then
        echo "    iOS:  (not installed — pre-built bundle is arm64 only; build from source)"
    else
        echo "    iOS:  (not installed — macOS only)"
    fi
    echo ""
    echo "  Run the MCP server:"
    echo "    visiontest"
    echo ""
    echo "  For Claude Code, add with:"
    echo "    claude mcp add visiontest java -- -jar $RESOLVED_VISIONTEST_HOME/visiontest.jar"
    echo ""
    echo "  To update later, re-run this script."
    echo ""
}

main
