package com.buct.one.data.sync

import android.content.Context
import com.buct.course.login.BuctWebVpnAuthRequiredException
import com.buct.course.manager.UserManager
import com.buct.one.data.model.CampusNetSnapshot
import com.buct.one.data.model.DomainSlice
import com.buct.one.data.source.campusnet.CampusNetClient
import com.buct.one.data.source.campusnet.CampusNetParser
import com.buct.one.data.store.CampusNetStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * 北化校园网网费与账单（tree.buct.edu.cn & :8800 自服务平台）只读同步服务。
 * 全程免密直连校内网网关，绝不走 WebVPN；遵守数据层四条铁律。
 */
class CampusNetSyncService private constructor(
    private val appContext: Context,
    private val store: CampusNetStore,
    private val client: CampusNetClient
) {
    private val syncMutex = Mutex()

    private val _campusNetState = MutableStateFlow(store.read(CampusNetParser.PARSER_VERSION))
    val campusNetState: StateFlow<DomainSlice<CampusNetSnapshot>> = _campusNetState.asStateFlow()

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing.asStateFlow()

    private fun resolveFallbackStudentId(): String {
        val user = UserManager.getInstance().apply { init(appContext) }
        if (user.isDemoMode) return ""
        return user.studentId?.trim()?.takeIf { it.isNotBlank() }
            ?: user.username?.trim().orEmpty()
    }

    suspend fun refresh(): Result<CampusNetSnapshot> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            _syncing.value = true
            try {
                val snap = client.fetchBillingSnapshot(resolveFallbackStudentId())
                val committed = store.commitSuccess(snap, CampusNetParser.PARSER_VERSION)
                _campusNetState.value = committed
                Result.success(snap)
            } catch (e: Throwable) {
                val msg = mapErrorMessage(e)
                _campusNetState.value = store.commitFailure(msg, CampusNetParser.PARSER_VERSION)
                Result.failure(if (e is Exception) e else RuntimeException(msg, e))
            } finally {
                _syncing.value = false
            }
        }
    }

    private fun mapErrorMessage(t: Throwable): String {
        if (t is BuctWebVpnAuthRequiredException) {
            return t.message?.takeIf { it.isNotBlank() } ?: "校外查询校园网网费需先完成 WebVPN 认证"
        }
        return when (t) {
            is UnknownHostException, is ConnectException, is SocketTimeoutException ->
                "无法连接校园网网关（已尝试直连与 WebVPN），请检查网络或重新完成 WebVPN 认证"
            is IOException ->
                t.message?.takeIf { it.isNotBlank() } ?: "校园网网费查询网络异常，请检查网络后重试"
            else ->
                t.message?.takeIf { it.isNotBlank() } ?: "校园网网费查询失败，请稍后重试"
        }
    }

    companion object {
        @Volatile
        private var instance: CampusNetSyncService? = null

        fun getInstance(context: Context): CampusNetSyncService {
            return instance ?: synchronized(this) {
                instance ?: CampusNetSyncService(
                    appContext = context.applicationContext,
                    store = CampusNetStore(context.applicationContext),
                    client = CampusNetClient()
                ).also { instance = it }
            }
        }
    }
}
