package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.ElectricityReading
import com.buct.one.data.model.ElectricityRoomTarget
import com.buct.one.data.model.ElectricitySnapshot
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID
import kotlin.math.max

/**
 * 宿舍电费本地快照与同意授权状态存储。
 * 严格遵循数据层四条铁律：
 * 1. 失败永不产生新 data
 * 2. NEVER != EMPTY_CONFIRMED
 * 3. parserVersion 只增不减
 * 4. capturedAt 单调不减
 */
class ElectricityStore(private val rootDir: File, context: Context? = null) {

    constructor(context: Context, accountKey: String = "default") : this(
        File(File(context.applicationContext.filesDir, "campus"), sanitizeKey(accountKey)),
        context.applicationContext
    )

    private val appContext: Context? = context?.applicationContext
    private val snapshotFile: File get() = File(rootDir, "electricity.snapshot.v1.json")

    @Synchronized
    fun read(defaultParserVersion: Int): DomainSlice<ElectricitySnapshot> {
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
    fun updateTargets(
        targets: List<ElectricityRoomTarget>,
        parserVersion: Int,
        threshold: Double = getLowBalanceThreshold()
    ): DomainSlice<ElectricitySnapshot> {
        val prev = read(parserVersion)
        if (targets.isEmpty()) {
            runCatching { snapshotFile.delete() }
            return DomainSlice.never(SOURCE_ID, max(parserVersion, prev.provenance.parserVersion))
        }
        val targetKeys = targets.mapTo(HashSet()) { "${it.schoolid}\u0000${it.roomid}" }
        val keptReadings = prev.data?.readings.orEmpty()
            .filter { "${it.target.schoolid}\u0000${it.target.roomid}" in targetKeys }
        val nextState = if (keptReadings.isNotEmpty()) prev.provenance.refreshState else RefreshState.NEVER
        val next = DomainSlice(
            data = ElectricitySnapshot(
                targets = targets,
                readings = keptReadings,
                lowBalanceThreshold = threshold
            ),
            provenance = prev.provenance.copy(
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = null
        )
        writeSlice(next)
        return next
    }

    @Synchronized
    fun updateThreshold(threshold: Double, parserVersion: Int): DomainSlice<ElectricitySnapshot> {
        setLowBalanceThreshold(threshold)
        val prev = read(parserVersion)
        val data = prev.data ?: return prev
        val next = prev.copy(data = data.copy(lowBalanceThreshold = threshold))
        writeSlice(next)
        return next
    }

    @Synchronized
    fun commitSuccess(
        targets: List<ElectricityRoomTarget>,
        readings: List<ElectricityReading>,
        parserVersion: Int,
        nowMillis: Long = System.currentTimeMillis()
    ): DomainSlice<ElectricitySnapshot> {
        val prev = read(parserVersion)
        val monotonicTime = max(nowMillis, prev.provenance.capturedAt)
        val monotonicVersion = max(parserVersion, prev.provenance.parserVersion)
        val threshold = getLowBalanceThreshold()
        val state = if (readings.isEmpty()) RefreshState.EMPTY_CONFIRMED else RefreshState.FRESH
        val next = DomainSlice(
            data = ElectricitySnapshot(
                targets = targets,
                readings = readings,
                lowBalanceThreshold = threshold
            ),
            provenance = Provenance(
                source = SOURCE_ID,
                capturedAt = monotonicTime,
                parserVersion = monotonicVersion,
                refreshState = state,
                complete = true,
                incompleteReason = null
            ),
            lastError = null
        )
        writeSlice(next)
        return next
    }

    @Synchronized
    fun commitFailure(errorMessage: String, parserVersion: Int): DomainSlice<ElectricitySnapshot> {
        val prev = read(parserVersion)
        val hasReadings = !prev.data?.readings.isNullOrEmpty()
        val nextState = if (hasReadings) RefreshState.FAILED_FALLBACK else prev.provenance.refreshState
        val next = DomainSlice(
            data = prev.data,
            provenance = prev.provenance.copy(
                parserVersion = max(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage.ifBlank { "查询宿舍电费失败" }
        )
        if (prev.data != null) {
            writeSlice(next)
        }
        return next
    }

    @Synchronized
    fun clearAll(parserVersion: Int): DomainSlice<ElectricitySnapshot> {
        runCatching { snapshotFile.delete() }
        setConsentAccepted(false)
        return DomainSlice.never(SOURCE_ID, parserVersion)
    }

    fun isConsentAccepted(): Boolean {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return false
        return prefs.getBoolean(KEY_CONSENT_ACCEPTED, false)
    }

    fun setConsentAccepted(accepted: Boolean) {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit().putBoolean(KEY_CONSENT_ACCEPTED, accepted).apply()
    }

    fun getLowBalanceThreshold(): Double {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return 20.0
        val raw = prefs.getFloat(KEY_LOW_THRESHOLD, 20.0f).toDouble()
        return if (raw.isFinite() && raw >= 0.0) raw else 20.0
    }

    fun setLowBalanceThreshold(threshold: Double) {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        val safe = threshold.coerceIn(0.0, 500.0).toFloat()
        prefs.edit().putFloat(KEY_LOW_THRESHOLD, safe).apply()
    }

    @Synchronized
    fun getOrCreateClientId(): String {
        val ctx = appContext ?: return UUID.randomUUID().toString()
        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val noBackupFile = runCatching { File(ctx.noBackupFilesDir, "electricity_client_id") }.getOrNull()
        val fromFile = runCatching {
            noBackupFile?.takeIf { it.isFile }?.readText(Charsets.UTF_8)?.trim()
        }.getOrNull()?.takeIf(::isValidUuid)
        if (fromFile != null) return fromFile

        val fromPrefs = prefs.getString(KEY_CLIENT_ID, null)?.trim()?.takeIf(::isValidUuid)
        val resolved = fromPrefs ?: UUID.randomUUID().toString()
        runCatching { noBackupFile?.writeText(resolved, Charsets.UTF_8) }
        if (fromPrefs == null) {
            prefs.edit().putString(KEY_CLIENT_ID, resolved).apply()
        }
        return resolved
    }

    private fun isValidUuid(value: String): Boolean =
        value.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))

    private fun writeSlice(slice: DomainSlice<ElectricitySnapshot>) {
        if (!rootDir.exists()) rootDir.mkdirs()
        val root = JSONObject().apply {
            put("provenance", JSONObject().apply {
                put("source", slice.provenance.source)
                put("capturedAt", slice.provenance.capturedAt)
                put("parserVersion", slice.provenance.parserVersion)
                put("refreshState", slice.provenance.refreshState.name)
                put("complete", slice.provenance.complete)
                slice.provenance.incompleteReason?.let { put("incompleteReason", it) }
            })
            slice.lastError?.let { put("lastError", it) }
            slice.data?.let { put("data", encodeSnapshot(it)) }
        }
        val tmp = File(rootDir, "${snapshotFile.name}.${UUID.randomUUID()}.tmp")
        tmp.writeText(root.toString(), Charsets.UTF_8)
        if (!tmp.renameTo(snapshotFile)) {
            snapshotFile.writeText(root.toString(), Charsets.UTF_8)
            tmp.delete()
        }
    }

    private fun encodeSnapshot(snap: ElectricitySnapshot): JSONObject = JSONObject().apply {
        put("lowBalanceThreshold", snap.lowBalanceThreshold)
        put("targets", JSONArray().apply {
            snap.targets.forEach { put(encodeTarget(it)) }
        })
        put("readings", JSONArray().apply {
            snap.readings.forEach { put(encodeReading(it)) }
        })
    }

    private fun decodeSnapshot(obj: JSONObject): ElectricitySnapshot {
        val threshold = obj.optDouble("lowBalanceThreshold", 20.0).takeIf { it.isFinite() && it >= 0.0 } ?: 20.0
        val targetsArr = obj.optJSONArray("targets") ?: JSONArray()
        val targets = ArrayList<ElectricityRoomTarget>(targetsArr.length())
        for (i in 0 until targetsArr.length()) {
            targetsArr.optJSONObject(i)?.let { targets.add(decodeTarget(it)) }
        }
        val readingsArr = obj.optJSONArray("readings") ?: JSONArray()
        val readings = ArrayList<ElectricityReading>(readingsArr.length())
        for (i in 0 until readingsArr.length()) {
            readingsArr.optJSONObject(i)?.let { readings.add(decodeReading(it)) }
        }
        return ElectricitySnapshot(
            targets = targets,
            readings = readings,
            lowBalanceThreshold = threshold
        )
    }

    private fun encodeTarget(t: ElectricityRoomTarget): JSONObject = JSONObject().apply {
        put("schoolid", t.schoolid)
        put("campusName", t.campusName)
        put("buildingid", t.buildingid)
        put("buildingName", t.buildingName)
        put("floorid", t.floorid)
        put("floorName", t.floorName)
        put("roomid", t.roomid)
        put("roomName", t.roomName)
        put("meterType", t.meterType)
    }

    private fun decodeTarget(obj: JSONObject): ElectricityRoomTarget = ElectricityRoomTarget(
        schoolid = obj.optString("schoolid", ""),
        campusName = obj.optString("campusName", ""),
        buildingid = obj.optString("buildingid", ""),
        buildingName = obj.optString("buildingName", ""),
        floorid = obj.optString("floorid", ""),
        floorName = obj.optString("floorName", ""),
        roomid = obj.optString("roomid", ""),
        roomName = obj.optString("roomName", ""),
        meterType = obj.optString("meterType", "")
    )

    private fun encodeReading(r: ElectricityReading): JSONObject = JSONObject().apply {
        put("target", encodeTarget(r.target))
        put("balanceText", r.balanceText)
        if (r.balanceValue != null) {
            put("balanceValue", r.balanceValue)
        }
        put("unit", r.unit)
        put("accountStatus", r.accountStatus)
        put("queriedAtEpochSec", r.queriedAtEpochSec)
        put("fromCache", r.fromCache)
    }

    private fun decodeReading(obj: JSONObject): ElectricityReading {
        val target = decodeTarget(obj.getJSONObject("target"))
        val value = if (obj.has("balanceValue") && !obj.isNull("balanceValue")) {
            obj.optDouble("balanceValue").takeIf { it.isFinite() }
        } else {
            null
        }
        return ElectricityReading(
            target = target,
            balanceText = obj.optString("balanceText", ""),
            balanceValue = value,
            unit = obj.optString("unit", ""),
            accountStatus = obj.optString("accountStatus", ""),
            queriedAtEpochSec = obj.optLong("queriedAtEpochSec", 0L),
            fromCache = obj.optBoolean("fromCache", false)
        )
    }

    companion object {
        private const val SOURCE_ID = "ele.buct.one"
        private const val PREFS_NAME = "buct_electricity_prefs"
        private const val KEY_CONSENT_ACCEPTED = "privacy_consent_accepted"
        private const val KEY_CLIENT_ID = "client_id"
        private const val KEY_LOW_THRESHOLD = "low_balance_threshold_v1"

        private fun sanitizeKey(raw: String): String =
            raw.ifBlank { "default" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
