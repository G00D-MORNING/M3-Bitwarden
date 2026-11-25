#!/bin/bash
# Builds the Proton Authenticator core (proton-core/, Rust) for iOS and Android and generates the
# Swift and Kotlin bindings. Needs rustup targets aarch64-apple-ios(-sim) and aarch64-linux-android,
# cargo-ndk and an Android NDK.
set -euo pipefail
cd "$(dirname "$0")/../proton-core"
export PATH="$HOME/.rustup/toolchains/stable-aarch64-apple-darwin/bin:$HOME/.cargo/bin:$PATH"
OUT_IOS=../iosApp/ProtonCore
OUT_ANDROID=../androidApp/src/main

cargo build --release --lib --target aarch64-apple-ios
cargo build --release --lib --target aarch64-apple-ios-sim
cargo build --release --lib   # host build, for binding generation

rm -rf "$OUT_IOS" target/bindings && mkdir -p "$OUT_IOS" target/bindings/swift target/bindings/kotlin
cargo run --release --bin uniffi-bindgen -- generate --library target/release/libm3_proton_core.dylib --language swift --out-dir target/bindings/swift
cargo run --release --bin uniffi-bindgen -- generate --library target/release/libm3_proton_core.dylib --language kotlin --out-dir target/bindings/kotlin

# Headers in their own folder: Xcode copies them to include/, where a bare module.modulemap
# would clash with the one from the Bitwarden SDK.
HEADERS=target/bindings/headers && rm -rf $HEADERS && mkdir -p $HEADERS/M3ProtonCore
cp target/bindings/swift/*.h $HEADERS/M3ProtonCore/
cat target/bindings/swift/*.modulemap > $HEADERS/M3ProtonCore/module.modulemap
xcodebuild -create-xcframework \
  -library target/aarch64-apple-ios/release/libm3_proton_core.a -headers $HEADERS \
  -library target/aarch64-apple-ios-sim/release/libm3_proton_core.a -headers $HEADERS \
  -output "$OUT_IOS/M3ProtonCore.xcframework"
cp target/bindings/swift/*.swift "$OUT_IOS/"

NDK_DIR=$(ls -d "$HOME/Library/Android/sdk/ndk/"* | sort -V | tail -1)
ANDROID_NDK_HOME="$NDK_DIR" cargo ndk -t arm64-v8a -o "$OUT_ANDROID/jniLibs" build --release --lib
mkdir -p "$OUT_ANDROID/kotlin/uniffi/m3_proton"
cp target/bindings/kotlin/uniffi/m3_proton/*.kt "$OUT_ANDROID/kotlin/uniffi/m3_proton/"
echo "done"
