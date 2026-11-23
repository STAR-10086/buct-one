package com.buct.course.widget

import android.content.Context
import com.buct.course.manager.ScheduleSettingsManager

/**
 * 桌面小组件按 appWidgetId 保存的实例级配置：
 * - 标题（实例自定义 → 全局默认 → 兜底文案）
 * - 显示账号（配置页可选；空 = 跟随当前账号）
 * - 来源（空 = 本机账号；`friend:<id>` = 好友传来的课表，见 WidgetScheduleSource）
 * - 最近一次解析到的桌面尺寸（RemoteViewsService 拿不到 AppWidgetOptions 时复用）
 *
 * 全部存在独立的 widget_config SharedPreferences，删除实例时按 id 清理，避免 id 复用串配置。
 */
object WidgetConfigStore {
    private const val PREFS = "widget_config"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun title(context: Context, appWidgetId: Int): String? =
        prefs(context).getString("title_$appWidgetId", null)?.takeIf { it.isNotBlank() }

    fun setTitle(context: Context, appWidgetId: Int, value: String?) {
        prefs(context).edit().apply {
            if (value.isNullOrBlank()) remove("title_$appWidgetId")
            else putString("title_$appWidgetId", value.trim())
        }.commit()
    }

    fun accountKey(context: Context, appWidgetId: Int): String? =
        prefs(context).getString("account_$appWidgetId", null)?.takeIf { it.isNotBlank() }

    fun setAccount(context: Context, appWidgetId: Int, value: String?) {
        prefs(context).edit().apply {
            if (value.isNullOrBlank()) remove("account_$appWidgetId")
            else putString("account_$appWidgetId", value)
        }.commit()
    }

    /** 课表来源：空 = 本机账号，`friend:<id>` = 朋友课表。 */
    fun source(context: Context, appWidgetId: Int): String? =
        prefs(context).getString("source_$appWidgetId", null)

    fun setSource(context: Context, appWidgetId: Int, value: String?) {
        prefs(context).edit().apply {
            if (value.isNullOrBlank()) remove("source_$appWidgetId")
            else putString("source_$appWidgetId", value)
        }.commit()
    }

    fun hideTitle(context: Context, appWidgetId: Int): Boolean =
        prefs(context).getBoolean("hide_title_$appWidgetId", false)

    fun setHideTitle(context: Context, appWidgetId: Int, hide: Boolean) {
        prefs(context).edit().putBoolean("hide_title_$appWidgetId", hide).commit()
    }

    fun widgetWidthDp(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt("width_dp_$appWidgetId", 0)

    fun widgetHeightDp(context: Context, appWidgetId: Int): Int =
        prefs(context).getInt("height_dp_$appWidgetId", 0)

    fun setWidgetSize(context: Context, appWidgetId: Int, widthDp: Int, heightDp: Int) {
        if (widthDp <= 0 || heightDp <= 0) return
        val store = prefs(context)
        if (store.getInt("width_dp_$appWidgetId", 0) == widthDp &&
            store.getInt("height_dp_$appWidgetId", 0) == heightDp
        ) {
            return
        }
        store.edit()
            .putInt("width_dp_$appWidgetId", widthDp)
            .putInt("height_dp_$appWidgetId", heightDp)
            .commit()
    }

    fun clear(context: Context, appWidgetId: Int) {
        prefs(context).edit()
            .remove("title_$appWidgetId")
            .remove("hide_title_$appWidgetId")
            .remove("width_dp_$appWidgetId")
            .remove("height_dp_$appWidgetId")
            .remove("account_$appWidgetId")
            .remove("source_$appWidgetId")
            .commit()
    }

    /** 回收桌面上已删除实例的配置，避免 appWidgetId 被系统复用时串配置。 */
    fun prune(context: Context, aliveIds: Set<Int>) {
        val p = prefs(context)
        val stale = p.all.keys.mapNotNull { key ->
            key.substringAfterLast('_', "").toIntOrNull()?.takeIf { it !in aliveIds }?.let { key }
        }
        if (stale.isEmpty()) return
        p.edit().apply { stale.forEach { remove(it) } }.apply()
    }

    /**
     * 实例标题解析：实例自定义 → 全局默认 → 兜底文案（按组件类型区分）。
     *
     * 全局标题是「课表设置」里的那一项，只对课表类小组件生效；
     * 待办事项用它会出现「我的课表」挂在一个待办列表上。
     */
    fun resolveTitle(context: Context, appWidgetId: Int, kind: WidgetKind): String {
        title(context, appWidgetId)?.let { return it }
        if (kind != WidgetKind.TODO) {
            val global = runCatching {
                ScheduleSettingsManager.getInstance().apply { init(context) }.widgetTitle
            }.getOrNull()
            if (!global.isNullOrBlank()) return global
        }
        return kind.defaultTitle
    }
}
