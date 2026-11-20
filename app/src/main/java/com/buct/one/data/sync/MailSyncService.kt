package com.buct.one.data.sync

import android.content.Context
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.model.MailAttachment
import com.buct.one.data.model.MailDownloadedFile
import com.buct.one.data.model.MailFolderIds
import com.buct.one.data.model.MailMessageDetail
import com.buct.one.data.model.MailMessageSummary
import com.buct.one.data.model.MailSnapshot
import com.buct.one.data.session.SidPortalSessionVault
import com.buct.one.data.source.mail.MailClient
import com.buct.one.data.source.mail.MailParser
import com.buct.one.data.store.MailStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * 北化校园邮箱（mail.buct.edu.cn）收件同步与状态操作服务。
 * 包结构约束：sync -> source, store, session, model；严禁依赖 ui。
 */
class MailSyncService private constructor(
    private val appContext: Context,
    private val store: MailStore,
    private val client: MailClient
) {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
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

    private suspend fun runServerAction(
        serverCall: () -> Unit,
        onCommittedSuccess: () -> Unit
    ): Result<Unit> = serviceScope.async {
        syncMutex.withLock {
            try {
                executeWithAutoRelogin(userInitiated = true) {
                    serverCall()
                }
                onCommittedSuccess()
                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }.await()

    suspend fun setMessageRead(
        messageId: String,
        isRead: Boolean,
        folderId: Int = MailFolderIds.INBOX,
        previousIsRead: Boolean? = null
    ): Result<Unit> = runServerAction(
        serverCall = { client.setMessageRead(messageId, isRead) },
        onCommittedSuccess = {
            applyReadStateToSnapshot(
                messageId = messageId,
                folderId = folderId,
                isRead = isRead,
                previousIsRead = previousIsRead
            )
        }
    )

    suspend fun deleteMessage(
        message: MailMessageSummary,
        permanent: Boolean = false
    ): Result<Unit> = runServerAction(
        serverCall = { client.deleteMessage(message.id, message.folderId, permanent) },
        onCommittedSuccess = {
            detailCache.remove(message.id)
            store.writeDetails(detailCache)
            val targetFolder = if (permanent || message.folderId == MailFolderIds.TRASH) {
                null
            } else {
                MailFolderIds.TRASH
            }
            applyFolderMoveToSnapshot(message = message, targetFolderId = targetFolder)
        }
    )

    suspend fun restoreMessageToInbox(message: MailMessageSummary): Result<Unit> =
        runServerAction(
            serverCall = { client.moveMessages(listOf(message.id), MailFolderIds.INBOX) },
            onCommittedSuccess = {
                applyFolderMoveToSnapshot(message = message, targetFolderId = MailFolderIds.INBOX)
            }
        )

    suspend fun markFolderAllRead(folderId: Int = MailFolderIds.INBOX): Result<Unit> =
        runServerAction(
            serverCall = { client.markFolderAllRead(folderId) },
            onCommittedSuccess = { applyMarkFolderAllReadToSnapshot(folderId) }
        )

    private fun applyReadStateToSnapshot(
        messageId: String,
        folderId: Int,
        isRead: Boolean,
        previousIsRead: Boolean?
    ) {
        val currentData = _mailState.value.data ?: return
        val existing = currentData.inboxMessages.firstOrNull { it.id == messageId }
        val oldIsRead = existing?.isRead ?: previousIsRead
        val effectiveFolderId = existing?.folderId ?: folderId
        val deltaUnread = when {
            oldIsRead == null || oldIsRead == isRead -> 0
            isRead -> -1
            else -> 1
        }
        if (deltaUnread == 0 && existing == null) return

        val updatedInbox = if (existing != null) {
            currentData.inboxMessages.map { msg ->
                if (msg.id == messageId) msg.copy(isRead = isRead) else msg
            }
        } else {
            currentData.inboxMessages
        }
        val nextFolders = if (deltaUnread != 0) {
            currentData.folders.map { folder ->
                if (folder.id == effectiveFolderId) {
                    folder.copy(unreadCount = (folder.unreadCount + deltaUnread).coerceAtLeast(0))
                } else {
                    folder
                }
            }
        } else {
            currentData.folders
        }
        val nextUnreadTotal = if (effectiveFolderId == MailFolderIds.INBOX && deltaUnread != 0) {
            (currentData.unreadTotal + deltaUnread).coerceAtLeast(0)
        } else {
            currentData.unreadTotal
        }
        val nextSnapshot = currentData.copy(
            folders = nextFolders,
            inboxMessages = updatedInbox,
            unreadTotal = nextUnreadTotal
        )
        _mailState.value = store.commitSuccess(
            snapshot = nextSnapshot,
            parserVersion = MailParser.PARSER_VERSION
        )
    }

    private fun applyFolderMoveToSnapshot(
        message: MailMessageSummary,
        targetFolderId: Int?
    ) {
        if (targetFolderId == message.folderId) return
        val currentData = _mailState.value.data ?: return
        val existing = currentData.inboxMessages.firstOrNull { it.id == message.id }
        val sourceFolderId = existing?.folderId ?: message.folderId
        val wasUnread = !(existing?.isRead ?: message.isRead)

        val nextFolders = currentData.folders.map { folder ->
            when (folder.id) {
                sourceFolderId -> folder.copy(
                    totalCount = (folder.totalCount - 1).coerceAtLeast(0),
                    unreadCount = if (wasUnread) (folder.unreadCount - 1).coerceAtLeast(0) else folder.unreadCount
                )
                targetFolderId -> folder.copy(
                    totalCount = folder.totalCount + 1,
                    unreadCount = if (wasUnread) folder.unreadCount + 1 else folder.unreadCount
                )
                else -> folder
            }
        }

        val updatedInbox = if (targetFolderId == MailFolderIds.INBOX) {
            if (existing != null) {
                currentData.inboxMessages.map { msg ->
                    if (msg.id == message.id) msg.copy(folderId = MailFolderIds.INBOX) else msg
                }
            } else {
                listOf(message.copy(folderId = MailFolderIds.INBOX)) + currentData.inboxMessages
            }
        } else {
            currentData.inboxMessages.filterNot { it.id == message.id }
        }

        val (nextInboxTotal, nextUnreadTotal) = when {
            sourceFolderId == MailFolderIds.INBOX && targetFolderId != MailFolderIds.INBOX -> {
                (currentData.inboxTotal - 1).coerceAtLeast(0) to
                    if (wasUnread) (currentData.unreadTotal - 1).coerceAtLeast(0) else currentData.unreadTotal
            }
            targetFolderId == MailFolderIds.INBOX && sourceFolderId != MailFolderIds.INBOX -> {
                (currentData.inboxTotal + 1) to
                    if (wasUnread) (currentData.unreadTotal + 1) else currentData.unreadTotal
            }
            else -> currentData.inboxTotal to currentData.unreadTotal
        }

        val nextSnapshot = currentData.copy(
            folders = nextFolders,
            inboxMessages = updatedInbox,
            inboxTotal = nextInboxTotal,
            unreadTotal = nextUnreadTotal
        )
        _mailState.value = store.commitSuccess(
            snapshot = nextSnapshot,
            parserVersion = MailParser.PARSER_VERSION
        )
    }

    private fun applyMarkFolderAllReadToSnapshot(folderId: Int) {
        val currentData = _mailState.value.data ?: return
        val updatedInbox = currentData.inboxMessages.map { msg ->
            if (msg.folderId == folderId && !msg.isRead) msg.copy(isRead = true) else msg
        }
        val nextFolders = currentData.folders.map { folder ->
            if (folder.id == folderId) folder.copy(unreadCount = 0) else folder
        }
        val nextUnreadTotal = if (folderId == MailFolderIds.INBOX) 0 else currentData.unreadTotal
        val nextSnapshot = currentData.copy(
            folders = nextFolders,
            inboxMessages = updatedInbox,
            unreadTotal = nextUnreadTotal
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
