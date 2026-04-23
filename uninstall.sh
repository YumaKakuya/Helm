#!/usr/bin/env bash
set -euo pipefail

PREFIX="${MCPHUB_PREFIX:-$HOME/.local/share/mcphub}"
BIN_PREFIX="${MCPHUB_BIN_PREFIX:-$HOME/.local/bin}"
OS="$(uname -s)"

echo "MCPHUB Uninstall"
echo "================"
echo "This will remove:"
echo "  $BIN_PREFIX/mcphub"
echo "  $PREFIX (all data, logs, database)"
echo ""
read -rp "Continue? [y/N] " CONFIRM
[[ "$CONFIRM" == "y" || "$CONFIRM" == "Y" ]] || exit 0

# Stop daemon if running
if [[ -x "$BIN_PREFIX/mcphub" ]]; then
    "$BIN_PREFIX/mcphub" stop 2>/dev/null || true
fi

# Remove service files
case "$OS" in
    Linux)
        SERVICE_FILE="$HOME/.config/systemd/user/mcphub.service"
        if [[ -f "$SERVICE_FILE" ]]; then
            systemctl --user stop mcphub 2>/dev/null || true
            systemctl --user disable mcphub 2>/dev/null || true
            rm -f "$SERVICE_FILE"
            systemctl --user daemon-reload
            echo "Removed systemd unit"
        fi
        ;;
    Darwin)
        PLIST="$HOME/Library/LaunchAgents/dev.sorted.mcphub.plist"
        if [[ -f "$PLIST" ]]; then
            launchctl unload "$PLIST" 2>/dev/null || true
            rm -f "$PLIST"
            echo "Removed launchd agent"
        fi
        ;;
esac

# Remove binary
rm -f "$BIN_PREFIX/mcphub"
echo "Removed binary"

# Remove data dir
rm -rf "$PREFIX"
echo "Removed data directory"

echo ""
echo "Uninstall complete."
