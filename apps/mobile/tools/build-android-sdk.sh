#!/usr/bin/env bash
# Builds the official Bitwarden SDK for Android (com.bitwarden:sdk-android.dev:LOCAL) from
# bitwarden/sdk-internal at the same revision as the iOS sdk-swift package, and publishes it to
# ~/.m2 for Gradle. Needs: rustup, cargo-ndk, Android NDK (sdkmanager "ndk;30.0.16248370").
set -euo pipefail
REV=8da8618f9cbefbfdd26c0a300b0743081afa232f   # sdk-swift d9afe6f = sdk-internal 8da8618
SRC="${SDK_SRC:-$HOME/src/sdk-internal}"
export PATH="/opt/homebrew/opt/rustup/bin:$HOME/.cargo/bin:$PATH"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | tail -1)}"

[ -d "$SRC" ] || git clone --filter=blob:none https://github.com/bitwarden/sdk-internal.git "$SRC"
cd "$SRC" && git fetch -q origin && git checkout -q "$REV"
rustup target add aarch64-linux-android >/dev/null
cargo ndk -t arm64-v8a -P 29 build -p bitwarden-uniffi --release

cd crates/bitwarden-uniffi/kotlin
mkdir -p sdk/src/main/jniLibs/arm64-v8a
cp ../../../target/aarch64-linux-android/release/libbitwarden_uniffi.so sdk/src/main/jniLibs/arm64-v8a/
./build-schemas.sh
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew sdk:publishToMavenLocal -Pversion=LOCAL
echo "Published com.bitwarden:sdk-android.dev:LOCAL"
