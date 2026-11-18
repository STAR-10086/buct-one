package com.buct.course.analytics

import android.content.Context

/**
 * BUCT One 无统计后端，彻底关闭所有上游使用统计上报。
 */
object UsageStatsReporter {
    fun isEnabled(context: Context): Boolean = false

    fun setEnabled(context: Context, enabled: Boolean) {
        // no-op: 禁止任何外部统计上报
    }

    fun reportLaunch(context: Context) {
        // no-op: 禁止任何外部统计上报
    }
}
