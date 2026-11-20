package com.buct.one.data.sync

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.TyglSnapshot
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.source.tygl.TyglClient
import com.buct.one.data.source.tygl.TyglParser
import com.buct.one.data.store.TyglStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 体测与体育管理平台（tygl.buct.edu.cn）同步服务。
 * 包结构约束：sync -> source, store, session, model；严禁依赖 ui。
 */
class TyglSyncService private constructor(
    private val appContext: Context,
    private val store: TyglStore,
    private val client: TyglClient
) {
    private val syncMutex = Mutex()

    @Volatile
    private var lastRestoredSidCookie: String = ""

    private val _tyglState = MutableStateFlow(store.read(TyglParser.PARSER_VERSION))
    val tyglState: StateFlow<DomainSlice<TyglSnapshot>> = _tyglState.asStateFlow()

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

    suspend fun refresh(userInitiated: Boolean = false): Result<DomainSlice<TyglSnapshot>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val sidBefore = SidPortalSessionVault.readCookies(appContext).first
                restoreSidPortalCookies()
                val snapshot = try {
                    client.fetchFullSnapshot()
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
                    client.fetchFullSnapshot()
                }
                persistSidPortalCookies()
                val committed = store.commitSuccess(
                    snapshot = snapshot,
                    parserVersion = TyglParser.PARSER_VERSION
                )
                _tyglState.value = committed
                Result.success(committed)
            } catch (e: Exception) {
                val failed = store.commitFailure(
                    errorMessage = e.message ?: "体测系统同步失败",
                    parserVersion = TyglParser.PARSER_VERSION
                )
                _tyglState.value = failed
                Result.failure(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    suspend fun loginAndSync(username: String, passwordChars: CharArray): Result<DomainSlice<TyglSnapshot>> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                _syncing.value = true
                try {
                    val loginRes = client.loginWithSidCredentials(username, passwordChars)
                    if (loginRes.isFailure) {
                        val msg = loginRes.exceptionOrNull()?.message ?: "统一身份认证登录失败"
                        val failed = store.commitFailure(msg, TyglParser.PARSER_VERSION)
                        _tyglState.value = failed
                        return@withContext Result.failure(loginRes.exceptionOrNull() ?: IllegalStateException(msg))
                    }
                    SsoSilentRelogin.resetCooldown()
                    persistSidPortalCookies()
                    val snapshot = client.fetchFullSnapshot()
                    val committed = store.commitSuccess(
                        snapshot = snapshot,
                        parserVersion = TyglParser.PARSER_VERSION
                    )
                    _tyglState.value = committed
                    Result.success(committed)
                } catch (e: Exception) {
                    val failed = store.commitFailure(
                        errorMessage = e.message ?: "体测系统同步失败",
                        parserVersion = TyglParser.PARSER_VERSION
                    )
                    _tyglState.value = failed
                    Result.failure(e)
                } finally {
                    passwordChars.fill('\u0000')
                    _syncing.value = false
                }
            }
        }

    companion object {
        @Volatile
        private var instance: TyglSyncService? = null

        fun getInstance(context: Context): TyglSyncService {
            return instance ?: synchronized(this) {
                instance ?: TyglSyncService(
                    appContext = context.applicationContext,
                    store = TyglStore(context.applicationContext),
                    client = TyglClient()
                ).also { instance = it }
            }
        }
    }
}
