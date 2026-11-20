package com.buct.one.data.sync

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.MailAttachment
import com.buct.one.data.model.MailDownloadedFile
import com.buct.one.data.model.MailMessageDetail
import com.buct.one.data.model.MailMessageSummary
import com.buct.one.data.model.MailSnapshot
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.source.mail.MailClient
import com.buct.one.data.source.mail.MailParser
import com.buct.one.data.store.MailStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 北化校园邮箱（mail.buct.edu.cn）只读收件同步服务。
 * 包结构约束：sync -> source, store, session, model；严禁依赖 ui。
 */
class MailSyncService private constructor(
    private val appContext: Context,
    private val store: MailStore,
    private val client: MailClient
) {
    private val syncMutex = Mutex()
    private val detailCache = ConcurrentHashMap<String, MailMessageDetail>()

    @Volatile
    private var lastRestoredSidCookie: String = ""

    private val _mailState = MutableStateFlow(store.read(MailParser.PARSER_VERSION))
    val mailState: StateFlow<DomainSlice<MailSnapshot>> = _mailState.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    init {
        restoreSidPortalCookies()
        detailCache.putAll(store.readDetails())
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

    suspend fun refresh(userInitiated: Boolean = false): Result<DomainSlice<MailSnapshot>> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                _syncing.value = true
                try {
                    val snapshot = executeWithAutoRelogin(userInitiated = userInitiated) {
                        client.fetchFullSnapshot()
                    }
                    val committed = store.commitSuccess(
                        snapshot = snapshot,
                        parserVersion = MailParser.PARSER_VERSION
                    )
                    _mailState.value = committed
                    Result.success(committed)
                } catch (e: Exception) {
                    val failed = store.commitFailure(
                        errorMessage = e.message ?: "校园邮箱同步失败",
                        parserVersion = MailParser.PARSER_VERSION
                    )
                    _mailState.value = failed
                    Result.failure(e)
                } finally {
                    _syncing.value = false
                }
            }
        }

    suspend fun loadFolderMessages(
        folderId: Int,
        start: Int = 0,
        limit: Int = 30,
        unreadOnly: Boolean = false,
        userInitiated: Boolean = false
    ): Result<Pair<List<MailMessageSummary>, Int>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val page = executeWithAutoRelogin(userInitiated = userInitiated) {
                    client.fetchMessages(
                        folderId = folderId,
                        start = start,
                        limit = limit,
                        unreadOnly = unreadOnly
                    )
                }
                Result.success(page.messages to page.total)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun fetchMessageDetail(
        messageId: String,
        forceRefresh: Boolean = false
    ): Result<MailMessageDetail> {
        if (!forceRefresh) {
            detailCache[messageId]?.let { return Result.success(it) }
        }
        return withContext(Dispatchers.IO) {
            syncMutex.withLock {
                if (!forceRefresh) {
                    detailCache[messageId]?.let { return@withLock Result.success(it) }
                }
                try {
                    val detail = executeWithAutoRelogin(userInitiated = true) {
                        client.readMessage(messageId)
                    }
                    detailCache[messageId] = detail
                    store.writeDetails(detailCache)
                    Result.success(detail)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }
    }

    suspend fun setMessageRead(messageId: String, isRead: Boolean): Result<Unit> =
        withContext(Dispatchers.IO) {
            syncMutex.withLock {
                try {
                    executeWithAutoRelogin(userInitiated = true) {
                        client.setMessageRead(messageId, isRead)
                    }
                    applyReadStateToSnapshot(messageId, isRead)
                    Result.success(Unit)
                } catch (e: Exception) {
                    Result.failure(e)
                }
            }
        }

    private fun applyReadStateToSnapshot(messageId: String, isRead: Boolean) {
        val currentSlice = _mailState.value
        val currentData = currentSlice.data ?: return
        var deltaUnread = 0
        var targetFolderId: Int? = null
        val updatedInbox = currentData.inboxMessages.map { msg ->
            if (msg.id == messageId) {
                if (msg.isRead != isRead) {
                    deltaUnread = if (isRead) -1 else 1
                    targetFolderId = msg.folderId
                }
                msg.copy(isRead = isRead)
            } else {
                msg
            }
        }
        if (deltaUnread == 0 && updatedInbox == currentData.inboxMessages) return
        val nextFolders = currentData.folders.map { folder ->
            if (targetFolderId != null && folder.id == targetFolderId) {
                folder.copy(unreadCount = (folder.unreadCount + deltaUnread).coerceAtLeast(0))
            } else {
                folder
            }
        }
        val nextSnapshot = currentData.copy(
            folders = nextFolders,
            inboxMessages = updatedInbox,
            unreadTotal = (currentData.unreadTotal + deltaUnread).coerceAtLeast(0)
        )
        _mailState.value = store.commitSuccess(
            snapshot = nextSnapshot,
            parserVersion = MailParser.PARSER_VERSION
        )
    }

    suspend fun downloadAttachment(
        messageId: String,
        attachment: MailAttachment
    ): Result<MailDownloadedFile> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            try {
                val downloaded = executeWithAutoRelogin(userInitiated = true) {
                    client.downloadAttachment(messageId, attachment, appContext.cacheDir)
                }
                Result.success(downloaded)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    suspend fun loginAndSync(
        username: String,
        passwordChars: CharArray
    ): Result<DomainSlice<MailSnapshot>> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val loginRes = client.loginWithSidCredentials(username, passwordChars)
                if (loginRes.isFailure) {
                    val msg = loginRes.exceptionOrNull()?.message ?: "统一身份认证登录失败"
                    val failed = store.commitFailure(msg, MailParser.PARSER_VERSION)
                    _mailState.value = failed
                    return@withContext Result.failure(loginRes.exceptionOrNull() ?: IllegalStateException(msg))
                }
                SsoSilentRelogin.resetCooldown()
                persistSidPortalCookies()
                val snapshot = client.fetchFullSnapshot()
                val committed = store.commitSuccess(
                    snapshot = snapshot,
                    parserVersion = MailParser.PARSER_VERSION
                )
                _mailState.value = committed
                Result.success(committed)
            } catch (e: Exception) {
                val failed = store.commitFailure(
                    errorMessage = e.message ?: "校园邮箱同步失败",
                    parserVersion = MailParser.PARSER_VERSION
                )
                _mailState.value = failed
                Result.failure(e)
            } finally {
                passwordChars.fill('\u0000')
                _syncing.value = false
            }
        }
    }

    companion object {
        @Volatile
        private var instance: MailSyncService? = null

        fun getInstance(context: Context): MailSyncService {
            return instance ?: synchronized(this) {
                instance ?: MailSyncService(
                    appContext = context.applicationContext,
                    store = MailStore(context.applicationContext),
                    client = MailClient()
                ).also { instance = it }
            }
        }
    }
}
