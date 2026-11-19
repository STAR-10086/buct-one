package com.buct.one.data.store

import android.content.Context
import com.buct.one.data.model.CardAccountInfo
import com.buct.one.data.model.CardSnapshot
import com.buct.one.data.model.CardTransaction
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.Provenance
import com.buct.one.data.model.RefreshState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * 校园卡余额与消费流水的原子持久化存储。
 * 强制遵守数据层四条铁律：
 * 1. 失败永不产生新 data（失败路径保留旧快照）
 * 2. NEVER != EMPTY_CONFIRMED
 * 3. parserVersion 只增不减
 * 4. capturedAt 单调不减（时钟回拨保护）
 */
class CardStore(private val rootDir: File) {

    constructor(context: Context, accountKey: String = "default") : this(
        File(File(context.filesDir, "campus"), sanitizeAccountKey(accountKey))
    )

    private val snapshotFile: File get() = File(rootDir, "ecard.snapshot.v1.json")

    @Synchronized
    fun read(defaultParserVersion: Int): DomainSlice<CardSnapshot> {
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
        snapshot: CardSnapshot,
        parserVersion: Int,
        nowMillis: Long,
        complete: Boolean = true,
        incompleteReason: String? = null
    ): DomainSlice<CardSnapshot> {
        val prev = read(parserVersion)
        val monotonicCapturedAt = maxOf(nowMillis, prev.provenance.capturedAt)
        val monotonicParserVersion = maxOf(parserVersion, prev.provenance.parserVersion)
        val state = if (snapshot.transactions.isEmpty()) RefreshState.EMPTY_CONFIRMED else RefreshState.FRESH

        val slice = DomainSlice(
            data = snapshot,
            provenance = Provenance(
                source = SOURCE_ID,
                capturedAt = monotonicCapturedAt,
                parserVersion = monotonicParserVersion,
                refreshState = state,
                complete = complete,
                incompleteReason = incompleteReason
            ),
            lastError = null
        )
        writeSlice(slice)
        return slice
    }

    @Synchronized
    fun commitFailure(errorMessage: String, parserVersion: Int): DomainSlice<CardSnapshot> {
        val prev = read(parserVersion)
        val hasPreviousData = prev.data != null
        val nextState = if (hasPreviousData) RefreshState.FAILED_FALLBACK else prev.provenance.refreshState
        val slice = DomainSlice(
            data = prev.data,
            provenance = prev.provenance.copy(
                parserVersion = maxOf(parserVersion, prev.provenance.parserVersion),
                refreshState = nextState
            ),
            lastError = errorMessage
        )
        if (hasPreviousData) {
            writeSlice(slice)
        }
        return slice
    }

    @Synchronized
    fun clear() {
        runCatching { snapshotFile.delete() }
    }

    private fun writeSlice(slice: DomainSlice<CardSnapshot>) {
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
        atomicWrite(snapshotFile, root.toString())
    }

    private fun encodeSnapshot(s: CardSnapshot): JSONObject = JSONObject().apply {
        put("account", JSONObject().apply {
            put("holderName", s.account.holderName)
            put("maskedId", s.account.maskedId)
            put("balanceFen", s.account.balanceFen)
            put("statusCode", s.account.statusCode)
            put("statusLabel", s.account.statusLabel)
            put("cardTypeName", s.account.cardTypeName)
            put("createdDate", s.account.createdDate)
            put("effectiveDate", s.account.effectiveDate)
            put("singleLimitFen", s.account.singleLimitFen)
            put("cumulativeLimitFen", s.account.cumulativeLimitFen)
        })
        put("queryStartDate", s.queryStartDate)
        put("queryEndDate", s.queryEndDate)
        put("totalCount", s.totalCount)
        put("hasMore", s.hasMore)
        val arr = JSONArray()
        for (tx in s.transactions) {
            arr.put(
                JSONObject().apply {
                    put("id", tx.id)
                    put("title", tx.title)
                    put("txName", tx.txName)
                    put("txCode", tx.txCode)
                    put("posCode", tx.posCode)
                    put("timestampMillis", tx.timestampMillis)
                    put("formattedTime", tx.formattedTime)
                    put("amountFen", tx.amountFen)
                    put("balanceAfterFen", tx.balanceAfterFen)
                }
            )
        }
        put("transactions", arr)
    }

    private fun decodeSnapshot(obj: JSONObject): CardSnapshot {
        val accObj = obj.getJSONObject("account")
        val account = CardAccountInfo(
            holderName = accObj.optString("holderName", "持卡人"),
            maskedId = accObj.optString("maskedId", ""),
            balanceFen = accObj.optLong("balanceFen", 0L),
            statusCode = accObj.optString("statusCode", ""),
            statusLabel = accObj.optString("statusLabel", "状态未知"),
            cardTypeName = accObj.optString("cardTypeName", "校园卡"),
            createdDate = accObj.optString("createdDate", ""),
            effectiveDate = accObj.optString("effectiveDate", ""),
            singleLimitFen = accObj.optLong("singleLimitFen", 0L),
            cumulativeLimitFen = accObj.optLong("cumulativeLimitFen", 0L)
        )
        val arr = obj.optJSONArray("transactions") ?: JSONArray()
        val txs = ArrayList<CardTransaction>(arr.length())
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            txs.add(
                CardTransaction(
                    id = t.optString("id", ""),
                    title = t.optString("title", ""),
                    txName = t.optString("txName", ""),
                    txCode = t.optString("txCode", ""),
                    posCode = t.optString("posCode", "0000"),
                    timestampMillis = t.optLong("timestampMillis", 0L),
                    formattedTime = t.optString("formattedTime", "-"),
                    amountFen = t.optLong("amountFen", 0L),
                    balanceAfterFen = t.optLong("balanceAfterFen", 0L)
                )
            )
        }
        return CardSnapshot(
            account = account,
            transactions = txs,
            queryStartDate = obj.optString("queryStartDate", ""),
            queryEndDate = obj.optString("queryEndDate", ""),
            totalCount = obj.optInt("totalCount", txs.size),
            hasMore = obj.optBoolean("hasMore", false)
        )
    }

    private fun atomicWrite(target: File, content: String) {
        if (!rootDir.exists()) rootDir.mkdirs()
        val tmp = File(rootDir, "${target.name}.${UUID.randomUUID()}.tmp")
        tmp.writeText(content, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.writeText(content, Charsets.UTF_8)
            tmp.delete()
        }
    }

    companion object {
        const val SOURCE_ID = "ecard.buct"

        private fun sanitizeAccountKey(raw: String): String {
            val cleaned = raw.replace(Regex("""[^a-zA-Z0-9._-]"""), "_").take(64)
            return cleaned.ifBlank { "default" }
        }
    }
}
