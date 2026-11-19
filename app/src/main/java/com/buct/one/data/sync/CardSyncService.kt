package com.buct.one.data.sync

import android.content.Context
import com.buct.one.data.model.CardSnapshot
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.session.CardSessionVault
import com.buct.one.data.source.card.EcardAuthException
import com.buct.one.data.source.card.EcardClient
import com.buct.one.data.source.card.EcardParser
import com.buct.one.data.source.card.EcardSessionExpiredException
import com.buct.one.data.store.CardStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/**
 * 校园卡（ecard.buct.edu.cn）独立同步服务。
 * 与统一身份认证（sidportal）生命周期完全解耦，单域串行刷新并在失败时严格保留旧快照。
 */
class CardSyncService private constructor(
    private val appContext: Context,
    private val store: CardStore,
    private val client: EcardClient
) {
    private val syncMutex = Mutex()

    private val _cardState = MutableStateFlow(store.read(EcardParser.PARSER_VERSION))
    val cardState: StateFlow<DomainSlice<CardSnapshot>> = _cardState.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private val _hasSession = MutableStateFlow(CardSessionVault.hasSession(appContext))
    val hasSession: StateFlow<Boolean> = _hasSession.asStateFlow()

    init {
        restoreSessionIfPresent()
    }

    /**
     * 使用学工号与校园卡查询密码原生登录并同步实时余额与近期消费流水。
     * 传入的 CharArray 密码无论成功失败均立即清零，绝不落盘。
     */
    suspend fun loginAndSync(
        idserial: String,
        password: CharArray,
        rangeDays: Int = 30,
        pageSize: Int = 20
    ): Result<CardSnapshot> = withContext(Dispatchers.IO) {
        try {
            syncMutex.withLock {
                _syncing.value = true
                try {
                    val cleanId = idserial.trim()
                    client.login(cleanId, password)
                    val acctypeMap = client.fetchAcctypeMap()
                    val account = client.fetchAccountInfo(cleanId, acctypeMap)
                    val (startDate, endDate) = computeRecentRange(rangeDays)
                    val page = client.fetchTransactions(
                        idserial = cleanId,
                        startDate = startDate,
                        endDate = endDate,
                        tradeType = "-1",
                        pageNumber = 0,
                        pageSize = pageSize
                    )
                    val (cookie, rsaKey) = client.exportSession()
                    CardSessionVault.save(appContext, cookie, cleanId, rsaKey)
                    _hasSession.value = true

                    val snapshot = CardSnapshot(
                        account = account,
                        transactions = page.items,
                        queryStartDate = startDate,
                        queryEndDate = endDate,
                        totalCount = page.totalCount,
                        hasMore = page.hasMore
                    )
                    val committed = store.commitSuccess(
                        snapshot = snapshot,
                        parserVersion = EcardParser.PARSER_VERSION,
                        nowMillis = System.currentTimeMillis()
                    )
                    _cardState.value = committed
                    Result.success(snapshot)
                } catch (e: EcardAuthException) {
                    val failed = store.commitFailure(
                        e.message ?: "校园卡登录失败",
                        EcardParser.PARSER_VERSION
                    )
                    _cardState.value = failed
                    Result.failure(e)
                } catch (e: Exception) {
                    val failed = store.commitFailure(
                        e.message ?: "校园卡同步失败",
                        EcardParser.PARSER_VERSION
                    )
                    _cardState.value = failed
                    Result.failure(e)
                } finally {
                    _syncing.value = false
                }
            }
        } finally {
            password.fill('\u0000')
        }
    }

    /**
     * 复用加密会话刷新实时账户余额与指定日期区间第一页流水。
     * 若服务端会话已过期，清理失效 Cookie 并通过 commitFailure 保留旧余额与旧流水。
     */
    suspend fun refresh(
        startDate: String? = null,
        endDate: String? = null,
        tradeType: String = "-1",
        pageSize: Int = 20
    ): Result<CardSnapshot> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val stored = CardSessionVault.read(appContext)
                if (stored == null) {
                    _hasSession.value = false
                    val failed = store.commitFailure("请先登录校园卡查询平台", EcardParser.PARSER_VERSION)
                    _cardState.value = failed
                    return@withContext Result.failure(IllegalStateException(failed.lastError))
                }
                client.restoreSession(stored.cookie, stored.rsaPublicKey)

                val acctypeMap = client.fetchAcctypeMap()
                val account = client.fetchAccountInfo(stored.idserial, acctypeMap)
                val (defaultStart, defaultEnd) = computeRecentRange(30)
                val qStart = startDate?.trim()?.ifBlank { defaultStart } ?: defaultStart
                val qEnd = endDate?.trim()?.ifBlank { defaultEnd } ?: defaultEnd

                val page = client.fetchTransactions(
                    idserial = stored.idserial,
                    startDate = qStart,
                    endDate = qEnd,
                    tradeType = tradeType,
                    pageNumber = 0,
                    pageSize = pageSize
                )

                val (latestCookie, latestKey) = client.exportSession()
                if (latestCookie.isNotBlank() &&
                    (latestCookie != stored.cookie || latestKey != stored.rsaPublicKey)
                ) {
                    CardSessionVault.save(appContext, latestCookie, stored.idserial, latestKey)
                }
                _hasSession.value = true

                val snapshot = CardSnapshot(
                    account = account,
                    transactions = page.items,
                    queryStartDate = qStart,
                    queryEndDate = qEnd,
                    totalCount = page.totalCount,
                    hasMore = page.hasMore
                )
                val committed = store.commitSuccess(
                    snapshot = snapshot,
                    parserVersion = EcardParser.PARSER_VERSION,
                    nowMillis = System.currentTimeMillis()
                )
                _cardState.value = committed
                Result.success(snapshot)
            } catch (e: EcardSessionExpiredException) {
                CardSessionVault.clear(appContext)
                client.clearSession()
                _hasSession.value = false
                val failed = store.commitFailure(
                    e.message ?: "校园卡会话已过期，请重新登录",
                    EcardParser.PARSER_VERSION
                )
                _cardState.value = failed
                Result.failure(e)
            } catch (e: Exception) {
                val failed = store.commitFailure(
                    e.message ?: "刷新校园卡数据失败",
                    EcardParser.PARSER_VERSION
                )
                _cardState.value = failed
                Result.failure(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * 加载下一页消费流水并合并去重到当前快照。
     */
    suspend fun loadMore(
        startDate: String,
        endDate: String,
        tradeType: String = "-1",
        nextPage: Int,
        pageSize: Int = 20
    ): Result<CardSnapshot> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val stored = CardSessionVault.read(appContext)
                if (stored == null) {
                    _hasSession.value = false
                    return@withContext Result.failure(EcardSessionExpiredException("校园卡会话已过期，请重新登录"))
                }
                val prev = _cardState.value.data
                    ?: return@withContext Result.failure(IllegalStateException("请先刷新校园卡数据"))
                client.restoreSession(stored.cookie, stored.rsaPublicKey)

                val page = client.fetchTransactions(
                    idserial = stored.idserial,
                    startDate = startDate,
                    endDate = endDate,
                    tradeType = tradeType,
                    pageNumber = nextPage,
                    pageSize = pageSize
                )
                val merged = (prev.transactions + page.items).distinctBy { it.id }
                val updated = prev.copy(
                    transactions = merged,
                    queryStartDate = startDate,
                    queryEndDate = endDate,
                    totalCount = maxOf(prev.totalCount, page.totalCount),
                    hasMore = page.hasMore
                )
                val committed = store.commitSuccess(
                    snapshot = updated,
                    parserVersion = EcardParser.PARSER_VERSION,
                    nowMillis = System.currentTimeMillis()
                )
                _cardState.value = committed
                Result.success(updated)
            } catch (e: EcardSessionExpiredException) {
                CardSessionVault.clear(appContext)
                client.clearSession()
                _hasSession.value = false
                val failed = store.commitFailure(
                    e.message ?: "校园卡会话已过期，请重新登录",
                    EcardParser.PARSER_VERSION
                )
                _cardState.value = failed
                Result.failure(e)
            } catch (e: Exception) {
                val failed = store.commitFailure(
                    e.message ?: "加载更多流水失败",
                    EcardParser.PARSER_VERSION
                )
                _cardState.value = failed
                Result.failure(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    suspend fun logout() = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            CardSessionVault.clear(appContext)
            client.clearSession()
            store.clear()
            _hasSession.value = false
            _cardState.value = store.read(EcardParser.PARSER_VERSION)
        }
    }

    fun getSavedAccountId(): String = CardSessionVault.read(appContext)?.idserial.orEmpty()

    private fun restoreSessionIfPresent() {
        val stored = CardSessionVault.read(appContext)
        if (stored != null) {
            client.restoreSession(stored.cookie, stored.rsaPublicKey)
            _hasSession.value = true
        } else {
            _hasSession.value = false
        }
    }

    companion object {
        @Volatile
        private var instance: CardSyncService? = null

        fun getInstance(context: Context): CardSyncService {
            return instance ?: synchronized(this) {
                instance ?: CardSyncService(
                    appContext = context.applicationContext,
                    store = CardStore(context.applicationContext),
                    client = EcardClient()
                ).also { instance = it }
            }
        }

        fun computeRecentRange(days: Int): Pair<String, String> {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val endCal = Calendar.getInstance()
            val startCal = (endCal.clone() as Calendar).apply {
                add(Calendar.DAY_OF_MONTH, -(days.coerceIn(1, 31) - 1))
            }
            return fmt.format(startCal.time) to fmt.format(endCal.time)
        }

        fun computeCurrentMonthRange(): Pair<String, String> {
            val fmt = SimpleDateFormat("yyyy-MM-dd", Locale.CHINA)
            val endCal = Calendar.getInstance()
            val startCal = (endCal.clone() as Calendar).apply {
                set(Calendar.DAY_OF_MONTH, 1)
            }
            return fmt.format(startCal.time) to fmt.format(endCal.time)
        }
    }
}
