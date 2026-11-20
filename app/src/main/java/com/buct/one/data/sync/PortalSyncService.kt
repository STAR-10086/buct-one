package com.buct.one.data.sync

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.RefreshState
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.source.portal.PortalClient
import com.buct.one.data.source.portal.PortalParser
import com.buct.one.data.store.PortalStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * i北化官方校历（i.buct.edu.cn）同步服务。
 * 包结构约束：sync -> source, store, session, model；严禁依赖 ui。
 */
class PortalSyncService private constructor(
    private val appContext: Context,
    private val store: PortalStore,
    private val client: PortalClient
) {
    private val syncMutex = Mutex()

    @Volatile
    private var lastRestoredSidCookie: String = ""

    private val _calendarState = MutableStateFlow(store.read(PortalParser.PARSER_VERSION))
    val calendarState: StateFlow<DomainSlice<PortalCalendarSnapshot>> = _calendarState.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        restoreSidPortalCookies()
    }

    fun hasSavedSsoSession(): Boolean = SidPortalSessionVault.hasAnyCookie(appContext)

    fun canAutoSync(): Boolean = hasSavedSsoSession() || SsoSilentRelogin.hasSavedCredentials(appContext)

    private fun restoreSidPortalCookies() {
        val (sidCookies, _) = SidPortalSessionVault.readCookies(appContext)
        if (sidCookies.isNotBlank() && sidCookies != lastRestoredSidCookie) {
            client.importSidPortalCookies(sidCookies)
            client.invalidateSession()
            lastRestoredSidCookie = sidCookies
        }
    }

    private fun persistSidPortalCookies() {
        val currentSid = client.exportSidPortalCookieHeader()
        if (currentSid.isNotBlank() && currentSid != lastRestoredSidCookie) {
            SidPortalSessionVault.saveSidPortalCookies(appContext, currentSid)
            lastRestoredSidCookie = currentSid
        }
    }

    private suspend fun <T> executeWithAutoRelogin(
        userInitiated: Boolean = false,
        block: () -> T
    ): T {
        val sidBefore = SidPortalSessionVault.readCookies(appContext).first
        restoreSidPortalCookies()
        return try {
            block().also { persistSidPortalCookies() }
        } catch (e: Exception) {
            if (!SsoSilentRelogin.isAuthFailure(e)) throw e
            val relogined = SsoSilentRelogin.attemptRelogin(
                context = appContext,
                sidSnapshotBefore = sidBefore,
                bypassCooldown = userInitiated
            ) { username, passwordChars ->
                val ok = client.loginWithSidCredentials(username, passwordChars).isSuccess
                if (ok) persistSidPortalCookies()
                ok
            }
            if (!relogined) throw e
            restoreSidPortalCookies()
            block().also { persistSidPortalCookies() }
        }
    }

    /**
     * 刷新官方校历与节假日快照；非手动触发且 12 小时内已刷新成功时直接复用缓存。
     */
    suspend fun refreshCalendar(userInitiated: Boolean = false): Result<DomainSlice<PortalCalendarSnapshot>> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                val cached = _calendarState.value
                val ageMs = System.currentTimeMillis() - cached.provenance.capturedAt
                if (!userInitiated &&
                    cached.provenance.refreshState == RefreshState.FRESH &&
                    cached.data != null &&
                    cached.data.beginDate.isNotBlank() &&
                    ageMs in 0 until CACHE_TTL_MS
                ) {
                    return@withLock Result.success(cached)
                }

                _syncing.value = true
                try {
                    val todayDate = SimpleDateFormat("yyyy-MM-dd", Locale.ROOT).format(Date())
                    val snapshot = executeWithAutoRelogin(userInitiated = userInitiated) {
                        client.fetchCalendarSnapshot(todayDate)
                    }
                    val committed = store.commitSuccess(
                        snapshot = snapshot,
                        parserVersion = PortalParser.PARSER_VERSION
                    )
                    _calendarState.value = committed
                    Result.success(committed)
                } catch (e: Exception) {
                    val failed = store.commitFailure(
                        errorMessage = e.message ?: "官方校历同步失败",
                        parserVersion = PortalParser.PARSER_VERSION
                    )
                    _calendarState.value = failed
                    Result.failure(e)
                } finally {
                    _syncing.value = false
                }
            }
        }

    companion object {
        private const val CACHE_TTL_MS = 12L * 3600L * 1000L

        @Volatile
        private var instance: PortalSyncService? = null

        fun getInstance(context: Context): PortalSyncService {
            return instance ?: synchronized(this) {
                instance ?: PortalSyncService(
                    appContext = context.applicationContext,
                    store = PortalStore(context.applicationContext),
                    client = PortalClient()
                ).also { instance = it }
            }
        }
    }
}
