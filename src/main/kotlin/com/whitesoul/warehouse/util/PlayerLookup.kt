package com.whitesoul.warehouse.util

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer

object PlayerLookup {

    fun findOfflinePlayer(name: String): OfflinePlayer? {
        Bukkit.getPlayerExact(name)?.let { return it }
        // 使用 getOfflinePlayer(name) 代替遍历全量离线玩家列表，避免大服务器上的性能问题。
        // 注意：该方法在玩家从未加入过时仍会返回非 null 对象，需通过 hasPlayedBefore 过滤。
        @Suppress("DEPRECATION")
        val offline = Bukkit.getOfflinePlayer(name)
        return if (offline.hasPlayedBefore()) offline else null
    }

    fun suggestNames(): List<String> {
        // 仅补全在线玩家名，避免遍历全量离线玩家列表导致 Tab 补全卡顿。
        return Bukkit.getOnlinePlayers().map { it.name }.sorted()
    }
}
