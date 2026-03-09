package com.whitesoul.warehouse.listener

import com.whitesoul.warehouse.service.WarehouseRemarkInputService
import org.bukkit.event.player.AsyncPlayerChatEvent
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submit

object WarehouseRemarkInputListener {

    @SubscribeEvent
    fun onChat(event: AsyncPlayerChatEvent) {
        val player = event.player
        if (!WarehouseRemarkInputService.isPending(player)) {
            return
        }
        event.isCancelled = true
        val message = event.message
        submit {
            // 聊天事件是异步的，仓库与菜单操作必须切回主线程执行。
            if (player.isOnline) {
                WarehouseRemarkInputService.handle(player, message)
            }
        }
    }
}
