package com.buct.course.login

import okhttp3.Dns
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 北化域名智能混合 DNS 解析器：
 * - 第二课堂域名（scs.buct.edu.cn / eapi.buct.edu.cn）仅支持校内/VPN 访问，优先直连校内 DNS（202.4.130.100:53），
 *   超时后回退系统 DNS 与内置保底 IP，避免手机私有 DNS / 代理 DNS 导致校内域解析异常；
 * - 其余 *.buct.edu.cn 业务域名优先走系统 DNS（保障校外访问零额外延迟），
 *   仅当系统 DNS 解析失败时再依次尝试校内 DNS 202.4.130.100 与内置保底 IP；
 * - 非北化域名直接走系统 DNS。
 */
object BuctDns : Dns {

    private const val CAMPUS_DNS_IP = "202.4.130.100"
    private const val CAMPUS_DNS_PORT = 53
    private const val CAMPUS_DNS_TIMEOUT_MS = 900
    private const val MIN_TTL_MS = 30_000L
    private const val MAX_TTL_MS = 300_000L

    private val campusFirstHosts = setOf(
        "scs.buct.edu.cn",
        "eapi.buct.edu.cn"
    )

    private val buctFallbackIps = mapOf(
        "w.buct.edu.cn" to listOf("121.195.132.7"),
        "scs.buct.edu.cn" to listOf("121.195.148.33"),
        "eapi.buct.edu.cn" to listOf("121.195.148.33"),
        "jwglxt.buct.edu.cn" to listOf("121.195.154.238"),
        "sidportal.buct.edu.cn" to listOf("222.199.255.33", "222.199.255.34"),
        "course.buct.edu.cn" to listOf("121.195.154.229"),
        "sjcx.buct.edu.cn" to listOf("222.199.255.27"),
        "ecard.buct.edu.cn" to listOf("222.199.255.27"),
        "tygl.buct.edu.cn" to listOf("222.199.255.27"),
        "gmis.buct.edu.cn" to listOf("121.195.148.197")
    )

    private data class CachedEntry(
        val addresses: List<InetAddress>,
        val expiresAtMs: Long
    )

    private val campusDnsCache = ConcurrentHashMap<String, CachedEntry>()
    private val txIdCounter = AtomicInteger(100)

    override fun lookup(hostname: String): List<InetAddress> {
        val host = hostname.trim().lowercase()
        if (host.isEmpty()) throw UnknownHostException(hostname)

        val isBuctHost = host == "buct.edu.cn" || host.endsWith(".buct.edu.cn")
        if (!isBuctHost) {
            return Dns.SYSTEM.lookup(hostname)
        }

        if (host in campusFirstHosts) {
            // 仅校内/VPN 可达的第二课堂域：优先查校内 DNS，失败再走系统 DNS 与内置保底 IP
            queryCampusDnsCached(host)?.let { return it }
            resolveSystemSafe(hostname)?.let { return it }
            resolveStaticFallback(hostname, host)?.let { return it }
            throw UnknownHostException("Unable to resolve host \"$hostname\" (scs campus DNS/system failed)")
        }

        // 校外也可访问的北化业务域：优先系统 DNS（校外无延迟），失败时回退校内 DNS 与内置 IP
        resolveSystemSafe(hostname)?.let { return it }
        queryCampusDnsCached(host)?.let { return it }
        resolveStaticFallback(hostname, host)?.let { return it }
        throw UnknownHostException(hostname)
    }

    private fun resolveSystemSafe(hostname: String): List<InetAddress>? =
        try {
            Dns.SYSTEM.lookup(hostname).takeIf { it.isNotEmpty() }
        } catch (_: Throwable) {
            null
        }

    private fun resolveStaticFallback(rawHost: String, normalizedHost: String): List<InetAddress>? {
        val ips = buctFallbackIps[normalizedHost] ?: return null
        return runCatching {
            ips.map { ipStr ->
                val raw = InetAddress.getByName(ipStr).address
                InetAddress.getByAddress(rawHost, raw)
            }.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }

    private fun queryCampusDnsCached(host: String): List<InetAddress>? {
        val now = System.currentTimeMillis()
        campusDnsCache[host]?.let { cached ->
            if (now < cached.expiresAtMs && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }
        }
        val queried = queryCampusDnsUdp(host) ?: return null
        campusDnsCache[host] = CachedEntry(
            addresses = queried.first,
            expiresAtMs = now + queried.second
        )
        return queried.first
    }

    /**
     * 通过 UDP 直连 202.4.130.100:53 执行轻量 RFC-1035 A 记录查询。
     * 返回 Pair(地址列表, 缓存TTL毫秒)，失败或超时返回 null。
     */
    private fun queryCampusDnsUdp(host: String): Pair<List<InetAddress>, Long>? {
        val txId = txIdCounter.incrementAndGet() and 0xFFFF
        val queryBytes = buildARecordQuery(host, txId) ?: return null

        return try {
            val serverAddr = InetAddress.getByAddress(byteArrayOf(202.toByte(), 4, 130.toByte(), 100))
            DatagramSocket().use { socket ->
                socket.soTimeout = CAMPUS_DNS_TIMEOUT_MS
                socket.send(DatagramPacket(queryBytes, queryBytes.size, serverAddr, CAMPUS_DNS_PORT))
                val recvBuf = ByteArray(1024)
                val recvPacket = DatagramPacket(recvBuf, recvBuf.size)
                socket.receive(recvPacket)
                parseARecordResponse(host, recvBuf, recvPacket.length, txId)
            }
        } catch (_: Throwable) {
            null
        }
    }

    private fun buildARecordQuery(host: String, txId: Int): ByteArray? {
        val labels = host.split('.')
        if (labels.isEmpty()) return null
        val out = ByteArrayOutputStream(64)
        // Header (12 bytes)
        out.write((txId ushr 8) and 0xFF)
        out.write(txId and 0xFF)
        out.write(0x01); out.write(0x00) // Flags: Standard query, RD=1
        out.write(0x00); out.write(0x01) // QDCOUNT = 1
        out.write(0x00); out.write(0x00) // ANCOUNT = 0
        out.write(0x00); out.write(0x00) // NSCOUNT = 0
        out.write(0x00); out.write(0x00) // ARCOUNT = 0
        for (label in labels) {
            val bytes = label.toByteArray(Charsets.US_ASCII)
            if (bytes.isEmpty() || bytes.size > 63) return null
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0x00)
        // QTYPE = A (1), QCLASS = IN (1)
        out.write(0x00); out.write(0x01)
        out.write(0x00); out.write(0x01)
        return out.toByteArray()
    }

    private fun parseARecordResponse(
        host: String,
        buf: ByteArray,
        len: Int,
        expectedTxId: Int
    ): Pair<List<InetAddress>, Long>? {
        if (len < 12) return null
        val txId = ((buf[0].toInt() and 0xFF) shl 8) or (buf[1].toInt() and 0xFF)
        if (txId != expectedTxId) return null
        val flags = ((buf[2].toInt() and 0xFF) shl 8) or (buf[3].toInt() and 0xFF)
        val isResponse = (flags and 0x8000) != 0
        val rcode = flags and 0x000F
        if (!isResponse || rcode != 0) return null

        val qdCount = readU16(buf, 4)
        val anCount = readU16(buf, 6)
        if (anCount <= 0) return null

        var offset = 12
        repeat(qdCount) {
            offset = skipDnsName(buf, len, offset)
            if (offset < 0 || offset + 4 > len) return null
            offset += 4
        }

        val results = ArrayList<InetAddress>(anCount)
        var minTtlSeconds = Long.MAX_VALUE
        repeat(anCount) {
            offset = skipDnsName(buf, len, offset)
            if (offset < 0 || offset + 10 > len) return@repeat
            val rType = readU16(buf, offset)
            val rClass = readU16(buf, offset + 2)
            val ttl = readU32(buf, offset + 4)
            val rdLength = readU16(buf, offset + 8)
            offset += 10
            if (offset + rdLength > len) return@repeat
            if (rType == 1 && rClass == 1 && rdLength == 4) {
                val ipv4 = buf.copyOfRange(offset, offset + 4)
                results.add(InetAddress.getByAddress(host, ipv4))
                if (ttl > 0) minTtlSeconds = minOf(minTtlSeconds, ttl)
            }
            offset += rdLength
        }

        if (results.isEmpty()) return null
        val ttlMs = if (minTtlSeconds == Long.MAX_VALUE) {
            MAX_TTL_MS
        } else {
            (minTtlSeconds * 1000L).coerceIn(MIN_TTL_MS, MAX_TTL_MS)
        }
        return results to ttlMs
    }

    private fun skipDnsName(buf: ByteArray, len: Int, start: Int): Int {
        var pos = start
        var steps = 0
        while (pos < len && steps < 64) {
            val b = buf[pos].toInt() and 0xFF
            when {
                b == 0 -> return pos + 1
                (b and 0xC0) == 0xC0 -> return if (pos + 2 <= len) pos + 2 else -1
                else -> {
                    pos += 1 + b
                    steps++
                }
            }
        }
        return -1
    }

    private fun readU16(buf: ByteArray, offset: Int): Int =
        ((buf[offset].toInt() and 0xFF) shl 8) or (buf[offset + 1].toInt() and 0xFF)

    private fun readU32(buf: ByteArray, offset: Int): Long =
        ((buf[offset].toLong() and 0xFF) shl 24) or
            ((buf[offset + 1].toLong() and 0xFF) shl 16) or
            ((buf[offset + 2].toLong() and 0xFF) shl 8) or
            (buf[offset + 3].toLong() and 0xFF)
}
