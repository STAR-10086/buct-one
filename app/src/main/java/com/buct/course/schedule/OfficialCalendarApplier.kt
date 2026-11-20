package com.buct.course.schedule

import android.content.Context
import com.buct.course.manager.ScheduleSettingsManager
import com.buct.course.widget.WidgetProviders
import com.buct.one.data.model.PortalCalendarSnapshot
import com.buct.one.data.model.PortalTermInfo

/**
 * 将 i北化官方校历应用到各学期的时间基准与当前开学日期设置。
 * 若用户在设置中手工修改过某学期的开学日期，则自动同步时尊重用户的手工设置，
 * 仅在用户主动点击「校准」时（forceOverrideManual = true）强制恢复官方日期。
 */
object OfficialCalendarApplier {

    fun apply(
        context: Context,
        accountKey: String,
        snapshot: PortalCalendarSnapshot,
        forceOverrideManual: Boolean = false
    ): String? {
        val effectiveAccount = accountKey.ifBlank { "default" }
        val settingsManager = ScheduleSettingsManager.getInstance().apply { init(context) }
        val reminderScheduler = ScheduleReminderScheduler.get(context)

        val effectiveTerms = when {
            snapshot.terms.isNotEmpty() -> snapshot.terms
            snapshot.currentTermId.isNotBlank() && snapshot.beginDate.isNotBlank() -> {
                val year = snapshot.currentTermId.substringBefore('-').toIntOrNull() ?: 0
                val semester = snapshot.currentTermId.substringAfterLast('-').toIntOrNull() ?: 1
                listOf(
                    PortalTermInfo(
                        wid = "",
                        academicYear = year,
                        semester = semester,
                        termId = snapshot.currentTermId,
                        beginDate = snapshot.beginDate,
                        endDate = snapshot.endDate,
                        isCurrent = true
                    )
                )
            }
            else -> emptyList()
        }

        val defaultTimes = settingsManager.getDefaultPeriodTimes()
        val defaultStarts = defaultTimes.associate { it.period to it.startTime }
        val defaultEnds = defaultTimes.associate { it.period to it.endTime }

        var anyChanged = false
        var appliedCurrentDate: String? = null

        for (term in effectiveTerms) {
            if (term.termId.isBlank() || term.beginDate.isBlank()) continue
            val normalizedMonday = ScheduleDates.normalizeFirstWeekDate(term.beginDate) ?: continue

            if (forceOverrideManual) {
                settingsManager.clearManualSemesterStart(effectiveAccount, term.termId)
            }
            if (settingsManager.isManualSemesterStart(effectiveAccount, term.termId)) {
                continue
            }

            val existingBase = reminderScheduler.timeBase(effectiveAccount, term.termId)
            if (existingBase == null || existingBase.firstWeekDate != normalizedMonday) {
                val starts = existingBase?.periodStarts?.takeIf { it.isNotEmpty() } ?: defaultStarts
                val ends = existingBase?.periodEnds?.takeIf { it.isNotEmpty() } ?: defaultEnds
                reminderScheduler.updateTimeBase(
                    effectiveAccount,
                    term.termId,
                    ScheduleTimeBase(
                        firstWeekDate = normalizedMonday,
                        periodStarts = starts,
                        periodEnds = ends
                    )
                )
                anyChanged = true
            }

            val isCurrentTerm = term.isCurrent || term.termId == snapshot.currentTermId
            if (isCurrentTerm) {
                ScheduleDates.firstMonday(normalizedMonday)?.timeInMillis?.let { mondayMillis ->
                    settingsManager.applyAutoSemesterStart(effectiveAccount, mondayMillis)
                }
                appliedCurrentDate = normalizedMonday
            }
        }

        if (anyChanged) {
            context.getSharedPreferences("widget_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("last_week_nav", 0L)
                .apply()
            WidgetProviders.refreshAll(context)
        }

        return appliedCurrentDate ?: ScheduleDates.normalizeFirstWeekDate(snapshot.beginDate)
    }
}
