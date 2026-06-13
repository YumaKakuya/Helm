#!/bin/sh
set -eu

# curl|sh-friendly release installer.
#
# Defaults target the current internal pre-rename artifact shape, but every public
# identity/distribution value can be overridden by environment variables:
#
#   PRODUCT_NAME="MCPHUB" PRODUCT_SLUG="mcphub" BINARY_NAME="mcphub" \
#   REPO_URL="https://github.com/OWNER/mcphub-internal" \
#   DISTRIBUTION_URL="https://github.com/OWNER/mcphub-internal/releases/download" \
#   RELEASE_TAG="v0.2.0-alpha" sh install-remote.sh
#
# For local/internal artifact verification:
#   DISTRIBUTION_URL="file:///path/to/dist/release" VERSION="0.2.0-alpha" sh install-remote.sh

PRODUCT_NAME=${PRODUCT_NAME:-MCPHUB}
PRODUCT_SLUG=${PRODUCT_SLUG:-mcphub}
BINARY_NAME=${BINARY_NAME:-$PRODUCT_SLUG}
ARTIFACT_NAME=${ARTIFACT_NAME:-$PRODUCT_SLUG}
REPO_URL=${REPO_URL:-https://github.com/OWNER/mcphub-internal}
DISTRIBUTION_URL=${DISTRIBUTION_URL:-$REPO_URL/releases/download}
RELEASE_TAG=${RELEASE_TAG:-latest}
VERSION=${VERSION:-}
INSTALL_DIR=${INSTALL_DIR:-$HOME/.local/share/$PRODUCT_SLUG}
BIN_DIR=${BIN_DIR:-$HOME/.local/bin}
RUN_DOCTOR=${RUN_DOCTOR:-1}
DRY_RUN=${DRY_RUN:-0}
KEEP_TMP=${KEEP_TMP:-0}
VERIFY_COMMAND=${VERIFY_COMMAND:-}
ARTIFACT_URL=${ARTIFACT_URL:-}
CHECKSUM_URL=${CHECKSUM_URL:-}
USE_SYSTEM_JAVA_LINK=${USE_SYSTEM_JAVA_LINK:-1}

log() { printf '%s\n' "$*"; }
err() { printf 'ERROR: %s\n' "$*" >&2; }
run() {
    if [ "$DRY_RUN" = "1" ]; then
        printf '[dry-run]'
        printf ' %s' "$@"
        printf '\n'
    else
        "$@"
    fi
}

usage() {
    cat <<EOF
${PRODUCT_NAME} remote installer

Environment variables:
  PRODUCT_NAME       Display name (default: MCPHUB)
  PRODUCT_SLUG       Install dir slug (default: mcphub)
  BINARY_NAME        Installed command name (default: PRODUCT_SLUG)
  ARTIFACT_NAME      Release archive prefix (default: PRODUCT_SLUG)
  REPO_URL           Repository URL used for default release URL
  DISTRIBUTION_URL   Release asset base URL or file:// directory
  RELEASE_TAG        Release tag path segment (default: latest)
  VERSION            Archive version segment; defaults to RELEASE_TAG without leading v
  ARTIFACT_URL       Fully qualified archive URL; overrides URL construction
  INSTALL_DIR        Install root (default: ~/.local/share/PRODUCT_SLUG)
  BIN_DIR            Command wrapper directory (default: ~/.local/bin)
  RUN_DOCTOR         Run verification command after install (default: 1)
  VERIFY_COMMAND     Override verification command
  DRY_RUN            Print actions without modifying filesystem (default: 0)

Examples:
  curl -fsSL URL/install-remote.sh | RELEASE_TAG=v0.3.0-beta sh
  DISTRIBUTION_URL=file:///tmp/release VERSION=0.2.0-alpha sh install-remote.sh
EOF
}

case "${1:-}" in
    -h|--help) usage; exit 0 ;;
esac

if [ "$(id -u)" = "0" ] && [ "${ALLOW_ROOT:-0}" != "1" ]; then
    err "do not run as root; set ALLOW_ROOT=1 only for disposable container verification"
    exit 1
fi

os=$(uname -s | tr '[:upper:]' '[:lower:]')
case "$os" in
    linux) os=linux ;;
    darwin) os=darwin ;;
    *) err "unsupported OS: $(uname -s)"; exit 1 ;;
esac

arch=$(uname -m)
case "$arch" in
    x86_64|amd64) arch=amd64 ;;
    aarch64|arm64) arch=arm64 ;;
    *) err "unsupported architecture: $arch"; exit 1 ;;
esac

if [ -z "$VERSION" ]; then
    case "$RELEASE_TAG" in
        v*) VERSION=${RELEASE_TAG#v} ;;
        latest) VERSION=latest ;;
        *) VERSION=$RELEASE_TAG ;;
    esac
fi

archive=${ARTIFACT_NAME}-${VERSION}-${os}-${arch}.tar.gz
if [ -z "$ARTIFACT_URL" ]; then
    case "$DISTRIBUTION_URL" in
        file://*) ARTIFACT_URL=${DISTRIBUTION_URL%/}/$archive ;;
        */download|*/download/) ARTIFACT_URL=${DISTRIBUTION_URL%/}/$RELEASE_TAG/$archive ;;
        *) ARTIFACT_URL=${DISTRIBUTION_URL%/}/$archive ;;
    esac
fi

log "${PRODUCT_NAME} installer"
log "  repo:        $REPO_URL"
log "  artifact:    $ARTIFACT_URL"
log "  install dir: $INSTALL_DIR"
log "  bin dir:     $BIN_DIR"
log "  platform:    $os/$arch"

if [ "$DRY_RUN" = "1" ]; then
    run mkdir -p "$INSTALL_DIR" "$BIN_DIR"
    log "Dry run complete."
    exit 0
fi

tmp=${TMPDIR:-/tmp}/${PRODUCT_SLUG}-install.$$
cleanup() {
    if [ "$KEEP_TMP" != "1" ]; then
        rm -rf "$tmp"
    else
        log "kept temp dir: $tmp"
    fi
}
trap cleanup EXIT INT TERM
mkdir -p "$tmp"
archive_path=$tmp/$archive

case "$ARTIFACT_URL" in
    file://*)
        src=${ARTIFACT_URL#file://}
        [ -f "$src" ] || { err "artifact not found: $src"; exit 1; }
        cp "$src" "$archive_path"
        ;;
    http://*|https://*)
        if command -v curl >/dev/null 2>&1; then
            curl -fL --retry 3 --connect-timeout 20 -o "$archive_path" "$ARTIFACT_URL"
        elif command -v wget >/dev/null 2>&1; then
            wget -O "$archive_path" "$ARTIFACT_URL"
        else
            err "curl or wget is required"
            exit 1
        fi
        ;;
    *)
        [ -f "$ARTIFACT_URL" ] || { err "artifact not found: $ARTIFACT_URL"; exit 1; }
        cp "$ARTIFACT_URL" "$archive_path"
        ;;
esac

if [ -n "$CHECKSUM_URL" ]; then
    sums=$tmp/checksums-sha256.txt
    case "$CHECKSUM_URL" in
        file://*) cp "${CHECKSUM_URL#file://}" "$sums" ;;
        http://*|https://*)
            if command -v curl >/dev/null 2>&1; then curl -fL -o "$sums" "$CHECKSUM_URL"; else wget -O "$sums" "$CHECKSUM_URL"; fi
            ;;
        *) cp "$CHECKSUM_URL" "$sums" ;;
    esac
    if command -v sha256sum >/dev/null 2>&1; then
        (cd "$tmp" && grep " $archive\$" checksums-sha256.txt | sha256sum -c -)
    elif command -v shasum >/dev/null 2>&1; then
        expected=$(grep " $archive\$" "$sums" | awk '{print $1}')
        actual=$(shasum -a 256 "$archive_path" | awk '{print $1}')
        [ "$expected" = "$actual" ] || { err "checksum mismatch"; exit 1; }
    else
        err "checksum requested but neither sha256sum nor shasum is available"
        exit 1
    fi
fi

mkdir -p "$tmp/extract"
tar -xzf "$archive_path" -C "$tmp/extract"

payload=""
for candidate in "$tmp/extract/$PRODUCT_SLUG" "$tmp/extract/$ARTIFACT_NAME" "$tmp/extract"/*; do
    if [ -d "$candidate/bin" ] && [ -d "$candidate/lib" ]; then
        payload=$candidate
        break
    fi
done
[ -n "$payload" ] || { err "archive payload with bin/ and lib/ not found"; exit 1; }
[ -f "$payload/bin/$BINARY_NAME" ] || { err "binary not found in archive: bin/$BINARY_NAME"; exit 1; }
[ -d "$payload/adapters" ] || { err "adapters directory not found in archive"; exit 1; }

mkdir -p "$INSTALL_DIR" "$BIN_DIR"
rm -rf "$INSTALL_DIR/bin" "$INSTALL_DIR/lib" "$INSTALL_DIR/adapters"
cp -R "$payload/bin" "$payload/lib" "$payload/adapters" "$INSTALL_DIR/"
chmod +x "$INSTALL_DIR/bin/$BINARY_NAME"

# Current pre-rename internal artifacts contain the JAR and adapters but may not
# bundle a JRE. Add a local jre/bin/java symlink to the system Java so the Go
# launcher can still use the distribution layout without source checkout state.
if [ ! -x "$INSTALL_DIR/jre/bin/java" ] && [ "$USE_SYSTEM_JAVA_LINK" = "1" ]; then
    java_path=$(command -v java || true)
    if [ -n "$java_path" ]; then
        mkdir -p "$INSTALL_DIR/jre/bin"
        ln -sf "$java_path" "$INSTALL_DIR/jre/bin/java"
    fi
fi

wrapper=$BIN_DIR/$BINARY_NAME
mkdir -p "$INSTALL_DIR/data"
cat > "$wrapper" <<EOF
#!/bin/sh
: "\${MCPHUB_DATA_DIR:=$INSTALL_DIR/data}"
: "\${MCPHUB_ADAPTER_DIR:=$INSTALL_DIR/adapters}"
export MCPHUB_DATA_DIR MCPHUB_ADAPTER_DIR
exec "$INSTALL_DIR/bin/$BINARY_NAME" "\$@"
EOF
chmod +x "$wrapper"

log "Installed payload: $INSTALL_DIR"
log "Installed command: $wrapper"

if [ "$RUN_DOCTOR" = "1" ]; then
    if [ -z "$VERIFY_COMMAND" ]; then
        VERIFY_COMMAND="\"$wrapper\" doctor"
    fi
    log "Running verification: $VERIFY_COMMAND"
    # shellcheck disable=SC2086
    sh -c "$VERIFY_COMMAND"
fi

log "Installation complete. Add $BIN_DIR to PATH if needed."
