#!/bin/zsh
# Сборка lampa-vlc-bridge без Gradle: aapt2 → javac → d8 → zipalign → apksigner.
# Ключ подписи лежит в release.keystore рядом. Не терять его: обновления
# поверх установленной версии подписываются только этим ключом.
set -euo pipefail

cd "$(dirname "$0")"
SDK=/opt/homebrew/share/android-commandlinetools
BT=$SDK/build-tools/35.0.0
ANDROID_JAR=$SDK/platforms/android-35/android.jar
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
export PATH="$JAVA_HOME/bin:$PATH"

rm -rf build && mkdir -p build/classes build/dex

"$BT/aapt2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml \
    --min-sdk-version 21 --target-sdk-version 34 -o build/unsigned.apk

javac -source 8 -target 8 -Xlint:-options -classpath "$ANDROID_JAR" \
    -d build/classes $(find src -name '*.java')

"$BT/d8" --lib "$ANDROID_JAR" --min-api 21 --release \
    --output build/dex $(find build/classes -name '*.class')

(cd build/dex && zip -q ../unsigned.apk classes.dex)

"$BT/zipalign" -f -p 4 build/unsigned.apk build/aligned.apk

if [ ! -f release.keystore ]; then
    keytool -genkeypair -keystore release.keystore -alias bridge \
        -keyalg RSA -keysize 2048 -validity 36500 \
        -storepass vlcbridge -keypass vlcbridge \
        -dname "CN=lampa-vlc-bridge"
fi

"$BT/apksigner" sign --ks release.keystore --ks-key-alias bridge \
    --ks-pass pass:vlcbridge --key-pass pass:vlcbridge \
    --out build/lampa-vlc-bridge.apk build/aligned.apk

"$BT/apksigner" verify build/lampa-vlc-bridge.apk
echo "OK: $(pwd)/build/lampa-vlc-bridge.apk"
