package com.buct.course.usage

import okhttp3.OkHttpClient
import java.io.IOException

/**
 * BUCT One 关闭所有上游统计上报，禁止向第三方服务器发送数据。
 */
internal class HttpUsageTransport(
    @Suppress("UNUSED_PARAMETER") client: OkHttpClient = OkHttpClient(),
    @Suppress("UNUSED_PARAMETER") endpoint: String = ""
) : UsageTransport {
    override suspend fun report(installationId: String, version: String): String {
        throw IOException("Usage reporting is disabled in BUCT One")
    }
}
