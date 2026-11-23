import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Platform-neutral autofill engine: field detection, form intent, URL/app matching,
// ranking and link/save suggestions. No Android or iOS APIs here, so every rule is
// unit-tested on the JVM and shared by the Android AutofillService and the iOS
// credential provider extension.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "AutofillCore"
            isStatic = true
        }
    }

    androidLibrary {
        namespace = "com.framepersecond.m3bitwarden.autofill"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }
    }

    sourceSets {
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}
