package com.whitesoul.warehouse.util

import org.bukkit.Bukkit
import org.bukkit.OfflinePlayer

object PlayerLookup {

    fun findOfflinePlayer(name: String): OfflinePlayer? {
        Bukkit.getPlayerExact(name)?.let { return it }
        // 离线玩家匹配降级为忽略大小写，兼容管理员手输玩家名时的常见大小写误差。
        return Bukkit.getOfflinePlayers().firstOrNull { it.name?.equals(name, ignoreCase = true) == true }
    }

    fun suggestNames(): List<String> {
        return buildSet {
            Bukkit.getOnlinePlayers().mapTo(this) { it.name }
            Bukkit.getOfflinePlayers().mapNotNullTo(this) { it.name }
        }.sorted()
    }
}
