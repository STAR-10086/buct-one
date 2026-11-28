package com.buct.one.data.sync

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.ScsAuditHistoryItem
import com.buct.one.data.model.ScsItemSelfEvalDetail
import com.buct.one.data.model.ScsSelfEvalSubmitPayload
import com.buct.one.data.model.ScsSnapshot
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.source.scs.ScsClient
import com.buct.one.data.source.scs.ScsParser
import com.buct.one.data.store.ScsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 第二课堂（scs.buct.edu.cn）同步服务。
 * 包结构约束：sync -> source, store, session, model；严禁依赖 ui。
 */
class ScsSyncService private constructor(
    private val appContext: Context,
    private val store: ScsStore,
    private val client: ScsClient
) {
    private val syncMutex = Mutex()

    @Volatile
    private var lastRestoredSidCookie: String = ""

    private val _scsState = MutableStateFlow(store.read(ScsParser.PARSER_VERSION))
    val scsState: StateFlow<DomainSlice<ScsSnapshot>> = _scsState.asStateFlow()

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

    suspend fun refresh(userInitiated: Boolean = false): Result<DomainSlice<ScsSnapshot>> = withContext(Dispatchers.IO) {
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
                    parserVersion = ScsParser.PARSER_VERSION
                )
                _scsState.value = committed
                Result.success(committed)
            } catch (e: Exception) {
                val failed = store.commitFailure(
                    errorMessage = e.message ?: "第二课堂同步失败",
                    parserVersion = ScsParser.PARSER_VERSION
                )
                _scsState.value = failed
                Result.failure(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    suspend fun loginAndSync(username: String, passwordChars: CharArray): Result<DomainSlice<ScsSnapshot>> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                _syncing.value = true
                try {
                    val loginRes = client.loginWithSidCredentials(username, passwordChars)
                    if (loginRes.isFailure) {
                        val msg = loginRes.exceptionOrNull()?.message ?: "统一身份认证登录失败"
                        val failed = store.commitFailure(msg, ScsParser.PARSER_VERSION)
                        _scsState.value = failed
                        return@withContext Result.failure(loginRes.exceptionOrNull() ?: IllegalStateException(msg))
                    }
                    SsoSilentRelogin.resetCooldown()
                    persistSidPortalCookies()
                    val snapshot = client.fetchFullSnapshot()
                    val committed = store.commitSuccess(
                        snapshot = snapshot,
                        parserVersion = ScsParser.PARSER_VERSION
                    )
                    _scsState.value = committed
                    Result.success(committed)
                } catch (e: Exception) {
                    val failed = store.commitFailure(
                        errorMessage = e.message ?: "第二课堂同步失败",
                        parserVersion = ScsParser.PARSER_VERSION
                    )
                    _scsState.value = failed
                    Result.failure(e)
                } finally {
                    passwordChars.fill('\u0000')
                    _syncing.value = false
                }
            }
        }

    private suspend fun <T> executeAuthenticated(
        userInitiated: Boolean = true,
        block: () -> T
    ): Result<T> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val sidBefore = SidPortalSessionVault.readCookies(appContext).first
                restoreSidPortalCookies()
                val value = try {
                    block()
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
                    block()
                }
                persistSidPortalCookies()
                Result.success(value)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun requireStudentId(): String {
        val id = _scsState.value.data?.studentId?.trim().orEmpty()
        if (id.isBlank()) {
            throw IllegalStateException("请先刷新同步第二课堂以获取学号信息")
        }
        return id
    }

    suspend fun loadItemSelfEvalDetail(
        yearId: Int,
        termId: Int,
        itemId: Long
    ): Result<ScsItemSelfEvalDetail> = executeAuthenticated {
        val xh = requireStudentId()
        client.fetchItemSelfEvalDetail(
            yearId = yearId,
            termId = termId,
            studentId = xh,
            itemId = itemId
        )
    }

    suspend fun uploadProofImage(
        fileName: String,
        mimeType: String,
        bytes: ByteArray
    ): Result<String> = executeAuthenticated {
        client.uploadCertificateImage(fileName = fileName, mimeType = mimeType, bytes = bytes)
    }

    suspend fun submitSelfEval(payload: ScsSelfEvalSubmitPayload): Result<Unit> {
        val res = executeAuthenticated {
            val xh = requireStudentId()
            client.submitSelfEval(studentId = xh, payload = payload)
        }
        if (res.isSuccess) {
            runCatching { refresh(userInitiated = false) }
        }
        return res
    }

    suspend fun revokeSelfEval(originDetailId: Long): Result<Unit> {
        val res = executeAuthenticated {
            client.revokeSelfEval(originDetailId = originDetailId)
        }
        if (res.isSuccess) {
            runCatching { refresh(userInitiated = false) }
        }
        return res
    }

    suspend fun loadAuditHistories(
        yearId: Int,
        termId: Int,
        typeId: Int? = null
    ): Result<List<ScsAuditHistoryItem>> = executeAuthenticated {
        val xh = requireStudentId()
        client.fetchAuditHistories(
            yearId = yearId,
            termId = termId,
            studentId = xh,
            typeId = typeId
        )
    }

    companion object {
        @Volatile
        private var instance: ScsSyncService? = null

        fun getInstance(context: Context): ScsSyncService {
            return instance ?: synchronized(this) {
                instance ?: ScsSyncService(
                    appContext = context.applicationContext,
                    store = ScsStore(context.applicationContext),
                    client = ScsClient()
                ).also { instance = it }
            }
        }
    }
}
