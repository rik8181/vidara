package com.rik.vidaraplus

import android.content.res.Resources
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/// Number and time formatting in the phone's language (for example "1.2K", "3 months ago").
object Fmt {
    lateinit var res: Resources

    private fun short(v: Double): String {
        if (v >= 10) return v.toInt().toString()
        val s = String.format(Locale.getDefault(), "%.1f", v)
        return s.removeSuffix(".0").removeSuffix(",0")
    }

    fun count(n: Long): String = when {
        n < 1000 -> n.toString()
        n < 1_000_000 -> res.getString(R.string.count_k, short(n / 1000.0))
        n < 1_000_000_000 -> res.getString(R.string.count_m, short(n / 1_000_000.0))
        else -> res.getString(R.string.count_b, short(n / 1_000_000_000.0))
    }

    fun views(n: Long): String = if (n <= 0) "" else res.getString(R.string.views_n, count(n))

    fun duration(seconds: Int): String {
        if (seconds <= 0) return ""
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%d:%02d", m, s)
    }

    /// „20240315” → „8 hónapja” / „8 months ago”
    fun age(date: String): String {
        if (date.length != 8) return ""
        return try {
            val d = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
            val days = ChronoUnit.DAYS.between(d, LocalDate.now()).toInt()
            when {
                days < 1 -> res.getString(R.string.age_today)
                days < 7 -> res.getQuantityString(R.plurals.age_days, days, days)
                days < 30 -> res.getQuantityString(R.plurals.age_weeks, days / 7, days / 7)
                days < 365 -> res.getQuantityString(R.plurals.age_months, days / 30, days / 30)
                else -> res.getQuantityString(R.plurals.age_years, days / 365, days / 365)
            }
        } catch (e: Exception) {
            ""
        }
    }

    fun subtitle(item: Item): String =
        listOf(item.channel, views(item.views), age(item.date)).filter { it.isNotEmpty() }.joinToString(" · ")
}
