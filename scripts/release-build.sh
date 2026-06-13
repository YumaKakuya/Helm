#!/usr/bin/env bash
set -euo pipefail

# MCPHUB Release Build Script — BL-06 / P1-pre T-P1-P2
# Builds distribution archives for release automation.
# Usage: PRODUCT_NAME=mcphub ./scripts/release-build.sh [version]
# Output: dist/release/<product>-<version>-<os>-<arch>.tar.gz

PRODUCT_NAME="${PRODUCT_NAME:-mcphub}"
VERSION="${1:-$(grep 'const version' cmd/mcphub/main.go | grep -oP '"[^"]+"' | tr -d '"')}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DIST_DIR="$REPO_ROOT/dist/release"
PLATFORMS=(
    "linux/amd64"
    "darwin/amd64"
    "darwin/arm64"
)

echo "=== ${PRODUCT_NAME} Release Build ==="
echo "Product: $PRODUCT_NAME"
echo "Version: $VERSION"
echo "Output:  $DIST_DIR"
echo ""

# Clean
rm -rf "$DIST_DIR"
mkdir -p "$DIST_DIR"

# Step 1: Build Java fat-JAR (platform-independent)
echo "==> Building Java fat-JAR..."
cd "$REPO_ROOT/java"
./gradlew jar --quiet
JAR_FILE=$(find build/libs -name "mcphub-core-*.jar" ! -name "*plain*" | head -1)
if [[ -z "$JAR_FILE" ]]; then
    echo "ERROR: JAR not found after build" >&2
    exit 1
fi
echo "    $JAR_FILE"

# Step 2: Build TypeScript adapters (platform-independent)
echo "==> Building TypeScript adapters..."
cd "$REPO_ROOT/adapters"
if [[ ! -d node_modules ]]; then
    npm install --silent
fi
npx tsc --build
echo "    adapters/dist/"

# Step 3: Build Go binaries for each platform
cd "$REPO_ROOT"
for PLATFORM in "${PLATFORMS[@]}"; do
    OS="${PLATFORM%%/*}"
    ARCH="${PLATFORM##*/}"
    BINARY_NAME="mcphub"

    echo "==> Building Go binary: ${OS}/${ARCH}..."
    GOOS="$OS" GOARCH="$ARCH" go build -o "$DIST_DIR/${PRODUCT_NAME}-${OS}-${ARCH}" ./cmd/mcphub

    # Step 4: Create distribution archive
    ARCHIVE_NAME="${PRODUCT_NAME}-${VERSION}-${OS}-${ARCH}"
    STAGE_DIR="$DIST_DIR/stage/${ARCHIVE_NAME}/${PRODUCT_NAME}"
    mkdir -p "$STAGE_DIR/bin" "$STAGE_DIR/lib" "$STAGE_DIR/adapters"

    # Go binary
    cp "$DIST_DIR/${PRODUCT_NAME}-${OS}-${ARCH}" "$STAGE_DIR/bin/${PRODUCT_NAME}"
    chmod +x "$STAGE_DIR/bin/${PRODUCT_NAME}"

    # Java JAR
    cp "$REPO_ROOT/java/$JAR_FILE" "$STAGE_DIR/lib/mcphub-core.jar"

    # Adapters
    cp -r "$REPO_ROOT/adapters/dist/"* "$STAGE_DIR/adapters/"

    # Support files
    cp "$REPO_ROOT/install.sh" "$STAGE_DIR/"
    cp "$REPO_ROOT/install-remote.sh" "$STAGE_DIR/"
    cp "$REPO_ROOT/README.md" "$STAGE_DIR/"
    cp "$REPO_ROOT/LICENSE" "$STAGE_DIR/"

    # Create archive
    echo "    Packaging ${ARCHIVE_NAME}.tar.gz..."
    tar -czf "$DIST_DIR/${ARCHIVE_NAME}.tar.gz" \
        -C "$DIST_DIR/stage/${ARCHIVE_NAME}" \
        "${PRODUCT_NAME}"

    echo "    $DIST_DIR/${ARCHIVE_NAME}.tar.gz"
done

# Step 5: Generate checksums
echo "==> Generating checksums..."
cd "$DIST_DIR"
sha256sum "${PRODUCT_NAME}-${VERSION}"-*.tar.gz > sha256sums.txt
echo "    sha256sums.txt"

# Cleanup staging and intermediate binaries
rm -rf "$DIST_DIR/stage" "$DIST_DIR"/"${PRODUCT_NAME}"-linux-* "$DIST_DIR"/"${PRODUCT_NAME}"-darwin-*

echo ""
echo "=== Release Build Complete ==="
echo "Archives:"
ls -lh "$DIST_DIR"/"${PRODUCT_NAME}-${VERSION}"-*.tar.gz
echo ""
echo "Checksums:"
cat "$DIST_DIR/sha256sums.txt"
