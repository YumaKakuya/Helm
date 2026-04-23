#!/usr/bin/env bash
set -euo pipefail

# MCPHUB installation script — REQ-9.11.1
# Usage: ./install.sh [--service] [--prefix PATH]
# Default prefix: $HOME/.local/share/mcphub

PREFIX="${MCPHUB_PREFIX:-$HOME/.local/share/mcphub}"
BIN_PREFIX="${MCPHUB_BIN_PREFIX:-$HOME/.local/bin}"
DATA_DIR="$PREFIX"
INSTALL_SERVICE=0

# Parse args
while [[ $# -gt 0 ]]; do
    case $1 in
        --service) INSTALL_SERVICE=1; shift ;;
        --prefix)  PREFIX="$2"; DATA_DIR="$2"; shift 2 ;;
        --help)    echo "Usage: $0 [--service] [--prefix PATH]"; exit 0 ;;
        *)         echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

# Detect OS
OS="$(uname -s)"
case "$OS" in
    Linux)  PLATFORM=linux ;;
    Darwin) PLATFORM=darwin ;;
    *)      echo "Unsupported OS: $OS" >&2; exit 1 ;;
esac

# Refuse root (REQ-9.11.2)
if [[ "$EUID" -eq 0 ]]; then
    echo "ERROR: Do not run as root. MCPHUB alpha is user-scoped." >&2
    exit 1
fi

echo "MCPHUB Installation"
echo "==================="
echo "Platform: $PLATFORM"
echo "Install prefix: $PREFIX"
echo "Binary prefix:  $BIN_PREFIX"

# Step 1: Create data dir
mkdir -p "$DATA_DIR"
mkdir -p "$DATA_DIR/logs"
chmod 700 "$DATA_DIR"

# Step 2: Copy binaries (assume we are in the MCPHUB repo dir)
REPO_DIR="$(cd "$(dirname "$0")" && pwd)"
mkdir -p "$BIN_PREFIX"

# Copy mcphub binary if present
if [[ -f "$REPO_DIR/mcphub" ]]; then
    cp "$REPO_DIR/mcphub" "$BIN_PREFIX/mcphub"
    chmod +x "$BIN_PREFIX/mcphub"
    echo "Installed: $BIN_PREFIX/mcphub"
else
    echo "WARNING: $REPO_DIR/mcphub not found — build it with 'go build -o mcphub ./cmd/mcphub' first"
fi

# Copy Java fat-JAR (AMD-MCPHUB-001: Java is the daemon, not just a subprocess)
JAR_FILE=$(find "$REPO_DIR/java/build/libs" -name "mcphub-core-*.jar" ! -name "*plain*" 2>/dev/null | head -1)
if [[ -n "$JAR_FILE" ]]; then
    mkdir -p "$PREFIX/lib"
    cp "$JAR_FILE" "$PREFIX/lib/mcphub-core.jar"
    echo "Installed: $PREFIX/lib/mcphub-core.jar"
else
    echo "WARNING: mcphub-core JAR not found — build with './gradlew jar' first"
fi

# Copy adapters
if [[ -d "$REPO_DIR/adapters/dist" ]]; then
    mkdir -p "$PREFIX/adapters"
    cp -r "$REPO_DIR/adapters/dist/"* "$PREFIX/adapters/"
    echo "Installed adapters to: $PREFIX/adapters"
fi

# Step 3: Optional service install
if [[ "$INSTALL_SERVICE" -eq 1 ]]; then
    case "$PLATFORM" in
        linux)
            UNIT_DIR="$HOME/.config/systemd/user"
            mkdir -p "$UNIT_DIR"
            sed "s|{{MCPHUB_INSTALL_PATH}}|$BIN_PREFIX/mcphub|g" \
                "$REPO_DIR/packaging/systemd/mcphub.service" > "$UNIT_DIR/mcphub.service"
            echo "Installed systemd unit: $UNIT_DIR/mcphub.service"
            echo "Enable with: systemctl --user daemon-reload && systemctl --user enable --now mcphub"
            ;;
        darwin)
            PLIST_DIR="$HOME/Library/LaunchAgents"
            mkdir -p "$PLIST_DIR"
            sed "s|{{MCPHUB_INSTALL_PATH}}|$BIN_PREFIX/mcphub|g" \
                "$REPO_DIR/packaging/launchd/dev.sorted.mcphub.plist" > "$PLIST_DIR/dev.sorted.mcphub.plist"
            echo "Installed launchd agent: $PLIST_DIR/dev.sorted.mcphub.plist"
            echo "Load with: launchctl load $PLIST_DIR/dev.sorted.mcphub.plist"
            ;;
    esac
fi

echo ""
echo "Installation complete."
echo "Add $BIN_PREFIX to PATH if not already."
echo "Verify with: mcphub --help"
