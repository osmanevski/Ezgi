#!/bin/bash
# Yeni sürüm yayınla: sürümü yükselt, imzalı APK derle, commit + etiket, GitHub sürümü.
# Telefondaki Ezgi açılışta GitHub'daki son sürüme bakar ve "Güncelle" der.
#   ./yayinla.sh 1.1 "Ekolayzer düzeltildi"
set -euo pipefail
cd "$(dirname "$0")"

SURUM="${1:?kullanım: ./yayinla.sh <sürüm> \"<notlar>\"}"
NOTLAR="${2:?notlar gerekli: telefonda güncelleme penceresinde görünür}"
GRADLE=app/build.gradle.kts
case "$NOTLAR" in "Neler değişti"|"Neler degisti"|"") echo "notlar örnek metin: telefondaki güncelleme penceresinde bu görünür, gerçek notu yaz" >&2; exit 1;; esac

[ -f imza/keystore.properties ] || { echo "imza/keystore.properties yok: imzasız APK telefondakinin üstüne kurulmaz" >&2; exit 1; }
[ -z "$(git status --porcelain)" ] || { echo "commit edilmemiş değişiklik var; önce onları commit et" >&2; exit 1; }
git rev-parse "v$SURUM" >/dev/null 2>&1 && { echo "v$SURUM zaten var" >&2; exit 1; }

KOD=$(( $(sed -nE 's/ *versionCode = ([0-9]+)/\1/p' "$GRADLE") + 1 ))
sed -i '' -E "s/versionCode = [0-9]+/versionCode = $KOD/; s/versionName = \"[^\"]+\"/versionName = \"$SURUM\"/" "$GRADLE"

export JAVA_HOME="${JAVA_HOME:-/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home}"
./gradlew -q --no-daemon -Pkotlin.compiler.execution.strategy=in-process :app:assembleRelease
APK="build/dagitim/Ezgi-$SURUM.apk"
mkdir -p build/dagitim && cp app/build/outputs/apk/release/app-release.apk "$APK"
# Sabit adlı kopya: …/releases/latest/download/Ezgi.apk her zaman son sürümü verir.
cp "$APK" build/dagitim/Ezgi.apk

git add "$GRADLE"
git commit -q -m "Ezgi $SURUM" -m "$NOTLAR"
git tag "v$SURUM"
git push -q origin HEAD "v$SURUM"
gh release create "v$SURUM" "$APK" build/dagitim/Ezgi.apk --title "Ezgi $SURUM" --notes "$NOTLAR"
echo "yayınlandı: $APK (versionCode $KOD)"
