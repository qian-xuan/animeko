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
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import me.him188.ani.app.ui.lang.*
import me.him188.ani.syncplay.engine.ChatMessage
import org.jetbrains.compose.resources.stringResource

/**
 * Chat panel for the Syncplay room. Displays the chat message log from [SyncplayViewModel.uiState]
 * and an input row for composing new messages.
 *
 * System messages (motd, join/leave) are rendered in a muted italic style; user messages show
 * the sender name in bold followed by the message text.
 *
 * The inbound chat feed is not yet wired to [DanmakuHost]; see `// T6.x` marker below.
 */
@Composable
fun ChatPanel(viewModel: SyncplayViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var inputText by remember { mutableStateOf("") }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            contentPadding = PaddingValues(8.dp),
        ) {
            items(uiState.chatMessages) { message ->
                ChatMessageItem(message)
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
            Button(
                onClick = {
                    viewModel.sendMessage(inputText)
                    inputText = ""
                },
                enabled = inputText.isNotBlank(),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Send, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text(stringResource(Lang.comment_send))
            }
        }

        // T6.x: feed inbound chat to DanmakuHost
    }
}

@Composable
private fun ChatMessageItem(message: ChatMessage) {
    if (message.isSystemMessage) {
        Text(
            text = message.message,
            style = MaterialTheme.typography.bodySmall,
            fontStyle = FontStyle.Italic,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text(
                text = message.username,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = message.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}
