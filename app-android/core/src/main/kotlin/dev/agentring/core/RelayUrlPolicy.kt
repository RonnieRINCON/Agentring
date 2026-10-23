package dev.agentring.core

import java.net.URI

/**
 * TLS is mandatory, except for plain-HTTP relays on a private network or the
 * emulator host (self-hosting on a LAN, local development, CI).
 */
object RelayUrlPolicy {
    fun check(url: String) {
        val uri = try {
            URI(url)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid relay URL")
        }
        val host = uri.host ?: throw IllegalArgumentException("Invalid relay URL")
        when (uri.scheme) {
            "https" -> Unit
            "http" -> require(isPrivateHost(host)) { "The relay must use https:// (http is only allowed on a local network)" }
            else -> throw IllegalArgumentException("The relay URL must start with https://")
        }
    }

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().trim('[', ']')
        if (h == "localhost" || h.endsWith(".local") || h == "::1") return true
        val parts = h.split(".").map { it.toIntOrNull() ?: return false }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        val (a, b) = parts
        return a == 10 || a == 127 || (a == 192 && b == 168) || (a == 172 && b in 16..31) || (a == 100 && b in 64..127)
    }
}
