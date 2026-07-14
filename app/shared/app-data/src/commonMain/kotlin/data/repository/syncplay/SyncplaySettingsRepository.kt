/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.data.repository.syncplay

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Persists the last used Syncplay server endpoint, room, and username.
 *
 * Backed by [preferencesStore][me.him188.ani.app.data.persistent.PlatformDataStoreManager.preferencesStore],
 * mirroring the [me.him188.ani.app.data.repository.user.PreferencesRepositoryImpl] pattern.
 */
class SyncplaySettingsRepository(
    private val preferences: DataStore<Preferences>,
) {
    val serverEndpoint: Flow<String> = preferences.data
        .map { it[KEY_SERVER_ENDPOINT] ?: DEFAULT_SERVER_ENDPOINT }

    val lastRoom: Flow<String> = preferences.data
        .map { it[KEY_LAST_ROOM] ?: DEFAULT_LAST_ROOM }

    val lastUsername: Flow<String> = preferences.data
        .map { it[KEY_LAST_USERNAME] ?: DEFAULT_LAST_USERNAME }

    suspend fun setServerEndpoint(value: String) {
        preferences.edit { it[KEY_SERVER_ENDPOINT] = value }
    }

    suspend fun setLastRoom(value: String) {
        preferences.edit { it[KEY_LAST_ROOM] = value }
    }

    suspend fun setLastUsername(value: String) {
        preferences.edit { it[KEY_LAST_USERNAME] = value }
    }

    private companion object {
        val KEY_SERVER_ENDPOINT = stringPreferencesKey("syncplay.serverEndpoint")
        val KEY_LAST_ROOM = stringPreferencesKey("syncplay.lastRoom")
        val KEY_LAST_USERNAME = stringPreferencesKey("syncplay.lastUsername")

        const val DEFAULT_SERVER_ENDPOINT = "syncplay.pl:8996"
        const val DEFAULT_LAST_ROOM = ""
        const val DEFAULT_LAST_USERNAME = ""
    }
}
