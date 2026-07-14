/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.syncplay

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import me.him188.ani.app.ui.foundation.AbstractViewModel
import me.him188.ani.app.ui.foundation.launchInBackground
import me.him188.ani.syncplay.engine.ChatMessage
import me.him188.ani.syncplay.engine.SyncplayController
import me.him188.ani.syncplay.protocol.models.ConnectionState
import me.him188.ani.syncplay.protocol.models.User

/**
 * ViewModel for the Syncplay UI. Injects a [SyncplayController] (resolved by Koin) and
 * exposes a single [SyncplayUiState] flow built by combining the controller's
 * connection state, room name, user list, chat log, and local readiness.
 *
 * The room name is tracked via [roomFlow] because [Session.currentRoom] is a plain
 * `var` (not a flow); [roomFlow] is updated in [connect] so the UI state reflects the
 * room the user is joining.
 *
 * `rtt` is reserved for a future round-trip-time metric and defaults to `0` until the
 * controller exposes one.
 */
class SyncplayViewModel(
    private val controller: SyncplayController,
) : AbstractViewModel() {

    data class SyncplayUiState(
        val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
        val room: String = "",
        val userList: List<User> = emptyList(),
        val chatMessages: List<ChatMessage> = emptyList(),
        val rtt: Long = 0,
        val isReady: Boolean = true,
    )

    private val roomFlow = MutableStateFlow(controller.session.currentRoom)

    val uiState: StateFlow<SyncplayUiState> = combine(
        controller.state,
        roomFlow,
        controller.userList,
        controller.messageSequence,
        controller.session.ready,
    ) { connectionState, room, userList, chatMessages, isReady ->
        SyncplayUiState(
            connectionState = connectionState,
            room = room,
            userList = userList,
            chatMessages = chatMessages,
            isReady = isReady,
        )
    }.stateInBackground(SyncplayUiState())

    fun connect(
        host: String,
        port: Int,
        room: String,
        username: String,
        password: String,
        enableTLS: Boolean,
    ) {
        roomFlow.value = room
        launchInBackground {
            controller.connect(host, port, room, username, password, enableTLS)
        }
    }

    fun disconnect() {
        controller.disconnect()
    }

    fun sendMessage(message: String) {
        launchInBackground {
            controller.dispatcher.sendMessage(message)
        }
    }
}
