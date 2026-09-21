package com.example.ktorservice.service

import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.send
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class ControlChangedEvent(
    val type: String = "CONTROL_CHANGED",
    val version: Long
)

@Serializable
data class VideoChangedEvent(
    val type: String = "VIDEO_CHANGED",
    val version: Long
)

object ControlWebSocketHub {

    private val sessions =
        ConcurrentHashMap<Int, MutableSet<WebSocketSession>>()

    private val json =
        Json {
            ignoreUnknownKeys = true
        }

    fun register(
        childUserId: Int,
        session: WebSocketSession
    ) {
        val set =
            sessions.computeIfAbsent(
                childUserId
            ) {
                ConcurrentHashMap.newKeySet()
            }

        set.add(session)
    }

    fun unregister(
        childUserId: Int,
        session: WebSocketSession
    ) {
        val set =
            sessions[childUserId]
                ?: return

        set.remove(session)

        if (set.isEmpty()) {
            sessions.remove(childUserId, set)
        }
    }

    /**
     * Thông báo Control đã thay đổi.
     *
     * Receiver nhận event này rồi gọi:
     * GET /control/state
     */
    suspend fun notifyChanged(
        childUserId: Int,
        version: Long
    ) {
        val set =
            sessions[childUserId]
                ?: return

        val message =
            json.encodeToString(
                ControlChangedEvent(
                    version = version
                )
            )

        sendToSessions(
            childUserId = childUserId,
            set = set,
            message = message
        )
    }

    /**
     * Thông báo video mới đã được upload.
     *
     * Receiver nhận event này rồi gọi:
     * VideoSyncManager.sync()
     */
    suspend fun notifyVideoChanged(
        childUserId: Int,
        version: Long
    ) {
        val set =
            sessions[childUserId]
                ?: return

        val message =
            json.encodeToString(
                VideoChangedEvent(
                    version = version
                )
            )

        sendToSessions(
            childUserId = childUserId,
            set = set,
            message = message
        )
    }

    /**
     * Gửi message tới toàn bộ WebSocket session
     * của một child.
     *
     * Session lỗi sẽ được tự động loại bỏ.
     */
    private suspend fun sendToSessions(
        childUserId: Int,
        set: MutableSet<WebSocketSession>,
        message: String
    ) {
        val deadSessions =
            mutableListOf<WebSocketSession>()

        for (session in set) {
            try {
                session.send(message)
            } catch (e: Exception) {
                deadSessions.add(session)
            }
        }

        deadSessions.forEach {
            set.remove(it)
        }

        if (set.isEmpty()) {
            sessions.remove(childUserId, set)
        }
    }
}