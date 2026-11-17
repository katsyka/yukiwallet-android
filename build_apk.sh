#!/usr/bin/env bash
# Builds the signed release APK and publishes it at https://wallet.yukichat.lol/downloads/yukiwallet.apk
# Usage: ./build_apk.sh ["What's new in this version"]
# Bump versionCode/versionName in app/build.gradle first: the app offers the update
# when the published versionName is newer than the installed one.
set -euo pipefail
cd "$(dirname "$0")"
export ANDROID_HOME=/opt/android-sdk
OUT=/root/yukiwallet/web/downloads
mkdir -p "$OUT"

./gradlew assembleRelease --no-daemon -q
cp app/build/outputs/apk/release/app-release.apk "$OUT/yukiwallet.apk.tmp"
python3 - "$OUT/yukiwallet.json.tmp" "${1:-}" <<'PY'
import json, sys, os, hashlib, datetime
apk = "app/build/outputs/apk/release/app-release.apk"
meta = json.load(open("app/build/outputs/apk/release/output-metadata.json"))["elements"][0]
info = {"version_code": meta["versionCode"], "version_name": meta["versionName"], "notes": sys.argv[2],
        "url": "https://wallet.yukichat.lol/downloads/yukiwallet.apk",
        # Read by the app (AppUpdater.check)
        "size": os.path.getsize(apk), "sha256": hashlib.sha256(open(apk, "rb").read()).hexdigest(),
        "released": datetime.date.today().isoformat(), "min_android": "7.0"}
# Optional structured notes for the update popup: {"summary": "...", "changes": [{"emoji","title","items":[...]}]}
if os.path.exists("release_notes.json"):
    rn = json.load(open("release_notes.json"))
    info["summary"] = rn.get("summary") or info["notes"]
    info["changes"] = rn.get("changes", [])
json.dump(info, open(sys.argv[1], "w"), indent=2)
PY
# Swap both files in together so the version info always matches the APK
mv "$OUT/yukiwallet.apk.tmp" "$OUT/yukiwallet.apk"
mv "$OUT/yukiwallet.json.tmp" "$OUT/yukiwallet.json"
VER=$(python3 -c 'import json,sys;print(json.load(open(sys.argv[1]))["version_name"])' "$OUT/yukiwallet.json")
echo "Published v$VER: $OUT/yukiwallet.apk ($(du -h "$OUT/yukiwallet.apk" | cut -f1))"
