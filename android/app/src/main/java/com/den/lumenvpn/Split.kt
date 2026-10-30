package com.den.lumenvpn

import android.content.Context
import java.math.BigInteger
import java.net.InetAddress

/**
 * Застосовує налаштування розділення трафіку до тексту конфігу перед підключенням:
 *  - застосунки: Excluded/IncludedApplications в [Interface];
 *  - сайти: домени резолвляться в IP (поки VPN ще не піднято), і ці IP
 *    віднімаються від AllowedIPs (bypass) або стають єдиними AllowedIPs (only).
 */
object Split {
    private data class Net(val a: BigInteger, val p: Int, val bits: Int) {
        override fun toString(): String {
            val len = bits / 8
            val raw = a.toByteArray().let { b -> ByteArray(len).also { out ->
                val src = if (b.size > len) b.copyOfRange(b.size - len, b.size) else b
                System.arraycopy(src, 0, out, len - src.size, src.size)
            } }
            return "${InetAddress.getByAddress(raw).hostAddress}/$p"
        }
    }

    private fun contains(big: Net, small: Net) =
        big.p <= small.p && small.a.shiftRight(small.bits - big.p) == big.a.shiftRight(big.bits - big.p)

    private fun minus(nets: List<Net>, x: Net): List<Net> = nets.flatMap { n ->
        when {
            n.bits != x.bits -> listOf(n)
            contains(x, n) -> emptyList()
            contains(n, x) -> minus(
                listOf(Net(n.a, n.p + 1, n.bits), Net(n.a.setBit(n.bits - n.p - 1), n.p + 1, n.bits)), x,
            )
            else -> listOf(n)
        }
    }

    private fun net(ip: InetAddress): Net {
        val bits = ip.address.size * 8
        return Net(BigInteger(1, ip.address), bits, bits)
    }

    private fun resolve(host: String): List<Net> =
        listOf(host, "www.$host").flatMap { h ->
            runCatching { InetAddress.getAllByName(h).map(::net) }.getOrDefault(emptyList())
        }.distinct()

    private val full = listOf(Net(BigInteger.ZERO, 0, 32), Net(BigInteger.ZERO, 0, 128))
    private val internal = listOf(
        Net(BigInteger(1, byteArrayOf(10, 8, 1, 0)), 24, 32),
        Net(BigInteger(1, byteArrayOf(10, 13, 13, 0)), 24, 32),
    )

    /** Блокує мережу: викликати з фонового потоку. */
    fun apply(c: Context, text: String): String {
        var out = text.lines()
            .filterNot { Regex("""(?i)^\s*(Excluded|Included)Applications\s*=""").containsMatchIn(it) }
            .joinToString("\n")

        val apps = SplitStore.apps(c)
        if (apps.isNotEmpty()) {
            val include = SplitStore.appMode(c) == "include"
            val key = if (include) "IncludedApplications" else "ExcludedApplications"
            out = out.replaceFirst(Regex("(?im)^\\[Interface\\]\\s*$"), "[Interface]\n$key = ${(if (include) apps + c.packageName else apps).joinToString(",")}")
        }

        val sites = SplitStore.sites(c)
        if (sites.isNotEmpty()) {
            val ips = sites.flatMap(::resolve).distinct()
            val allowed = if (SplitStore.siteMode(c) == "only") {
                ips + internal
            } else {
                ips.fold(full) { acc, ip -> minus(acc, ip) }
            }
            out = out.replace(Regex("(?im)^(\\s*AllowedIPs\\s*=).*$")) { "${it.groupValues[1]} ${allowed.joinToString(", ")}" }
        }
        return out
    }
}
