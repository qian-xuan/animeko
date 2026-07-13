/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.android.kotlin.multiplatform.library)
    alias(libs.plugins.kotlin.plugin.compose)
    alias(libs.plugins.jetbrains.compose)

    `ani-mpp-lib-targets`
    alias(libs.plugins.kotlin.plugin.serialization)
}

kotlin {
    androidLibrary {
        namespace = "me.him188.ani.app.ui.syncplay"
    }
    sourceSets.commonMain.dependencies {
        api(projects.syncplay)
        api(projects.app.shared.uiFoundation)
        api(projects.app.shared.appLang)
        api(projects.danmaku.danmakuUi)
    }
    sourceSets.commonTest.dependencies {
    }
    sourceSets.androidMain.dependencies {
    }
    sourceSets.desktopMain.dependencies {
    }
}

compose.resources {
    packageOfResClass = "me.him188.ani.app.ui.syncplay"
}
