#!/usr/bin/env bash
set -euo pipefail

# ============================================================================
APP_PKG="uk.simlink.lpa"
JAVA_PKG="im.angry.openeuicc.bridge"
APKTOOL_TREE="eazyeuicc"
SRC_DIR="src"
OUT_DIR="out"
DEPS_DIR="deps"
ORIG_APK="eazyeuicc.apk"
BAKSMALI_JAR="tools/baksmali.jar"
# ============================================================================

pick_smali_bucket() {
  local base="$APKTOOL_TREE"
  local n=2
  [[ -d "$base/smali" ]] && echo "$base/smali" && return 0
  while [[ -d "$base/smali_classes$n" ]]; do n=$((n+1)); done
  echo "$base/smali_classes$n"
}

# Derived paths/names
SMALI_PKG_DIR="${JAVA_PKG//.//}"
SRC_PKG_DIR="${SRC_DIR}/${SMALI_PKG_DIR}"
TARGET_SMALI_ROOT="$(pick_smali_bucket)"
TARGET_SMALI_DIR="${TARGET_SMALI_ROOT}/${SMALI_PKG_DIR}"

# SDK paths
ANDROID_SDK="$HOME/Library/Android/sdk"
ANDROID_JAR="$ANDROID_SDK/platforms/android-33/android.jar"
BT="$ANDROID_SDK/build-tools/$(ls "$ANDROID_SDK/build-tools" | sort -V | tail -1)"

# Sanity: tools present
command -v apktool >/dev/null || { echo "ERROR: apktool not found"; exit 1; }
command -v adb     >/dev/null || { echo "ERROR: adb not found"; exit 1; }
command -v javac   >/dev/null || { echo "ERROR: javac not found"; exit 1; }
command -v java    >/dev/null || { echo "ERROR: java not found"; exit 1; }
command -v keytool >/dev/null || { echo "ERROR: keytool not found"; exit 1; }

# Sanity: files/dirs
[[ -f "$ANDROID_JAR" ]]  || { echo "ERROR: android.jar not found: $ANDROID_JAR"; exit 1; }
[[ -x "$BT/d8" ]]        || { echo "ERROR: d8 not found: $BT/d8"; exit 1; }
[[ -x "$BT/zipalign" ]]  || { echo "ERROR: zipalign not found: $BT/zipalign"; exit 1; }
[[ -x "$BT/apksigner" ]] || { echo "ERROR: apksigner not found: $BT/apksigner"; exit 1; }
[[ -f "$BAKSMALI_JAR" ]] || { echo "ERROR: baksmali jar missing: $BAKSMALI_JAR"; exit 1; }
[[ -d "$APKTOOL_TREE" ]] || { echo "ERROR: apktool tree missing: $APKTOOL_TREE"; exit 1; }
[[ -d "$SRC_PKG_DIR" ]]  || { echo "ERROR: source package dir missing: $SRC_PKG_DIR"; exit 1; }
mkdir -p "$DEPS_DIR"

# Ensure we have a complete classpath jar containing ALL dexes (kotlin, coroutines, lpac_jni, etc.)
rebuild_cp_jar() {
  local apk="$1"
  local dexdir="$DEPS_DIR/_dex"
  local mergedir="$DEPS_DIR/_merge"
  local jar_out="$DEPS_DIR/eazyeuicc.jar"
  rm -rf "$dexdir" "$mergedir"
  mkdir -p "$dexdir" "$mergedir"

  echo "[dex] extracting classes*.dex from $apk"
  unzip -q -j "$apk" 'classes*.dex' -d "$dexdir"

  local any=0
  for d in "$dexdir"/classes*.dex; do
    [[ -f "$d" ]] || continue
    any=1
    local j="${d%.dex}.jar"
    echo "[dex2jar] $d -> $j"
    d2j-dex2jar "$d" -o "$j" >/dev/null
    unzip -q -o "$j" -d "$mergedir"
  done
  [[ $any -eq 1 ]] || { echo "ERROR: no classes*.dex found in $apk"; exit 1; }

  (cd "$mergedir" && jar cf "../eazyeuicc.jar" .)
  rm -rf "$dexdir" "$mergedir"
}

# (Re)build deps jar if missing or incomplete
APP_JAR="$DEPS_DIR/eazyeuicc.jar"
need_rebuild=0
if [[ ! -f "$APP_JAR" ]]; then
  need_rebuild=1
else
  jar tf "$APP_JAR" | grep -q '^kotlin/'                  || need_rebuild=1
  jar tf "$APP_JAR" | grep -q '^kotlinx/coroutines/'      || need_rebuild=1
  jar tf "$APP_JAR" | grep -q '^net/typeblog/lpac_jni/'   || need_rebuild=1
fi
if [[ $need_rebuild -eq 1 ]]; then
  [[ -f "$ORIG_APK" ]] || { echo "ERROR: ORIG_APK not found: $ORIG_APK"; exit 1; }
  rebuild_cp_jar "$ORIG_APK"
fi

# Verify again (fail fast with helpful messages)
jar tf "$APP_JAR" | grep -q '^kotlin/'                || { echo "ERROR: Kotlin stdlib missing in $APP_JAR"; exit 1; }
jar tf "$APP_JAR" | grep -q '^kotlinx/coroutines/'    || { echo "ERROR: kotlinx coroutines missing in $APP_JAR"; exit 1; }
jar tf "$APP_JAR" | grep -q '^net/typeblog/lpac_jni/' || { echo "ERROR: net.typeblog.lpac_jni missing in $APP_JAR"; exit 1; }

# Gather sources
JAVA_SOURCES=()
while IFS= read -r -d '' f; do
  JAVA_SOURCES+=("$f")
done < <(find "$SRC_PKG_DIR" -type f -name '*.java' -print0)
[[ ${#JAVA_SOURCES[@]} -gt 0 ]] || { echo "ERROR: no .java files found under $SRC_PKG_DIR"; exit 1; }

# Clean + mkdirs
rm -rf "$APKTOOL_TREE/build"
rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"/{classes,dex,smali}

# Build classpath from all jars in deps
CP="$ANDROID_JAR:$(find "$DEPS_DIR" -maxdepth 1 -type f -name '*.jar' | paste -sd: -)"

# Compile
javac \
  -source 23 -target 23 \
  -classpath "$CP" \
  -Xlint:-options \
  -encoding UTF-8 \
  -d "$OUT_DIR/classes" \
  "${JAVA_SOURCES[@]}"

# DEX
D8_CLASSPATH_ARGS=()
IFS=':' read -ra CP_ENTRIES <<< "$CP"
for entry in "${CP_ENTRIES[@]}"; do
  D8_CLASSPATH_ARGS+=(--classpath "$entry")
done
"$BT/d8" \
  --lib "$ANDROID_JAR" \
  "${D8_CLASSPATH_ARGS[@]}" \
  --output "$OUT_DIR/dex" \
  $(find "$OUT_DIR/classes" -type f -name '*.class')


# Smali
found_dex=0
for dex in "$OUT_DIR"/dex/*.dex; do
  [[ -f "$dex" ]] || continue
  found_dex=1
  java -jar "$BAKSMALI_JAR" d "$dex" -o "$OUT_DIR/smali"
done
[[ $found_dex -eq 1 ]] || { echo "ERROR: no dex files produced"; exit 1; }
mkdir -p "$TARGET_SMALI_DIR"
if [[ -d "$OUT_DIR/smali/$SMALI_PKG_DIR" ]]; then
  rm -rf "$TARGET_SMALI_DIR"
  mkdir -p "$TARGET_SMALI_DIR"
  cp -R "$OUT_DIR/smali/$SMALI_PKG_DIR/." "$TARGET_SMALI_DIR/"
else
  echo "ERROR: expected smali package dir not found: $OUT_DIR/smali/$SMALI_PKG_DIR"
  exit 1
fi

# Rebuild
apktool b "$APKTOOL_TREE" -o "$OUT_DIR/build-unsigned.apk"

# Align
"$BT/zipalign" -p -f 4 "$OUT_DIR/build-unsigned.apk" "$OUT_DIR/build-aligned.apk"

# Ensure keystore
if [ ! -f "$HOME/.android/debug.keystore" ]; then
  mkdir -p "$HOME/.android"
  keytool -genkeypair \
    -keystore "$HOME/.android/debug.keystore" \
    -storepass android -keypass android \
    -alias androiddebugkey \
    -keyalg RSA -keysize 2048 -validity 10000 \
    -dname "CN=Android Debug,O=Android,C=US"
fi

# Sign
java --enable-native-access=ALL-UNNAMED \
  -jar "$BT/lib/apksigner.jar" sign \
  --ks "$HOME/.android/debug.keystore" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android --key-pass pass:android \
  --out "$OUT_DIR/build-signed.apk" \
  "$OUT_DIR/build-aligned.apk"

# Install
adb install -r "$OUT_DIR/build-signed.apk"
