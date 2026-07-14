/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.syncplay

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.syncplay_cancel
import me.him188.ani.app.ui.lang.syncplay_join
import me.him188.ani.app.ui.lang.syncplay_join_room_title
import me.him188.ani.app.ui.lang.syncplay_room_name
import me.him188.ani.app.ui.lang.syncplay_room_name_invalid
import me.him188.ani.app.ui.lang.syncplay_server
import me.him188.ani.app.ui.lang.syncplay_use_tls
import me.him188.ani.app.ui.lang.syncplay_username
import me.him188.ani.app.ui.lang.syncplay_username_invalid
import org.jetbrains.compose.resources.stringResource

/**
 * Preset public Syncplay server endpoints.
 */
private val PRESET_SERVERS = listOf(
    "syncplay.pl:8995",
    "syncplay.pl:8996",
    "syncplay.pl:8997",
    "syncplay.pl:8998",
    "syncplay.pl:8999",
)

private const val ROOM_NAME_MIN = 6
private const val ROOM_NAME_MAX = 10
private const val USERNAME_MIN = 4
private const val USERNAME_MAX = 12

/**
 * Dialog for joining a Syncplay room. Collects room name, username, server endpoint,
 * and TLS preference, then calls [SyncplayViewModel.connect].
 *
 * @param viewModel the Syncplay ViewModel to invoke
 * @param onDismiss called when the dialog is cancelled or after a successful join request
 */
@Composable
fun JoinRoomDialog(
    viewModel: SyncplayViewModel,
    onDismiss: () -> Unit,
) {
    var roomName by rememberSaveable { mutableStateOf("") }
    var username by rememberSaveable { mutableStateOf("") }
    var serverText by rememberSaveable { mutableStateOf(PRESET_SERVERS.first()) }
    var serverMenuExpanded by remember { mutableStateOf(false) }
    var enableTLS by rememberSaveable { mutableStateOf(false) }

    val isRoomNameValid = roomName.length in ROOM_NAME_MIN..ROOM_NAME_MAX
    val isUsernameValid = username.length in USERNAME_MIN..USERNAME_MAX
    val serverParts = serverText.split(":")
    val isServerValid = serverParts.size == 2 &&
        serverParts[0].isNotEmpty() &&
        serverParts[1].toIntOrNull() != null
    val isFormValid = isRoomNameValid && isUsernameValid && isServerValid

    AlertDialog(
        title = { Text(stringResource(Lang.syncplay_join_room_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(
                    value = roomName,
                    onValueChange = { roomName = it },
                    label = { Text(stringResource(Lang.syncplay_room_name)) },
                    isError = roomName.isNotEmpty() && !isRoomNameValid,
                    supportingText = if (roomName.isNotEmpty() && !isRoomNameValid) {
                        { Text(stringResource(Lang.syncplay_room_name_invalid)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                TextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(Lang.syncplay_username)) },
                    isError = username.isNotEmpty() && !isUsernameValid,
                    supportingText = if (username.isNotEmpty() && !isUsernameValid) {
                        { Text(stringResource(Lang.syncplay_username_invalid)) }
                    } else null,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Box {
                    TextField(
                        value = serverText,
                        onValueChange = { serverText = it },
                        label = { Text(stringResource(Lang.syncplay_server)) },
                        trailingIcon = {
                            IconButton(onClick = { serverMenuExpanded = !serverMenuExpanded }) {
                                Icon(Icons.Default.ArrowDropDown, contentDescription = null)
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DropdownMenu(
                        expanded = serverMenuExpanded,
                        onDismissRequest = { serverMenuExpanded = false },
                    ) {
                        PRESET_SERVERS.forEach { server ->
                            DropdownMenuItem(
                                text = { Text(server) },
                                onClick = {
                                    serverText = server
                                    serverMenuExpanded = false
                                },
                            )
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = enableTLS,
                        onCheckedChange = { enableTLS = it },
                    )
                    Text(stringResource(Lang.syncplay_use_tls))
                }
            }
        },
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(
                onClick = {
                    val parts = serverText.split(":")
                    viewModel.connect(
                        host = parts[0],
                        port = parts[1].toInt(),
                        room = roomName,
                        username = username,
                        password = "",
                        enableTLS = enableTLS,
                    )
                    onDismiss()
                },
                enabled = isFormValid,
            ) {
                Text(stringResource(Lang.syncplay_join))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(Lang.syncplay_cancel))
            }
        },
    )
}
