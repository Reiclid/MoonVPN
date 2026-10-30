package com.den.lumenvpn

import android.content.Context
import androidx.core.content.edit

object Store {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    fun config(c: Context): String = p(c).getString("config", "") ?: ""
    fun setConfig(c: Context, text: String) = p(c).edit { putString("config", text.trim() + "\n") }
    fun hasConfig(c: Context) = config(c).contains("[Peer]")

    fun autoStart(c: Context) = p(c).getBoolean("autostart", true)
    fun setAutoStart(c: Context, v: Boolean) = p(c).edit { putBoolean("autostart", v) }

    private val endpointRe = Regex("""(?im)^(\s*Endpoint\s*=\s*)(\[[^\]]+\]|[^:\s]+):(\d+)\s*$""")

    /** Адреса сервера з рядка Endpoint конфігу (host:port). */
    fun endpoint(c: Context): String =
        endpointRe.find(config(c))?.let { "${it.groupValues[2]}:${it.groupValues[3]}" } ?: ""

    /** Замінює host (і за потреби port) в Endpoint. Повертає false, якщо конфігу немає. */
    fun setEndpoint(c: Context, hostPort: String): Boolean {
        val text = config(c)
        val m = endpointRe.find(text) ?: return false
        val value = hostPort.trim()
        if (value.isEmpty()) return false
        val port = if (Regex(""":\d+$""").containsMatchIn(value) && !value.endsWith("]")) "" else ":${m.groupValues[3]}"
        setConfig(c, text.replaceRange(m.range, "${m.groupValues[1]}$value$port"))
        return true
    }
}

/** Розділення трафіку: застосунки й сайти. */
object SplitStore {
    private fun p(c: Context) = c.applicationContext.getSharedPreferences("lumen", Context.MODE_PRIVATE)

    /** "exclude" — застосунки зі списку йдуть повз VPN; "include" — лише вони через VPN. */
    fun appMode(c: Context) = p(c).getString("appMode", "exclude") ?: "exclude"
    fun setAppMode(c: Context, v: String) = p(c).edit { putString("appMode", v) }
    fun apps(c: Context): Set<String> = HashSet(p(c).getStringSet("apps", emptySet()) ?: emptySet())
    fun setApps(c: Context, s: Set<String>) = p(c).edit { putStringSet("apps", HashSet(s)) }

    /** "bypass" — сайти зі списку йдуть напряму; "only" — лише вони через VPN. */
    fun siteMode(c: Context) = p(c).getString("siteMode", "bypass") ?: "bypass"
    fun setSiteMode(c: Context, v: String) = p(c).edit { putString("siteMode", v) }
    fun sites(c: Context): Set<String> = HashSet(p(c).getStringSet("sites", emptySet()) ?: emptySet())
    fun setSites(c: Context, s: Set<String>) = p(c).edit { putStringSet("sites", HashSet(s)) }

    fun normalizeHost(raw: String): String =
        raw.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/').substringBefore(':').removePrefix("www.")
}
