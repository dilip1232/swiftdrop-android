package com.swiftdrop

import java.util.UUID

data class ChatMsg(
    val id: String = UUID.randomUUID().toString().replace("-", "").take(16),
    val text: String,
    val from: String,
    val dir: String,   // "sent" | "recv"
    val ts: Long = System.currentTimeMillis()
)

class ChatStore(private val maxPerPeer: Int = 100) {
    private val msgs = mutableMapOf<String, MutableList<ChatMsg>>()

    @Synchronized
    fun add(peerId: String, msg: ChatMsg): ChatMsg {
        val list = msgs.getOrPut(peerId) { mutableListOf() }
        list.add(msg)
        if (list.size > maxPerPeer) {
            val excess = list.size - maxPerPeer
            repeat(excess) { list.removeAt(0) }
        }
        return msg
    }

    @Synchronized
    fun since(peerId: String, since: Long): List<ChatMsg> {
        return msgs[peerId]?.filter { it.ts > since } ?: emptyList()
    }
}
