package com.childprotector.app.realtime

import com.childprotector.app.Api
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

class RealtimeClient(private val api: Api) {
    fun connect(
        onOpen: () -> Unit,
        onMessage: (String) -> Unit,
        onDisconnect: () -> Unit
    ): WebSocket = api.openWebSocket(object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) = onOpen()

        override fun onMessage(webSocket: WebSocket, text: String) = onMessage(text)

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = onDisconnect()

        override fun onFailure(
            webSocket: WebSocket,
            throwable: Throwable,
            response: Response?
        ) = onDisconnect()
    })
}
