pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        // The SDK pulls the LP3 keyboard from JitPack.
        maven {
            name = "JitPack"
            url = uri("https://jitpack.io")
        }
    }
    versionCatalogs {
        create("libs") {
            from(files("light-sdk/gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "light-reader"

// Light's SDK is the pristine `light-sdk` submodule (pinned to a release tag); Reader is the
// `tool/` module built against it, the shape Light builds and signs community tools from.
val sdkDir = file("light-sdk")
if (!File(sdkDir, "sdk").exists()) {
    error("The light-sdk submodule is empty. Run: git submodule update --init")
}

includeBuild("light-sdk/plugin")

include(":lint-rules")
project(":lint-rules").projectDir = file("light-sdk/lint-rules")

include(":sdk:shared")
project(":sdk").projectDir = file("light-sdk/sdk")
project(":sdk:shared").projectDir = file("light-sdk/sdk/shared")

include(":sdk:ui")
project(":sdk:ui").projectDir = file("light-sdk/sdk/ui")

include(":sdk:client")
project(":sdk:client").projectDir = file("light-sdk/sdk/client")

include(":sdk:server")
project(":sdk:server").projectDir = file("light-sdk/sdk/server")

include(":sdk:emulator")
project(":sdk:emulator").projectDir = file("light-sdk/sdk/emulator")

include(":tool")
