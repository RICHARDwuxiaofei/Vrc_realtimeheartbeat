package best.nagikokoro.watch6heartrateprobe.mobile

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

data class PairingTarget(val host: String, val port: Int)

object PairingUri {
    fun parse(value: String): PairingTarget {
        val uri = runCatching { URI(value.trim()) }
            .getOrElse { throw IllegalArgumentException("配对码格式错误") }
        require(uri.scheme == "vrc-heartbeat" && uri.host == "pair") {
            "不是 VRChat 心率桥配对码"
        }
        val query = uri.rawQuery.orEmpty()
            .split("&")
            .filter { it.contains("=") }
            .associate {
                val (key, rawValue) = it.split("=", limit = 2)
                key to URLDecoder.decode(rawValue, StandardCharsets.UTF_8.name())
            }
        val host = query["host"].orEmpty()
        val port = query["port"]?.toIntOrNull()
            ?: throw IllegalArgumentException("配对码端口无效")
        require(isIpv4(host)) { "配对码电脑地址无效" }
        require(port in 1..65_535) { "配对码端口无效" }
        return PairingTarget(host, port)
    }

    private fun isIpv4(value: String): Boolean {
        val parts = value.split(".")
        return parts.size == 4 && parts.all { part ->
            part.isNotEmpty() && part.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}
