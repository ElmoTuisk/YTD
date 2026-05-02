#!/bin/bash
# =============================================================
# YTD macOS Build Script
# Builds the app bundle and packages it into a DMG
# =============================================================
# Prerequisites (install once):
#   brew install python ffmpeg create-dmg
#   pip3 install pyinstaller PyQt6 yt-dlp requests
#
# Usage:
#   cd V6-mac
#   chmod +x build_mac.sh
#   ./build_mac.sh
# =============================================================

set -e

APP_NAME="YTD"
DMG_NAME="YTD-Installer"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

cd "$SCRIPT_DIR"

echo "=== Step 1: Preparing ffmpeg binaries ==="
# Copy ffmpeg/ffprobe from Homebrew if not already in this folder
if [ ! -f ffmpeg ]; then
    FFMPEG_PATH=$(which ffmpeg 2>/dev/null || true)
    if [ -z "$FFMPEG_PATH" ]; then
        echo "ERROR: ffmpeg not found. Install with: brew install ffmpeg"
        exit 1
    fi
    cp "$FFMPEG_PATH" ./ffmpeg
    echo "  Copied ffmpeg from $FFMPEG_PATH"
fi

if [ ! -f ffprobe ]; then
    FFPROBE_PATH=$(which ffprobe 2>/dev/null || true)
    if [ -z "$FFPROBE_PATH" ]; then
        echo "ERROR: ffprobe not found. Install with: brew install ffmpeg"
        exit 1
    fi
    cp "$FFPROBE_PATH" ./ffprobe
    echo "  Copied ffprobe from $FFPROBE_PATH"
fi

echo "=== Step 2: Converting icon ==="
# Convert icon.ico to icon.icns if needed
if [ ! -f icon.icns ]; then
    if [ -f icon.ico ]; then
        # Create iconset from ico
        mkdir -p icon.iconset
        sips -s format png icon.ico --out icon.iconset/icon_512x512.png 2>/dev/null || true
        if [ -f icon.iconset/icon_512x512.png ]; then
            # Generate required sizes
            sips -z 16 16     icon.iconset/icon_512x512.png --out icon.iconset/icon_16x16.png
            sips -z 32 32     icon.iconset/icon_512x512.png --out icon.iconset/icon_16x16@2x.png
            sips -z 32 32     icon.iconset/icon_512x512.png --out icon.iconset/icon_32x32.png
            sips -z 64 64     icon.iconset/icon_512x512.png --out icon.iconset/icon_32x32@2x.png
            sips -z 128 128   icon.iconset/icon_512x512.png --out icon.iconset/icon_128x128.png
            sips -z 256 256   icon.iconset/icon_512x512.png --out icon.iconset/icon_128x128@2x.png
            sips -z 256 256   icon.iconset/icon_512x512.png --out icon.iconset/icon_256x256.png
            sips -z 512 512   icon.iconset/icon_512x512.png --out icon.iconset/icon_256x256@2x.png
            sips -z 1024 1024 icon.iconset/icon_512x512.png --out icon.iconset/icon_512x512@2x.png
            iconutil -c icns icon.iconset -o icon.icns
            rm -rf icon.iconset
            echo "  Converted icon.ico -> icon.icns"
        else
            echo "  WARNING: Could not convert icon. App will use default icon."
        fi
    else
        echo "  WARNING: No icon.ico found. Copy one from V6/ or provide icon.icns"
    fi
fi

echo "=== Step 3: Building app with PyInstaller ==="
pyinstaller --clean --noconfirm main.spec

echo "=== Step 4: Creating DMG ==="
# Clean up previous DMG
rm -f "dist/${DMG_NAME}.dmg"

# Check if create-dmg is available
if command -v create-dmg &>/dev/null; then
    create-dmg \
        --volname "${APP_NAME}" \
        --volicon "icon.icns" \
        --window-pos 200 120 \
        --window-size 600 400 \
        --icon-size 100 \
        --icon "${APP_NAME}.app" 175 190 \
        --hide-extension "${APP_NAME}.app" \
        --app-drop-link 425 190 \
        --no-internet-enable \
        "dist/${DMG_NAME}.dmg" \
        "dist/${APP_NAME}.app"
else
    echo "  create-dmg not found, using hdiutil fallback..."
    echo "  (Install create-dmg for a prettier DMG: brew install create-dmg)"

    # Create a temporary directory for DMG contents
    DMG_TEMP="dist/dmg_temp"
    rm -rf "$DMG_TEMP"
    mkdir -p "$DMG_TEMP"

    # Copy app bundle
    cp -R "dist/${APP_NAME}.app" "$DMG_TEMP/"

    # Create Applications symlink (drag-to-install)
    ln -s /Applications "$DMG_TEMP/Applications"

    # Create DMG
    hdiutil create -volname "${APP_NAME}" \
        -srcfolder "$DMG_TEMP" \
        -ov -format UDZO \
        "dist/${DMG_NAME}.dmg"

    rm -rf "$DMG_TEMP"
fi

echo ""
echo "=== Build Complete! ==="
echo "  App:  dist/${APP_NAME}.app"
echo "  DMG:  dist/${DMG_NAME}.dmg"
echo ""
echo "To install: Open the DMG and drag YTD to Applications."
