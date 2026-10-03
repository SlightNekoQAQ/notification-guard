package io.github.slightneko.notificationguard.data

import android.content.Context
import android.os.Bundle
import java.time.LocalDate

 data class ChannelRow(val user: Int, val pkg: String, val channel: String, val app: String, val label: String, val importance: Int, val enabled: Boolean, val blocked: Boolean, val hidden: Boolean)
 data class StatRow(val user: Int, val pkg: String, val channel: String, val app: String, val label: String, val attempts: Long, val blocked: Long, val updates: Long)
 data class AppStat(val user: Int, val pkg: String, val app: String, val attempts: Long, val blocked: Long, val updates: Long)
 data class JobRow(val action: String, val status: String, val detail: String)
 data class UiSnapshot(val channels: List<ChannelRow> = emptyList(), val stats: List<StatRow> = emptyList(), val state: Map<String,String> = emptyMap(), val jobs: List<JobRow> = emptyList(), val backups: Int = 0)

fun aggregateApps(rows: List<StatRow>): List<AppStat> = rows.groupBy { it.user to it.pkg }.map { (key, group) ->
    AppStat(key.first, key.second, group.first().app, group.sumOf { it.attempts }, group.sumOf { it.blocked }, group.sumOf { it.updates })
}.sortedWith(compareByDescending<AppStat> { it.attempts }.thenBy { it.pkg })

class UiRepository(private val context: Context) {
    private fun <T> query(path: String, read: (android.database.Cursor) -> T): List<T> {
        val uri = GuardProvider.URI.buildUpon().appendPath(path.substringBefore('?')).apply {
            if ('?' in path) appendQueryParameter("since", path.substringAfter("since="))
        }.build()
        return context.contentResolver.query(uri, null, null, null, null)?.use { c -> buildList { while(c.moveToNext()) add(read(c)) } } ?: emptyList()
    }
    fun load(days: Int): UiSnapshot {
        fun android.database.Cursor.s(name: String) = getString(getColumnIndexOrThrow(name)) ?: ""
        fun android.database.Cursor.i(name: String) = getInt(getColumnIndexOrThrow(name))
        fun android.database.Cursor.l(name: String) = getLong(getColumnIndexOrThrow(name))
        val since = if (days == 0) "0000-00-00" else LocalDate.now().minusDays((days - 1).toLong()).toString()
        return UiSnapshot(
            query("channels") { c -> ChannelRow(c.i("user"),c.s("pkg"),c.s("channel"),c.s("app"),c.s("label"),c.i("importance"),c.i("enabled") != 0,c.i("blocked") != 0,c.i("hidden") != 0) },
            query("stats?since=$since") { c -> StatRow(c.i("user"),c.s("pkg"),c.s("channel"),c.s("app"),c.s("label"),c.l("attempts"),c.l("blocked"),c.l("updates")) },
            query("state") { c -> c.s("key") to c.s("value") }.toMap(),
            query("jobs") { c -> JobRow(c.s("action"),c.s("status"),c.s("detail")) },
            query("backup") { c -> c.i("count") }.firstOrNull() ?: 0,
        )
    }
    fun rule(row: ChannelRow, blocked: Boolean, hidden: Boolean) {
        val b = Bundle().apply { putInt("user",row.user); putString("pkg",row.pkg); putString("channel",row.channel); putBoolean("blocked",blocked); putBoolean("hidden",hidden) }
        context.contentResolver.call(GuardProvider.URI,"rule",null,b)
    }
    fun request(action: String) { context.contentResolver.call(GuardProvider.URI,"request",action,null) }
    fun clearStats() { context.contentResolver.call(GuardProvider.URI,"clearStats",null,null) }
}
