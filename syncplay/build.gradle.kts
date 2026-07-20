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
    alias(libs.plugins.kotlin.plugin.serialization)
    idea
    `ani-mpp-lib-targets`
}

kotlin {
    androidLibrary {
        namespace = "me.him188.ani.syncplay"
    }
    sourceSets.commonMain.dependencies {
        implementation(libs.kotlinx.serialization.json)
        implementation(libs.kotlinx.coroutines.core)
        implementation(libs.atomicfu)
        implementation(libs.ktor.network)
        implementation(projects.utils.logging)
    }
    sourceSets.getByName("jvmMain").dependencies {
        implementation(libs.ktor.network.tls)
    }
}
