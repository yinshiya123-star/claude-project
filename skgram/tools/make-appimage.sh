#!/usr/bin/env bash
# Wraps the jpackage app folder from `gradlew :desktopApp:packageAppImage` into a single SKGram-<version>-x86_64.AppImage.
# Usage: tools/make-appimage.sh <version> [appimagetool path]
set -euo pipefail
cd "$(dirname "$0")/.."
version="$1"
tool="${2:-appimagetool}"
src=desktopApp/build/compose/binaries/main/app/SKGram
appdir=desktopApp/build/appimage/SKGram.AppDir
out=desktopApp/build/appimage/SKGram-${version}-x86_64.AppImage

rm -rf "$appdir" && mkdir -p "$appdir/usr"
cp -a "$src/." "$appdir/usr/"
cp desktopApp/icons/skgram.png "$appdir/skgram.png"
cat > "$appdir/skgram.desktop" <<DESKTOP
[Desktop Entry]
Type=Application
Name=SKGram
Comment=A fast Telegram client
Exec=SKGram
Icon=skgram
Categories=Network;InstantMessaging;
Terminal=false
DESKTOP
cat > "$appdir/AppRun" <<'APPRUN'
#!/bin/sh
here="$(dirname "$(readlink -f "$0")")"
exec "$here/usr/bin/SKGram" "$@"
APPRUN
chmod +x "$appdir/AppRun"
# appimagetool is itself an AppImage; extract-and-run avoids needing FUSE on CI machines.
APPIMAGE_EXTRACT_AND_RUN=1 ARCH=x86_64 "$tool" --no-appstream "$appdir" "$out"
echo "$out"
