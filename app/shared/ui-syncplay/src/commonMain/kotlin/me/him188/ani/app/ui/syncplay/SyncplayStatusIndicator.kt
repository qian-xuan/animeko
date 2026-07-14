/*
 * Copyright (C) 2024-2026 OpenAni and contributors.
 *
 * 此源代码的使用受 GNU AFFERO GENERAL PUBLIC LICENSE version 3 许可证的约束, 可以在以下链接找到该许可证.
 * Use of this source code is governed by the GNU AGPLv3 license, which can be found at the following link.
 *
 * https://github.com/open-ani/ani/blob/main/LICENSE
 */

package me.him188.ani.app.ui.syncplay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import me.him188.ani.syncplay.protocol.models.ConnectionState

/**
 * 一起看 (Watch Together) 连接状态指示器.
 *
 * 当 [SyncplayViewModel.SyncplayUiState.connectionState] 为 [ConnectionState.DISCONNECTED] 时不显示.
 * 连接中显示黄色圆点, 已连接显示绿色圆点 + 房间名 + RTT.
 *
 * 这是一个紧凑的状态徽章, 不是完整的面板.
 *
 * @param uiState 当前 Syncplay UI 状态
 */
@Composable
fun SyncplayStatusIndicator(
    uiState: SyncplayViewModel.SyncplayUiState,
) {
    if (uiState.connectionState == ConnectionState.DISCONNECTED) return

    val statusColor = when (uiState.connectionState) {
        ConnectionState.CONNECTED -> Color(0xFF4CAF50) // green
        else -> Color(0xFFFFC107) // CONNECTING, SCHEDULING_RECONNECT — amber
    }

    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f),
        contentColor = MaterialTheme.colorScheme.onSurface,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Connection status dot
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor),
            )
            if (uiState.connectionState == ConnectionState.CONNECTED) {
                Text(
                    text = uiState.room,
                    style = MaterialTheme.typography.labelSmall,
                )
                if (uiState.rtt > 0) {
                    Text(
                        text = "${uiState.rtt}ms",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                // T6.3: i18n for "连接中"
                Text(
                    text = "连接中",
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
    }
}
