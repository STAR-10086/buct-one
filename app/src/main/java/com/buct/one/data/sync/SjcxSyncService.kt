package com.buct.one.data.sync

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.SjcxDownloadedFile
import com.buct.one.data.model.SjcxExperimentReportItem
import com.buct.one.data.model.SjcxSnapshot
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.source.sjcx.SjcxClient
import com.buct.one.data.source.sjcx.SjcxParser
import com.buct.one.data.store.SjcxStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 实践教学综合服务平台（sjcx.buct.edu.cn）同步服务。
 *
 * 包结构约束：sync -> source, store, session, model；严禁依赖 ui。
 */
class SjcxSyncService private constructor(
    private val appContext: Context,
    private val store: SjcxStore,
    private val client: SjcxClient
) {
    private val syncMutex = Mutex()

    @Volatile
    private var lastRestoredSidCookie: String = ""

    private val _sjcxState = MutableStateFlow(store.read(SjcxParser.PARSER_VERSION))
    val sjcxState: StateFlow<DomainSlice<SjcxSnapshot>> = _sjcxState.asStateFlow()

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
     * 尝试复用已有统一身份认证（sidportal）TGT 同步实践教学数据，会话失效时自动尝试静默重登。
     */
    suspend fun refresh(userInitiated: Boolean = false): Result<DomainSlice<SjcxSnapshot>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val snapshot = executeWithAutoRelogin(userInitiated = userInitiated) {
                    client.fetchFullSnapshot()
                }
                val committed = store.commitSuccess(
                    snapshot = snapshot,
                    parserVersion = SjcxParser.PARSER_VERSION
                )
                _sjcxState.value = committed
                Result.success(committed)
            } catch (e: Exception) {
                val failed = store.commitFailure(
                    errorMessage = e.message ?: "实践教学平台同步失败",
                    parserVersion = SjcxParser.PARSER_VERSION
                )
                _sjcxState.value = failed
                Result.failure(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * 当 sidportal 会话过期时，使用统一身份认证学号和密码（CharArray 用完立即清零）直接重登并同步实践教学数据。
     */
    suspend fun loginAndSync(username: String, passwordChars: CharArray): Result<DomainSlice<SjcxSnapshot>> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                _syncing.value = true
                try {
                    val loginRes = client.loginWithSidCredentials(username, passwordChars)
                    if (loginRes.isFailure) {
                        val msg = loginRes.exceptionOrNull()?.message ?: "统一身份认证登录失败"
                        val failed = store.commitFailure(msg, SjcxParser.PARSER_VERSION)
                        _sjcxState.value = failed
                        return@withContext Result.failure(loginRes.exceptionOrNull() ?: IllegalStateException(msg))
                    }
                    SsoSilentRelogin.resetCooldown()
                    persistSidPortalCookies()
                    val snapshot = client.fetchFullSnapshot()
                    val committed = store.commitSuccess(
                        snapshot = snapshot,
                        parserVersion = SjcxParser.PARSER_VERSION
                    )
                    _sjcxState.value = committed
                    Result.success(committed)
                } catch (e: Exception) {
                    val failed = store.commitFailure(
                        errorMessage = e.message ?: "实践教学平台同步失败",
                        parserVersion = SjcxParser.PARSER_VERSION
                    )
                    _sjcxState.value = failed
                    Result.failure(e)
                } finally {
                    passwordChars.fill('\u0000')
                    _syncing.value = false
                }
            }
        }

    /**
     * 上传提交实验报告 PDF，成功后自动刷新一次快照。
     */
    suspend fun uploadExperimentReport(
        item: SjcxExperimentReportItem,
        pdfFile: File,
        displayFileName: String
    ): Result<String> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val msg = executeWithAutoRelogin(userInitiated = true) {
                    client.uploadExperimentReport(item, pdfFile, displayFileName)
                }
                runCatching {
                    val snapshot = client.fetchFullSnapshot()
                    val committed = store.commitSuccess(snapshot, SjcxParser.PARSER_VERSION)
                    _sjcxState.value = committed
                }
                Result.success(msg)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * 下载实验报告模板 PDF 到应用缓存目录。
     */
    suspend fun downloadReportTemplate(
        item: SjcxExperimentReportItem
    ): Result<SjcxDownloadedFile> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val file = executeWithAutoRelogin(userInitiated = true) {
                    client.downloadReportTemplate(item, appContext.cacheDir)
                }
                Result.success(file)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 下载已提交的实验报告 PDF 到应用缓存目录。
     */
    suspend fun downloadSubmittedReport(
        item: SjcxExperimentReportItem
    ): Result<SjcxDownloadedFile> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val file = executeWithAutoRelogin(userInitiated = true) {
                    client.downloadSubmittedReport(item, appContext.cacheDir)
                }
                Result.success(file)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /**
     * 报名讲座/活动，成功后自动刷新快照。
     */
    suspend fun registerLecture(projectTimeId: String): Result<String> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val msg = executeWithAutoRelogin(userInitiated = true) {
                    client.registerLecture(projectTimeId)
                }
                runCatching {
                    val snapshot = client.fetchFullSnapshot()
                    _sjcxState.value = store.commitSuccess(snapshot, SjcxParser.PARSER_VERSION)
                }
                Result.success(msg)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * 取消讲座报名，成功后自动刷新快照。
     */
    suspend fun cancelLectureRegistration(creProjectStuId: String): Result<String> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val msg = executeWithAutoRelogin(userInitiated = true) {
                    client.cancelLectureRegistration(creProjectStuId)
                }
                runCatching {
                    val snapshot = client.fetchFullSnapshot()
                    _sjcxState.value = store.commitSuccess(snapshot, SjcxParser.PARSER_VERSION)
                }
                Result.success(msg)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    /**
     * 保存/修改讲座心得体会，成功后自动刷新快照。
     */
    suspend fun saveLectureExperience(
        creProjectStuId: String,
        experience: String
    ): Result<String> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val msg = executeWithAutoRelogin(userInitiated = true) {
                    client.saveLectureExperience(creProjectStuId, experience)
                }
                runCatching {
                    val snapshot = client.fetchFullSnapshot()
                    _sjcxState.value = store.commitSuccess(snapshot, SjcxParser.PARSER_VERSION)
                }
                Result.success(msg)
            } catch (e: Exception) {
                Result.failure(e)
            } finally {
                _syncing.value = false
            }
        }
    }

    companion object {
        @Volatile
        private var instance: SjcxSyncService? = null

        fun getInstance(context: Context): SjcxSyncService {
            return instance ?: synchronized(this) {
                instance ?: SjcxSyncService(
                    appContext = context.applicationContext,
                    store = SjcxStore(context.applicationContext),
                    client = SjcxClient()
                ).also { instance = it }
            }
        }
    }
}
