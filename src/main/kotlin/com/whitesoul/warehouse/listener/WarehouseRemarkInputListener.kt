package com.whitesoul.warehouse.listener

import com.whitesoul.warehouse.service.WarehouseRemarkInputService
import io.papermc.paper.event.player.AsyncChatEvent
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import taboolib.common.platform.event.SubscribeEvent
import taboolib.common.platform.function.submit

object WarehouseRemarkInputListener {

    @SubscribeEvent
    fun onChat(event: AsyncChatEvent) {
        val player = event.player
        if (!WarehouseRemarkInputService.isPending(player)) {
            return
        }
        event.isCancelled = true
        val message = PlainTextComponentSerializer.plainText().serialize(event.message())
        submit {
            // AsyncChatEvent 可能由异步线程触发，仓库与菜单操作统一切回主线程执行。
            if (player.isOnline) {
                WarehouseRemarkInputService.handle(player, message)
            }
        }
    }
}
