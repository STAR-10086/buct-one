package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.CampusNetCheckoutRecord
import com.buct.one.data.model.CampusNetOnlineDevice
import com.buct.one.data.model.CampusNetPayRecord
import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.max

/**
 * 校园网网费、流量与账单本地快照存储。
 * 严格遵循数据层四条铁律（失败保留旧 data、NEVER != EMPTY_CONFIRMED、parserVersion 单调不减、capturedAt 单调不减）。
 */
class CampusNetStore(private val rootDir: File) {

    constructor(context: Context, accountKey: String = "default") : this(
        File(File(context.applicationContext.filesDir, "campus"), sanitizeKey(accountKey))
    )

    private val snapshotFile: File get() = File(rootDir, "campus_net.snapshot.v2.json")

    @Synchronized
    fun read(defaultParserVersion: Int): DomainSlice<CampusNetSnapshot> {
        if (!snapshotFile.exists()) {
            return DomainSlice.never(SOURCE_ID, defaultParserVersion)
        }
        return runCatching {
            val root = JSONObject(snapshotFile.readText(Charsets.UTF_8))
            val provObj = root.getJSONObject("provenance")
            val provenance = Provenance(
                source = provObj.optString("source", SOURCE_ID),
                capturedAt = provObj.optLong("capturedAt", 0L),
                parserVersion = provObj.optInt("parserVersion", defaultParserVersion),
                refreshState = runCatching {
                    RefreshState.valueOf(provObj.optString("refreshState", RefreshState.NEVER.name))
                }.getOrDefault(RefreshState.NEVER),
                complete = provObj.optBoolean("complete", true),
                incompleteReason = provObj.optString("incompleteReason", "").ifEmpty { null }
            )
            val dataObj = root.optJSONObject("data")
            val snapshot = dataObj?.let(::decodeSnapshot)
            val lastError = root.optString("lastError", "").ifEmpty { null }
            DomainSlice(snapshot, provenance, lastError)
        }.getOrElse {
            DomainSlice.never(SOURCE_ID, defaultParserVersion)
        }
    }

    @Synchronized
    fun commitSuccess(
        snapshot: CampusNetSnapshot,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<CampusNetSnapshot> {
        val prev = read(parserVersion)
        val monotonicTime = max(nowMillis, prev.provenance.capturedAt)
        val monotonicVersion = max(parserVersion, prev.provenance.parserVersion)
        val next = DomainSlice(
            data = snapshot,
            provenance = Provenance(
                source = SOURCE_ID,
                capturedAt = monotonicTime,
                parserVersion = monotonicVersion,
                refreshState = RefreshState.FRESH,
                complete = true
            ),
            lastError = null
        )
        writeSlice(next)
        return next
    }

    @Synchronized
    fun commitFailure(
        errorMessage: String,
        parserVersion: Int
    ): DomainSlice<CampusNetSnapshot> {
        val prev = read(parserVersion)
        val nextState = if (prev.data != null) RefreshState.FAILED_FALLBACK else prev.provenance.refreshState
        val next = DomainSlice(
            data = prev.data,
            provenance = prev.provenance.copy(
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage
        )
        writeSlice(next)
        return next
    }

    @Synchronized
    fun clear(parserVersion: Int): DomainSlice<CampusNetSnapshot> {
        runCatching { snapshotFile.delete() }
        return DomainSlice.never(SOURCE_ID, parserVersion)
    }

    private fun writeSlice(slice: DomainSlice<CampusNetSnapshot>) {
        val root = JSONObject().apply {
            put("provenance", JSONObject().apply {
                put("source", slice.provenance.source)
                put("capturedAt", slice.provenance.capturedAt)
                put("parserVersion", slice.provenance.parserVersion)
                put("refreshState", slice.provenance.refreshState.name)
                put("complete", slice.provenance.complete)
                slice.provenance.incompleteReason?.let { put("incompleteReason", it) }
            })
            slice.data?.let { put("data", encodeSnapshot(it)) }
            slice.lastError?.let { put("lastError", it) }
        }
        snapshotFile.parentFile?.let { if (!it.exists()) it.mkdirs() }
        val tmp = File(rootDir, "campus_net.snapshot.v2.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (snapshotFile.exists()) snapshotFile.delete()
        if (!tmp.renameTo(snapshotFile)) {
            snapshotFile.writeText(root.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun encodeSnapshot(snap: CampusNetSnapshot): JSONObject = JSONObject().apply {
        put("isOnline", snap.isOnline)
        put("username", snap.username)
        put("onlineIp", snap.onlineIp)
        put("accountStatus", snap.accountStatus)
        put("productsName", snap.productsName)
        put("billingName", snap.billingName)
        put("sumBytes", snap.sumBytes)
        put("allBytes", snap.allBytes)
        put("sumSeconds", snap.sumSeconds)
        put("trafficText", snap.trafficText)
        put("durationText", snap.durationText)
        put("userBalance", snap.userBalance)
        put("walletBalance", snap.walletBalance)
        put("userCharge", snap.userCharge)
        put("onlineDeviceTotal", snap.onlineDeviceTotal)
        put("keepaliveTimeSec", snap.keepaliveTimeSec)
        put("onlineDevices", JSONArray().apply {
            snap.onlineDevices.forEach { dev ->
                put(JSONObject().apply {
                    put("ip", dev.ip)
                    put("loginTime", dev.loginTime)
                    put("billingName", dev.billingName)
                })
            }
        })
        put("payRecords", JSONArray().apply {
            snap.payRecords.forEach { rec ->
                put(JSONObject().apply {
                    put("amountText", rec.amountText)
                    put("payType", rec.payType)
                    put("payTime", rec.payTime)
                })
            }
        })
        put("checkoutRecords", JSONArray().apply {
            snap.checkoutRecords.forEach { rec ->
                put(JSONObject().apply {
                    put("feeText", rec.feeText)
                    put("productName", rec.productName)
                    put("trafficText", rec.trafficText)
                    put("durationText", rec.durationText)
                    put("checkoutTime", rec.checkoutTime)
                })
            }
        })
    }

    private fun decodeSnapshot(obj: JSONObject): CampusNetSnapshot {
        val devicesArr = obj.optJSONArray("onlineDevices") ?: JSONArray()
        val devices = ArrayList<CampusNetOnlineDevice>(devicesArr.length())
        for (i in 0 until devicesArr.length()) {
            val d = devicesArr.optJSONObject(i) ?: continue
            devices.add(
                CampusNetOnlineDevice(
                    ip = d.optString("ip", ""),
                    loginTime = d.optString("loginTime", ""),
                    billingName = d.optString("billingName", "")
                )
            )
        }
        val payArr = obj.optJSONArray("payRecords") ?: JSONArray()
        val payRecords = ArrayList<CampusNetPayRecord>(payArr.length())
        for (i in 0 until payArr.length()) {
            val p = payArr.optJSONObject(i) ?: continue
            payRecords.add(
                CampusNetPayRecord(
                    amountText = p.optString("amountText", ""),
                    payType = p.optString("payType", ""),
                    payTime = p.optString("payTime", "")
                )
            )
        }
        val checkoutArr = obj.optJSONArray("checkoutRecords") ?: JSONArray()
        val checkoutRecords = ArrayList<CampusNetCheckoutRecord>(checkoutArr.length())
        for (i in 0 until checkoutArr.length()) {
            val c = checkoutArr.optJSONObject(i) ?: continue
            checkoutRecords.add(
                CampusNetCheckoutRecord(
                    feeText = c.optString("feeText", ""),
                    productName = c.optString("productName", ""),
                    trafficText = c.optString("trafficText", ""),
                    durationText = c.optString("durationText", ""),
                    checkoutTime = c.optString("checkoutTime", "")
                )
            )
        }
        return CampusNetSnapshot(
            isOnline = obj.optBoolean("isOnline", false),
            username = obj.optString("username", ""),
            onlineIp = obj.optString("onlineIp", ""),
            accountStatus = obj.optString("accountStatus", ""),
            productsName = obj.optString("productsName", ""),
            billingName = obj.optString("billingName", ""),
            sumBytes = obj.optLong("sumBytes", 0L),
            allBytes = obj.optLong("allBytes", 0L),
            sumSeconds = obj.optLong("sumSeconds", 0L),
            trafficText = obj.optString("trafficText", ""),
            durationText = obj.optString("durationText", ""),
            userBalance = obj.optDouble("userBalance", 0.0).takeIf { it.isFinite() } ?: 0.0,
            walletBalance = obj.optDouble("walletBalance", 0.0).takeIf { it.isFinite() } ?: 0.0,
            userCharge = obj.optDouble("userCharge", 0.0).takeIf { it.isFinite() } ?: 0.0,
            onlineDeviceTotal = obj.optInt("onlineDeviceTotal", 0),
            keepaliveTimeSec = obj.optLong("keepaliveTimeSec", 0L),
            onlineDevices = devices,
            payRecords = payRecords,
            checkoutRecords = checkoutRecords
        )
    }

    companion object {
        private const val SOURCE_ID = "srun_campus_net"

        private fun sanitizeKey(raw: String): String {
            val cleaned = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
            return cleaned.ifBlank { "default" }
        }
    }
}
