package com.buct.course.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent

/**
 * 小组件 provider 统一入口。
 *
 * 桌面上实际注册的是**旧包名**的两个兼容壳（com.tyust.course.widget.*）——
 * 老用户已经添加的实例绑的就是这个全名，不能改。新代码（配置页、管理页、刷新广播）
 * 必须同时认识新旧两套组件，否则会出现「组件在桌面上、App 里却看不到」的割裂。
 * 「今日课表·卡片」与「待办事项」是后加的独立组件，直接用新包名注册，
 * 与原有的「今日课表」并存、互不影响。
 */
object WidgetProviders {

    /** 统一刷新广播：四个小组件的 receiver 都要在 manifest 里声明这个 action。 */
    const val ACTION_REFRESH = "com.buct.course.widget.REFRESH_WIDGET"

    private const val LEGACY_WEEK = "com.tyust.course.widget.ScheduleWidget"
    private const val LEGACY_DAY = "com.tyust.course.widget.ScheduleDayWidget"

    fun week(context: Context): List<ComponentName> = listOf(
        ComponentName(context, ScheduleWidget::class.java),
        ComponentName(context.packageName, LEGACY_WEEK)
    )

    fun day(context: Context): List<ComponentName> = listOf(
        ComponentName(context, ScheduleDayWidget::class.java),
        ComponentName(context.packageName, LEGACY_DAY)
    )

    /** 新版「今日课表·卡片」（与按天翻页的旧版并存）。 */
    fun today(context: Context): List<ComponentName> = listOf(
        ComponentName(context, TodayWidget::class.java)
    )

    fun todo(context: Context): List<ComponentName> = listOf(
        ComponentName(context, TodoWidget::class.java)
    )

    fun exam(context: Context): List<ComponentName> = listOf(
        ComponentName(context, ExamCountdownWidget::class.java)
    )

    fun balance(context: Context): List<ComponentName> = listOf(
        ComponentName(context, BalanceWidget::class.java)
    )

    fun all(context: Context): List<ComponentName> =
        week(context) + day(context) + today(context) + todo(context) + exam(context) + balance(context)

    fun components(context: Context, kind: WidgetKind): List<ComponentName> = when (kind) {
        WidgetKind.WEEK -> week(context)
        WidgetKind.DAY -> day(context)
        WidgetKind.TODAY -> today(context)
        WidgetKind.TODO -> todo(context)
        WidgetKind.EXAM -> exam(context)
        WidgetKind.BALANCE -> balance(context)
    }

    /** 指定一组组件上已添加实例的 id。 */
    fun idsFor(components: List<ComponentName>, manager: AppWidgetManager): IntArray =
        components.flatMap { component ->
            runCatching { manager.getAppWidgetIds(component).toList() }.getOrDefault(emptyList())
        }.distinct().toIntArray()

    /** 所有已添加实例的 id（含旧组件）。 */
    fun allIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(all(context), manager)

    /**
     * 所有实例 id，用于清理已删除实例的配置。
     *
     * 任一组件查询抛异常就返回 null —— 调用方不能把「这次没查到」当成「用户删光了」，
     * 否则会一次性抹掉所有实例的标题 / 指定账号 / 尺寸。
     */
    fun allIdsOrNull(context: Context, manager: AppWidgetManager): Set<Int>? {
        val ids = LinkedHashSet<Int>()
        for (component in all(context)) {
            val part = runCatching { manager.getAppWidgetIds(component).toList() }.getOrNull()
                ?: return null
            ids.addAll(part)
        }
        return ids
    }

    fun weekIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(week(context), manager)

    fun dayIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(day(context), manager)

    fun todayIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(today(context), manager)

    fun todoIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(todo(context), manager)

    fun examIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(exam(context), manager)

    fun balanceIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(balance(context), manager)

    /** 通知全部小组件刷新（含旧组件名）。 */
    fun refreshAll(context: Context) {
        refreshComponents(context, all(context))
    }

    /** 仅通知「待办事项」小组件刷新（北化在线同步或过滤规则变更时调用）。 */
    fun refreshTodo(context: Context) {
        refreshComponents(context, todo(context))
    }

    fun refreshExam(context: Context) {
        refreshComponents(context, exam(context))
    }

    fun refreshBalance(context: Context) {
        refreshComponents(context, balance(context))
    }

    private fun refreshComponents(context: Context, components: List<ComponentName>) {
        components.forEach { component ->
            runCatching {
                context.sendBroadcast(
                    Intent().setComponent(component).setAction(ACTION_REFRESH)
                )
            }
        }
    }
}
