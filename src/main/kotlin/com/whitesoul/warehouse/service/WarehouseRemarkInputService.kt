package com.whitesoul.warehouse.service

import com.whitesoul.warehouse.ui.WarehouseHomeMenu
import org.bukkit.entity.Player
import taboolib.common.platform.function.submit
import taboolib.platform.util.asLangText
import taboolib.platform.util.asLangTextList
import taboolib.platform.util.sendLang
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object WarehouseRemarkInputService {

    private val pendingInputs = ConcurrentHashMap<UUID, PendingRemarkInput>()

    fun begin(player: Player, page: Int) {
        val currentRemark = WarehouseService.getPageRemark(player.uniqueId, page)
        pendingInputs[player.uniqueId] = PendingRemarkInput(player.uniqueId, page)
        // 备注输入走聊天栏，先关闭菜单避免玩家误以为还在 GUI 内编辑。
        player.closeInventory()
        val currentDisplay = if (currentRemark.isBlank()) player.asLangText("remark-empty-display") else currentRemark
        player.asLangTextList("remark-input-begin", page, currentDisplay, 32).forEach(player::sendMessage)
    }

    fun handle(player: Player, message: String) {
        val pending = pendingInputs.remove(player.uniqueId) ?: return
        val input = message.trim()
        when {
            input.equals("cancel", ignoreCase = true) -> {
                player.sendLang("remark-input-cancelled")
            }
            input.equals("clear", ignoreCase = true) -> {
                clearRemark(player, pending)
            }
            else -> {
                val remark = WarehouseService.updatePageRemark(pending.owner, pending.page, input)
                if (remark.isBlank()) {
                    clearRemark(player, pending)
                } else {
                    WarehouseService.savePage(pending.owner, pending.page, force = true)
                    player.sendLang("remark-input-saved", pending.page, remark)
                }
            }
        }
        submit(delay = 1L) {
            // 下一 tick 再开菜单，避免与聊天事件结束时的容器状态冲突。
            if (player.isOnline) {
                WarehouseHomeMenu.openSelf(player)
            }
        }
    }

    fun isPending(player: Player): Boolean {
        return pendingInputs.containsKey(player.uniqueId)
    }

    fun clear(player: Player) {
        pendingInputs.remove(player.uniqueId)
    }

    private fun clearRemark(player: Player, pending: PendingRemarkInput) {
        WarehouseService.updatePageRemark(pending.owner, pending.page, "")
        WarehouseService.savePage(pending.owner, pending.page, force = true)
        player.sendLang("remark-input-cleared", pending.page)
    }

    private data class PendingRemarkInput(
        val owner: UUID,
        val page: Int,
    )
}
