package me.him188.ani.syncplay.protocol.models

/** Network connection lifecycle states tracked by the NetworkManager. */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    SCHEDULING_RECONNECT,
}
