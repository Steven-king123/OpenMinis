package com.openminis.app.memory

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.json.JSONObject

/** Durable queue contains only explicit current-session completion IDs, epochs and debounce tokens. */
internal object AdvancedMemoryIdleScheduler {
    private const val PREFS = "advanced_memory_idle"
    private const val PREFIX = "pending:"
    private const val ACTION = "com.openminis.app.memory.IDLE"
    private val lock = Any()
    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private fun alarm(context: Context) = context.getSystemService(AlarmManager::class.java)
    private fun jobId(sessionId: String): Int = 0x4D000000 or (sessionId.hashCode() and 0x00FFFFFF)
    private fun pending(context: Context, sessionId: String): PendingIntent = PendingIntent.getBroadcast(
        context, 0, Intent(context, AdvancedMemoryIdleReceiver::class.java).setAction(ACTION)
            .setData(Uri.Builder().scheme("minis-memory").authority("idle").appendPath(sessionId).build())
            .putExtra("sessionId", sessionId), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    fun schedule(context: Context, sessionId: String, config: AdvancedMemoryConfig) = synchronized(lock) {
        if (sessionId.isBlank() || sessionId.length > 256) return@synchronized
        val due = System.currentTimeMillis() + config.idleMinutes.coerceIn(1, 240) * 60_000L
        val row = JSONObject().put("token", UUID.randomUUID().toString()).put("epoch", config.epoch).put("due", due)
        // commit before AlarmManager: a process kill can never expose an unpersisted token.
        check(prefs(context).edit().putString(PREFIX + sessionId, row.toString()).commit())
        context.getSystemService(JobScheduler::class.java).cancel(jobId(sessionId))
        alarm(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending(context, sessionId))
    }

    fun cancel(context: Context, sessionId: String) = synchronized(lock) {
        prefs(context).edit().remove(PREFIX + sessionId).commit()
        alarm(context).cancel(pending(context, sessionId))
        context.getSystemService(JobScheduler::class.java).cancel(jobId(sessionId))
    }

    private fun row(context: Context, sessionId: String): JSONObject? =
        prefs(context).getString(PREFIX + sessionId, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    fun isCurrent(context: Context, sessionId: String, token: String): Boolean = synchronized(lock) {
        row(context, sessionId)?.optString("token") == token
    }

    /** Called by receiver: no cloud request lives inside BroadcastReceiver.goAsync's short lifetime. */
    fun dispatch(context: Context, sessionId: String) = synchronized(lock) {
        val row = row(context, sessionId) ?: return@synchronized
        val config = AdvancedMemoryRuntime.store(context).config.value
        if (!config.enabled || !config.autoConsolidate || config.epoch != row.optLong("epoch")) {
            cancel(context, sessionId); return@synchronized
        }
        val due = row.optLong("due")
        if (due > System.currentTimeMillis()) {
            alarm(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, due, pending(context, sessionId))
            return@synchronized
        }
        val extras = PersistableBundle().apply {
            putString("sessionId", sessionId)
            putString("token", row.optString("token"))
            putLong("epoch", row.optLong("epoch"))
        }
        // JobService provides a real OS-managed execution window, network constraint and cancellation.
        val scheduler = context.getSystemService(JobScheduler::class.java)
        val installed = scheduler.getPendingJob(jobId(sessionId))
        if (installed?.extras?.getString("token") == row.optString("token")) return@synchronized
        val job = JobInfo.Builder(jobId(sessionId), ComponentName(context, AdvancedMemoryIdleJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setExtras(extras)
            .setMinimumLatency(0).setPersisted(true).build()
        if (context.getSystemService(JobScheduler::class.java).schedule(job) != JobScheduler.RESULT_SUCCESS)
            AdvancedMemoryRuntime.report("Android refused idle memory work; check background restrictions.")
    }

    fun finish(context: Context, sessionId: String, token: String) = synchronized(lock) {
        if (isCurrent(context, sessionId, token)) prefs(context).edit().remove(PREFIX + sessionId).commit()
    }

    /** Boot/package replacement restores ONLY the previously scheduled IDs, never queries chat history. */
    fun restore(context: Context) = synchronized(lock) {
        val now = System.currentTimeMillis()
        for ((key, value) in prefs(context).all) {
            if (!key.startsWith(PREFIX) || value !is String) continue
            val sessionId = key.removePrefix(PREFIX)
            val row = runCatching { JSONObject(value) }.getOrNull() ?: continue
            val config = AdvancedMemoryRuntime.store(context).config.value
            if (!config.enabled || !config.autoConsolidate || config.epoch != row.optLong("epoch")) {
                cancel(context, sessionId); continue
            }
            alarm(context).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                maxOf(now + 30_000L, row.optLong("due")), pending(context, sessionId))
        }
    }
}

class AdvancedMemoryIdleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        AdvancedMemoryRuntime.scope.launch {
            try {
                when (intent.action) {
                    Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> AdvancedMemoryIdleScheduler.restore(app)
                    else -> intent.getStringExtra("sessionId")?.let { AdvancedMemoryIdleScheduler.dispatch(app, it) }
                }
            } catch (e: Exception) {
                AdvancedMemoryRuntime.report("Idle memory scheduling failed; check Android background settings.")
            } finally { pending.finish() }
        }
    }
}

class AdvancedMemoryIdleJobService : JobService() {
    private val jobs = ConcurrentHashMap<Int, Job>()
    override fun onStartJob(params: JobParameters): Boolean {
        val sessionId = params.extras.getString("sessionId") ?: return false
        val token = params.extras.getString("token") ?: return false
        val epoch = params.extras.getLong("epoch")
        if (!AdvancedMemoryIdleScheduler.isCurrent(this, sessionId, token)) return false
        val job = AdvancedMemoryRuntime.scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            val self = kotlinx.coroutines.currentCoroutineContext()[Job]!!
            try {
                AdvancedMemoryRuntime.summarizeScheduled(applicationContext, sessionId, epoch, token)
                AdvancedMemoryIdleScheduler.finish(applicationContext, sessionId, token)
            } finally {
                if (jobs.remove(params.jobId, self)) jobFinished(params, false)
            }
        }
        jobs.put(params.jobId, job)?.cancel()
        job.start()
        return true
    }
    override fun onStopJob(params: JobParameters): Boolean {
        jobs.remove(params.jobId)?.cancel()
        // Leave durable token intact for the platform retry; no duplicate commit on stale epochs.
        return true
    }
    override fun onDestroy() {
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        super.onDestroy()
    }
}
