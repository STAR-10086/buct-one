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
 * 待办事项是后来加的，直接用新包名注册。
 */
object WidgetProviders {

    /** 统一刷新广播：三个小组件的 receiver 都要在 manifest 里声明这个 action。 */
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

    fun todo(context: Context): List<ComponentName> = listOf(
        ComponentName(context, TodoWidget::class.java)
    )

    fun all(context: Context): List<ComponentName> = week(context) + day(context) + todo(context)

    fun components(context: Context, kind: WidgetKind): List<ComponentName> = when (kind) {
        WidgetKind.WEEK -> week(context)
        WidgetKind.DAY -> day(context)
        WidgetKind.TODO -> todo(context)
    }

    /** 指定一组组件上已添加实例的 id。 */
    fun idsFor(components: List<ComponentName>, manager: AppWidgetManager): IntArray =
        components.flatMap { component ->
            runCatching { manager.getAppWidgetIds(component).toList() }.getOrDefault(emptyList())
        }.distinct().toIntArray()

    /** 所有已添加实例的 id（含旧组件）。 */
    fun allIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(all(context), manager)

    fun weekIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(week(context), manager)

    fun dayIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(day(context), manager)

    fun todoIds(context: Context, manager: AppWidgetManager): IntArray = idsFor(todo(context), manager)

    /** 通知全部小组件刷新（含旧组件名）。 */
    fun refreshAll(context: Context) {
        all(context).forEach { component ->
            runCatching {
                context.sendBroadcast(
                    Intent().setComponent(component).setAction(ACTION_REFRESH)
                )
            }
        }
    }
}
