// SPDX-License-Identifier: GPL-3.0-or-later WITH LicenseRef-cardkit-ads-exception
plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.compose.compiler)
}

// The browser build: a Kotlin/Wasm executable around :shared's FiveHundredApp.
// `wasmJsBrowserDistribution` emits a static site (build/dist/wasmJs/productionExecutable)
// that GitHub Pages serves as-is; `wasmJsBrowserRun` serves it locally for development.
kotlin {
    wasmJs {
        browser {
            commonWebpackConfig {
                outputFileName = "fivehundred.js"
            }
        }
        binaries.executable()
    }

    sourceSets {
        wasmJsMain.dependencies {
            implementation(project(":shared"))
            implementation(libs.kotlinx.browser)
            // For configureWebResources { }: resource URLs must stay relative to the page.
            implementation(compose.components.resources)
        }
    }
}

compose.resources {
    packageOfResClass = "io.github.rotundtapir.fivehundred.web.generated.resources"
    // Resources live only in wasmJsMain (the fallback font); without this the Res class is
    // only generated for modules with commonMain resources.
    generateResClass = always
}
