/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.syncplay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.lang.Lang
import me.him188.ani.app.ui.lang.syncplay_controller
import me.him188.ani.app.ui.lang.syncplay_ready
import me.him188.ani.app.ui.lang.syncplay_waiting_for_peers
import me.him188.ani.syncplay.protocol.models.User
import org.jetbrains.compose.resources.stringResource

/**
 * Displays the list of peers in the current Syncplay room.
 *
 * Each row shows the peer's username, a green checkmark when [User.readiness] is true,
 * a star icon when [User.isController] is true, and the file name from [User.file] when
 * the user is playing a file.
 *
 * When [SyncplayViewModel.SyncplayUiState.userList] is empty, a centered placeholder
 * message is shown instead.
 */
@Composable
fun PeerList(uiState: SyncplayViewModel.SyncplayUiState) {
    val peers = uiState.userList
    if (peers.isEmpty()) {
        Box(
            Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                stringResource(Lang.syncplay_waiting_for_peers),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(Modifier.fillMaxSize()) {
        items(peers) { user ->
            PeerRow(user)
        }
    }
}

@Composable
private fun PeerRow(user: User) {
    val file = user.file
    ListItem(
        headlineContent = {
            Text(user.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = if (file != null && file.fileName.isNotEmpty()) {
            { Text(file.fileName, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        } else null,
        leadingContent = {
            Icon(Icons.Rounded.Person, contentDescription = null)
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (user.isController) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = stringResource(Lang.syncplay_controller),
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                }
                if (user.readiness) {
                    Icon(
                        Icons.Default.Check,
                        contentDescription = stringResource(Lang.syncplay_ready),
                        tint = Color(0xFF4CAF50),
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
        },
    )
}
