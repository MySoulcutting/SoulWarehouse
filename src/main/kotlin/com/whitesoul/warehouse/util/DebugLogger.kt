package com.whitesoul.warehouse.util

import com.whitesoul.warehouse.config.PluginConfig
import taboolib.common.platform.function.info

object DebugLogger {

    inline fun log(message: () -> String) {
        if (PluginConfig.debug) {
            info("[SW-Debug] ${message()}")
        }
    }

    inline fun <T> timed(label: String, block: () -> T): T {
        if (!PluginConfig.debug) {
            return block()
        }
        val start = System.nanoTime()
        val result = block()
        val elapsed = (System.nanoTime() - start) / 1_000_000.0
        info("[SW-Debug] $label (${String.format("%.2f", elapsed)}ms)")
        return result
    }
}
