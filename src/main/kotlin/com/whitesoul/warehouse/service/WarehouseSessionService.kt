package com.whitesoul.warehouse.service

import com.whitesoul.warehouse.model.WarehouseSession
import org.bukkit.entity.Player
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object WarehouseSessionService {

    private val sessions = ConcurrentHashMap<UUID, WarehouseSession>()

    fun begin(player: Player, currentPage: Int? = null): Boolean {
        val session = WarehouseSession(player.uniqueId, currentPage, false)
        return sessions.putIfAbsent(player.uniqueId, session) == null
    }

    fun beginTransition(player: Player, currentPage: Int? = null) {
        val session = sessions.computeIfAbsent(player.uniqueId) { WarehouseSession(player.uniqueId) }
        session.currentPage = currentPage
        // navigating=true 表示这次 close 是菜单切页产生的，不应触发真正的结束逻辑。
        session.navigating = true
    }

    fun completeOpen(player: Player, currentPage: Int? = null) {
        sessions.computeIfAbsent(player.uniqueId) { WarehouseSession(player.uniqueId) }.apply {
            this.currentPage = currentPage
            this.navigating = false
        }
    }

    fun isNavigating(player: Player): Boolean {
        return sessions[player.uniqueId]?.navigating == true
    }

    fun hasSession(player: Player): Boolean {
        return sessions.containsKey(player.uniqueId)
    }

    fun end(player: Player) {
        sessions.remove(player.uniqueId)
    }
}
