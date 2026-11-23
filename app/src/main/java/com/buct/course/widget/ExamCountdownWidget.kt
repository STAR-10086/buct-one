package com.buct.course.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import com.buct.course.MainActivity
import com.buct.course.R
import com.buct.course.manager.CustomExamStore
import com.buct.course.manager.StartupPage
import com.buct.course.manager.UserManager
import org.json.JSONArray
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 桌面「考试倒计时」小组件（最小 2×1）：
 * 合并本地自建考试（CustomExamStore）与教务考试缓存（exam_cache），展示最近一场待考科目与倒计时。
 */
class ExamCountdownWidget : AppWidgetProvider() {

    private data class UpcomingExam(
        val name: String,
        val startAtMillis: Long,
        val timeText: String,
        val location: String
    )

    companion object {
        private const val HEADER_MIN_HEIGHT_DP = 76

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val size = WidgetSizePolicy.resolve(
                context,
                appWidgetManager.getAppWidgetOptions(appWidgetId),
                appWidgetId
            )
            appWidgetManager.updateAppWidget(appWidgetId, buildViews(context, size, appWidgetId))
        }

        private fun buildViews(
            context: Context,
            size: WidgetSize,
            appWidgetId: Int
        ): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_exam_countdown)
            views.setInt(R.id.exam_widget_root, "setBackgroundResource", WidgetAppearance.backgroundRes(context))

            val hideTitle = WidgetConfigStore.hideTitle(context, appWidgetId)
            val showHeader = !hideTitle && size.heightDp >= HEADER_MIN_HEIGHT_DP
            views.setViewVisibility(R.id.exam_widget_header, if (showHeader) View.VISIBLE else View.GONE)

            val nextExam = loadNearestExam(context)
            val countdownBadge = nextExam?.let { formatCountdown(it.startAtMillis) }.orEmpty()

            if (showHeader) {
                views.setTextViewText(
                    R.id.exam_widget_title,
                    WidgetConfigStore.resolveTitle(context, appWidgetId, WidgetKind.EXAM)
                )
                views.setTextColor(R.id.exam_widget_title, WidgetAppearance.textColor(context))
                views.setTextViewText(R.id.exam_widget_badge, countdownBadge)
                views.setTextColor(R.id.exam_widget_badge, 0xFF0A84FF.toInt())

                val configIntent = Intent(
                    context,
                    com.buct.course.ui.widget.WidgetConfigActivity::class.java
                ).apply {
                    putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                    putExtra(
                        com.buct.course.ui.widget.WidgetConfigActivity.EXTRA_WIDGET_KIND,
                        WidgetKind.EXAM.name
                    )
                    data = Uri.parse("buctwidget://config/exam/$appWidgetId")
                }
                views.setOnClickPendingIntent(
                    R.id.exam_widget_header,
                    PendingIntent.getActivity(
                        context,
                        appWidgetId + 4000,
                        configIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                )
            }

            if (nextExam == null) {
                views.setTextViewText(R.id.exam_widget_name, "近期没有考试")
                views.setTextColor(R.id.exam_widget_name, WidgetAppearance.secondaryTextColor(context))
                views.setTextViewText(R.id.exam_widget_detail, "可在课表页添加或同步考试")
                views.setTextColor(R.id.exam_widget_detail, WidgetAppearance.secondaryTextColor(context))
            } else {
                val titleText = if (showHeader || countdownBadge.isBlank()) {
                    nextExam.name
                } else {
                    "${nextExam.name} · $countdownBadge"
                }
                val detail = listOf(nextExam.timeText, nextExam.location)
                    .filter { it.isNotBlank() }
                    .joinToString(" · ")
                views.setTextViewText(R.id.exam_widget_name, titleText)
                views.setTextColor(R.id.exam_widget_name, WidgetAppearance.textColor(context))
                views.setTextViewText(R.id.exam_widget_detail, detail)
                views.setTextColor(R.id.exam_widget_detail, WidgetAppearance.secondaryTextColor(context))
            }

            val openIntent = Intent(context, MainActivity::class.java)
                .setData(Uri.parse("buctwidget://exam/open/$appWidgetId"))
                .putExtra(MainActivity.EXTRA_TARGET_TAB, StartupPage.Schedule.name)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            views.setOnClickPendingIntent(
                R.id.exam_widget_root,
                PendingIntent.getActivity(
                    context,
                    appWidgetId + 4500,
                    openIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )

            WidgetTextSizes.apply(views, WidgetTextSizes.EXAM_ROOT, WidgetTextSizes.scale(context))
            return views
        }

        private fun loadNearestExam(context: Context): UpcomingExam? {
            runCatching { UserManager.getInstance().init(context) }
            val now = System.currentTimeMillis()
            val list = mutableListOf<UpcomingExam>()
            val timeFmt = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA)

            runCatching {
                CustomExamStore.list(context)
                    .filter { !it.hidden && it.startAt > now - 3_600_000L }
                    .forEach { exam ->
                        list += UpcomingExam(
                            name = exam.title,
                            startAtMillis = exam.startAt,
                            timeText = timeFmt.format(Date(exam.startAt)),
                            location = exam.location
                        )
                    }
            }

            runCatching {
                val raw = context.getSharedPreferences("exam_cache", Context.MODE_PRIVATE)
                    .getString("exams", null) ?: return@runCatching
                val arr = JSONArray(raw)
                val parser = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA)
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val rawTime = o.optString("examTime", "").trim()
                    val datePart = rawTime.take(10)
                    val timeStart = rawTime.drop(10).trim().split('-', '~', '–', '至').firstOrNull()?.trim().orEmpty()
                    val startMillis = runCatching {
                        if (datePart.length == 10 && timeStart.length >= 4) {
                            parser.parse("$datePart $timeStart")?.time
                        } else {
                            null
                        }
                    }.getOrNull() ?: continue
                    if (startMillis <= now - 3_600_000L) continue
                    val loc = listOf(o.optString("location", ""), o.optString("seatNumber", ""))
                        .filter { it.isNotBlank() }
                        .joinToString(" ")
                    list += UpcomingExam(
                        name = o.optString("courseName", "考试").ifBlank { "考试" },
                        startAtMillis = startMillis,
                        timeText = timeFmt.format(Date(startMillis)),
                        location = loc
                    )
                }
            }

            return list.minByOrNull { it.startAtMillis }
        }

        private fun formatCountdown(targetMillis: Long): String {
            val nowCal = Calendar.getInstance()
            val targetCal = Calendar.getInstance().apply { timeInMillis = targetMillis }
            val startOfToday = (nowCal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val startOfTarget = (targetCal.clone() as Calendar).apply {
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }.timeInMillis
            val days = ((startOfTarget - startOfToday) / 86_400_000L).toInt()
            return when {
                days <= 0 -> {
                    val hours = max(0L, (targetMillis - System.currentTimeMillis()) / 3_600_000L)
                    if (hours == 0L) "即将开考" else "今天 · ${hours}h"
                }
                days == 1 -> "明天"
                else -> "还有 $days 天"
            }
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == WidgetProviders.ACTION_REFRESH) {
            val manager = AppWidgetManager.getInstance(context)
            onUpdate(context, manager, WidgetProviders.examIds(context, manager))
        }
    }

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            WidgetConfigStore.clear(context, appWidgetId)
        }
    }
}
