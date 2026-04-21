#!/bin/bash
# VisionTest MCP Server Installer
# Usage: curl -fsSL https://github.com/docer1990/visiontest/releases/latest/download/install.sh | bash
#
# Flags:
#   --skip-agent-setup  Skip installing AI agent instructions
#   --local-jar PATH    Use a local JAR instead of downloading from GitHub Releases
#                       (also skips APK/iOS bundle download — useful for testing)
#
# Environment variables:
#   VISIONTEST_DIR  — override install directory (default: ~/.local/share/visiontest)

set -eu
umask 077

# ---------- parse flags ----------
SKIP_AGENT_SETUP=false
LOCAL_JAR=""
for arg in "$@"; do
    case "$arg" in
        --skip-agent-setup) SKIP_AGENT_SETUP=true ;;
        --local-jar)        _EXPECT_JAR_PATH=true ;;
        *)
            if [ "${_EXPECT_JAR_PATH:-}" = "true" ]; then
                LOCAL_JAR="$arg"
                _EXPECT_JAR_PATH=""
            fi
            ;;
    esac
done
unset _EXPECT_JAR_PATH

if [ -n "$LOCAL_JAR" ] && [ ! -f "$LOCAL_JAR" ]; then
    printf '  \033[1;31mx\033[0m --local-jar: file not found: %s\n' "$LOCAL_JAR" >&2
    exit 2
fi

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

    # Validate archive entries: reject absolute paths, parent traversal, and symlinks
    # Done BEFORE removing the existing bundle to avoid data loss on failure
    IOS_ARCHIVE="$RESOLVED_VISIONTEST_HOME/ios-automation-server.tar.gz"
    if tar -tzf "$IOS_ARCHIVE" | grep -qE '(^/|/\.\.(/|$)|^\.\./|^\.\.$)'; then
        error "iOS bundle archive contains unsafe paths (absolute or parent traversal)"
        rm -f "$IOS_ARCHIVE"
        exit 1
    fi
    if tar -tvzf "$IOS_ARCHIVE" | grep -qE '^l'; then
        error "iOS bundle archive contains symbolic links"
        rm -f "$IOS_ARCHIVE"
        exit 1
    fi

    # Extract into a temp directory, then atomically replace the old bundle
    # This avoids deleting a working bundle if extraction fails
    IOS_TMP_DIR="$RESOLVED_VISIONTEST_HOME/ios-automation-server.tmp"
    rm -rf "$IOS_TMP_DIR"
    mkdir -p "$IOS_TMP_DIR"
    chmod 700 "$IOS_TMP_DIR"

    if ! tar -xzf "$IOS_ARCHIVE" --no-same-owner -C "$IOS_TMP_DIR"; then
        error "Failed to extract iOS bundle archive"
        rm -rf "$IOS_TMP_DIR"
        rm -f "$IOS_ARCHIVE" "${IOS_ARCHIVE}.sha256"
        exit 1
    fi

    # Safe swap: backup old bundle, move new one in, then drop backup
    IOS_FINAL_DIR="$RESOLVED_VISIONTEST_HOME/ios-automation-server"
    IOS_BACKUP_DIR="${IOS_FINAL_DIR}.bak.$$"
    if [ -d "$IOS_FINAL_DIR" ]; then
        rm -rf "$IOS_BACKUP_DIR"
        if ! mv "$IOS_FINAL_DIR" "$IOS_BACKUP_DIR"; then
            error "Failed to create backup of existing iOS bundle"
            rm -rf "$IOS_TMP_DIR"
            rm -f "$IOS_ARCHIVE" "${IOS_ARCHIVE}.sha256"
            exit 1
        fi
    fi
    if ! mv "$IOS_TMP_DIR" "$IOS_FINAL_DIR"; then
        error "Failed to install new iOS bundle"
        rm -rf "$IOS_TMP_DIR"
        # Attempt to restore previous bundle if backup exists
        if [ -d "$IOS_BACKUP_DIR" ]; then
            if mv "$IOS_BACKUP_DIR" "$IOS_FINAL_DIR"; then
                info "Restored previous iOS bundle from backup"
            else
                error "Failed to restore previous iOS bundle from backup; manual intervention required"
            fi
        fi
        rm -f "$IOS_ARCHIVE" "${IOS_ARCHIVE}.sha256"
        exit 1
    fi
    rm -rf "$IOS_BACKUP_DIR"
    rm -f "$IOS_ARCHIVE" "${IOS_ARCHIVE}.sha256"

    ok "iOS bundle installed to $IOS_FINAL_DIR/"
}

# ---------- install from local JAR (testing mode) ----------

install_local_jar() {
    mkdir -p "$RESOLVED_VISIONTEST_HOME"
    chmod 700 "$RESOLVED_VISIONTEST_HOME"

    info "Installing from local JAR: $LOCAL_JAR"
    cp -f "$LOCAL_JAR" "$RESOLVED_VISIONTEST_HOME/visiontest.jar"
    chmod 600 "$RESOLVED_VISIONTEST_HOME/visiontest.jar"
    printf 'local-dev\n' > "$RESOLVED_VISIONTEST_HOME/version.txt"
    chmod 600 "$RESOLVED_VISIONTEST_HOME/version.txt"
    ok "Installed local JAR to $RESOLVED_VISIONTEST_HOME/visiontest.jar"

    # Copy AGENT_INSTRUCTIONS.md from repo if present alongside install.sh
    local SCRIPT_DIR
    SCRIPT_DIR=$(cd "$(dirname "$0")" && pwd)
    if [ -f "$SCRIPT_DIR/AGENT_INSTRUCTIONS.md" ]; then
        cp -f "$SCRIPT_DIR/AGENT_INSTRUCTIONS.md" "$RESOLVED_VISIONTEST_HOME/AGENT_INSTRUCTIONS.md"
        chmod 600 "$RESOLVED_VISIONTEST_HOME/AGENT_INSTRUCTIONS.md"
        ok "Copied AGENT_INSTRUCTIONS.md from repo"
    fi
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

# ---------- install AI agent instructions ----------

MARKER_BEGIN="<!-- BEGIN VISIONTEST -->"
MARKER_END="<!-- END VISIONTEST -->"

download_agent_instructions() {
    info "Downloading agent instructions..."
    local INSTRUCTIONS_URL="https://github.com/$REPO/releases/download/$LATEST_TAG/AGENT_INSTRUCTIONS.md"
    local DEST="$RESOLVED_VISIONTEST_HOME/AGENT_INSTRUCTIONS.md"

    if command -v curl >/dev/null 2>&1; then
        curl -fsSL -o "$DEST" "$INSTRUCTIONS_URL"
    elif command -v wget >/dev/null 2>&1; then
        wget -q -O "$DEST" "$INSTRUCTIONS_URL"
    fi
    chmod 600 "$DEST"
}

# Appends or replaces the VisionTest instruction block in a target file.
# Uses BEGIN/END markers for idempotent updates.
# Usage: append_with_markers <target_file> <instructions_content>
append_with_markers() {
    local TARGET="$1"
    local CONTENT="$2"

    if [ -f "$TARGET" ] && grep -qF "$MARKER_BEGIN" "$TARGET"; then
        # Replace existing block: remove old markers + content, append new
        # Use a temp file to avoid sed -i portability issues (GNU vs BSD)
        local TMP
        TMP=$(mktemp "${TARGET}.XXXXXX")
        awk -v begin="$MARKER_BEGIN" -v end="$MARKER_END" '
            $0 == begin { skip=1; next }
            $0 == end   { skip=0; next }
            !skip { print }
        ' "$TARGET" > "$TMP"
        mv -f "$TMP" "$TARGET"
    fi

    # Append the block
    {
        echo ""
        echo "$MARKER_BEGIN"
        echo "$CONTENT"
        echo "$MARKER_END"
    } >> "$TARGET"
}

install_agent_instructions() {
    if [ "$SKIP_AGENT_SETUP" = "true" ]; then
        info "Skipping AI agent setup (--skip-agent-setup)"
        return
    fi

    local INSTRUCTIONS_FILE="$RESOLVED_VISIONTEST_HOME/AGENT_INSTRUCTIONS.md"
    if [ ! -f "$INSTRUCTIONS_FILE" ]; then
        warn "Agent instructions not found, skipping agent setup"
        return
    fi

    local INSTRUCTIONS
    INSTRUCTIONS=$(cat "$INSTRUCTIONS_FILE")
    local AGENTS_CONFIGURED=""

    # --- Claude Code ---
    if command -v claude >/dev/null 2>&1; then
        local CLAUDE_SKILL_DIR="$HOME/.claude/skills/visiontest-mobile"
        mkdir -p "$CLAUDE_SKILL_DIR"
        cat > "$CLAUDE_SKILL_DIR/SKILL.md" <<CLAUDE_EOF
---
name: visiontest-mobile
description: Automate mobile device interactions (Android/iOS) via the VisionTest CLI. Use when testing, inspecting, or interacting with mobile apps through screenshots, taps, swipes, and text input.
when-to-use: When you need to interact with a mobile device or simulator — take screenshots, tap elements, type text, or inspect UI hierarchy.
---

$INSTRUCTIONS
CLAUDE_EOF
        chmod 644 "$CLAUDE_SKILL_DIR/SKILL.md"
        ok "Claude Code: installed skill to $CLAUDE_SKILL_DIR/SKILL.md"
        AGENTS_CONFIGURED="${AGENTS_CONFIGURED}claude "
    fi

    # --- OpenCode ---
    if command -v opencode >/dev/null 2>&1; then
        local OPENCODE_DIR="$HOME/.config/opencode"
        mkdir -p "$OPENCODE_DIR"
        local OPENCODE_TARGET="$OPENCODE_DIR/AGENTS.md"
        append_with_markers "$OPENCODE_TARGET" "$INSTRUCTIONS"
        chmod 644 "$OPENCODE_TARGET"
        ok "OpenCode: updated $OPENCODE_TARGET"
        AGENTS_CONFIGURED="${AGENTS_CONFIGURED}opencode "
    fi

    # --- Codex (OpenAI) ---
    if command -v codex >/dev/null 2>&1; then
        local CODEX_DIR="$HOME/.codex"
        mkdir -p "$CODEX_DIR"
        local CODEX_TARGET="$CODEX_DIR/instructions.md"
        append_with_markers "$CODEX_TARGET" "$INSTRUCTIONS"
        chmod 644 "$CODEX_TARGET"
        ok "Codex: updated $CODEX_TARGET"
        AGENTS_CONFIGURED="${AGENTS_CONFIGURED}codex "
    fi

    # --- GitHub Copilot CLI ---
    if command -v gh >/dev/null 2>&1 && gh extension list 2>/dev/null | grep -q "copilot"; then
        local COPILOT_DIR="$HOME/.github"
        mkdir -p "$COPILOT_DIR"
        local COPILOT_TARGET="$COPILOT_DIR/copilot-instructions.md"
        append_with_markers "$COPILOT_TARGET" "$INSTRUCTIONS"
        chmod 644 "$COPILOT_TARGET"
        ok "Copilot: updated $COPILOT_TARGET"
        AGENTS_CONFIGURED="${AGENTS_CONFIGURED}copilot "
    fi

    if [ -z "$AGENTS_CONFIGURED" ]; then
        info "No supported AI coding agents detected (checked: claude, opencode, codex, gh copilot)"
        info "You can manually copy $INSTRUCTIONS_FILE into your agent's config"
    fi
}

# ---------- main ----------

main() {
    echo ""
    printf '  \033[1mVisionTest MCP Server Installer\033[0m\n'
    echo ""

    detect_platform
    check_java

    if [ -n "$LOCAL_JAR" ]; then
        install_local_jar
        LATEST_TAG="local-dev"
    else
        fetch_latest_version
        download_jar
        download_apks
        download_ios_bundle
        download_agent_instructions
        # Disarm the cleanup trap since all downloads succeeded
        trap - EXIT
    fi

    create_wrapper
    ensure_path
    install_agent_instructions

    echo ""
    ok "VisionTest $LATEST_TAG installed successfully!"
    echo ""
    echo "  Installed:"
    echo "    JAR:  $RESOLVED_VISIONTEST_HOME/visiontest.jar"
    if [ -z "$LOCAL_JAR" ]; then
        echo "    APKs: $RESOLVED_VISIONTEST_HOME/automation-server.apk"
        echo "          $RESOLVED_VISIONTEST_HOME/automation-server-test.apk"
        if [ "$PLATFORM" = "macOS" ] && [ "$ARCH" = "arm64" ]; then
            echo "    iOS:  $RESOLVED_VISIONTEST_HOME/ios-automation-server/"
        elif [ "$PLATFORM" = "macOS" ]; then
            echo "    iOS:  (not installed — pre-built bundle is arm64 only; build from source)"
        else
            echo "    iOS:  (not installed — macOS only)"
        fi
    fi
    echo ""
    echo "  Run the MCP server:"
    echo "    visiontest"
    echo ""
    echo "  For Claude Code, add with:"
    echo "    claude mcp add visiontest java -- -jar $RESOLVED_VISIONTEST_HOME/visiontest.jar"
    echo ""
    echo "  CLI usage (in any project):"
    echo "    visiontest --help"
    echo ""
    echo "  To update later, re-run this script."
    echo "  To skip agent config: install.sh --skip-agent-setup"
    echo ""
}

main
