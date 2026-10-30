package com.pdfmanager.app.printing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.print.PrintJob
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.pdfmanager.app.MainActivity
import com.pdfmanager.app.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** The print job the service should follow. Set by the print screen right after the job starts. */
object PrintJobRegistry {
    @Volatile var job: PrintJob? = null
    @Volatile var label: String = ""
    /** True for guided two-sided printing (a [PrintSession] exists). */
    @Volatile var guided: Boolean = false
}

/**
 * Keeps following a print job after the app screen is closed or the app is left, and shows
 * the progress as a notification with a Cancel button. It never cancels a job by itself:
 * only the Cancel button here, or Android's own print notification, does that.
 *
 * It can only tell what the print system reports. If the printer or its print service does not
 * report "finished", no "finished" notification is shown (the print screen then still lets the
 * user start the next pass by hand).
 */
class PrintWatchService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var watching: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        PrintSessionStore.init(applicationContext)
        ensureChannels()
        if (intent?.action == ACTION_CANCEL) {
            runCatching { PrintJobRegistry.job?.cancel() }
            return START_NOT_STICKY
        }
        val job = PrintJobRegistry.job
        ServiceCompat.startForeground(
            this, ID_PROGRESS, progress("Waiting for the Android print screen..."),
            if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
        if (job == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        watching?.cancel()
        watching = scope.launch {
            val status = PrintJobWatcher.track(job) { s ->
                val text = when (s) {
                    JobStatus.BLOCKED -> "The printer needs attention (paper, ink or connection)."
                    else -> "Printing in the background. You can leave the app."
                }
                notify(ID_PROGRESS, progress(text))
            }
            finish(status)
        }
        return START_NOT_STICKY
    }

    private fun finish(status: JobStatus?) {
        val guided = PrintJobRegistry.guided
        val phase = PrintSessionStore.current()?.phase ?: SessionPhase.NONE
        val name = PrintJobRegistry.label.ifBlank { "PDF" }
        if (status != null) {
            val next = if (guided) SessionPhase.after(phase, status) else phase
            if (guided) PrintSessionStore.setPhase(next)
            when {
                status == JobStatus.COMPLETED && guided && next == SessionPhase.READY_2 -> notify(
                    ID_RESULT,
                    alert(
                        "Step 1 finished: $name",
                        "Turn the printed paper over and put it back in the tray. Then tap \"Print step 2\".",
                        openApp = true, autoStart = true, actionText = "Print step 2"
                    )
                )
                status == JobStatus.COMPLETED && guided -> notify(
                    ID_RESULT, alert("Printing finished: $name", "Both sides are printed.", openApp = true)
                )
                status == JobStatus.COMPLETED -> notify(
                    ID_RESULT, alert("Printing finished: $name", "The print service reports the job as finished.", openApp = false)
                )
                status == JobStatus.FAILED -> notify(
                    ID_RESULT,
                    alert(
                        "Printing failed: $name",
                        if (guided) "Open the app to continue from where it stopped." else "Check the printer, then try again.",
                        openApp = guided
                    )
                )
                status == JobStatus.CANCELLED -> notify(
                    ID_RESULT,
                    alert(
                        "Printing cancelled: $name",
                        if (guided) "Open the app to print this step again or continue where it stopped." else "The job was cancelled.",
                        openApp = guided
                    )
                )
            }
            if (guided && next == SessionPhase.NONE) PrintSessionStore.clear()
        }
        PrintJobRegistry.job = null
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    // ---- notifications ----

    private fun ensureChannels() {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROGRESS, "Printing progress", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RESULT, "Printing results and next steps", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openIntent(autoStart: Boolean, code: Int): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_OPEN_PRINT, true)
            putExtra(EXTRA_AUTO_START, autoStart)
        }
        return PendingIntent.getActivity(this, code, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun progress(text: String): Notification {
        val guided = PrintJobRegistry.guided
        val phase = PrintSessionStore.current()?.phase
        val title = when {
            guided && phase == SessionPhase.PRINTING_2 -> "Step 2 of 2 (front sides): ${PrintJobRegistry.label}"
            guided -> "Step 1 of 2 (back sides): ${PrintJobRegistry.label}"
            else -> "Printing: ${PrintJobRegistry.label.ifBlank { "PDF" }}"
        }
        val cancel = PendingIntent.getService(
            this, 1, Intent(this, PrintWatchService::class.java).setAction(ACTION_CANCEL),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_PROGRESS)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openIntent(false, 2))
            .addAction(0, "Cancel printing", cancel)
            .build()
    }

    private fun alert(title: String, text: String, openApp: Boolean, autoStart: Boolean = false, actionText: String? = null): Notification {
        val b = NotificationCompat.Builder(this, CHANNEL_RESULT)
            .setSmallIcon(R.drawable.ic_stat_print)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
        if (openApp) b.setContentIntent(openIntent(false, 3))
        if (autoStart && actionText != null) b.addAction(0, actionText, openIntent(true, 4))
        return b.build()
    }

    @SuppressLint("MissingPermission")
    private fun notify(id: Int, n: Notification) {
        // Without the notification permission nothing is shown; printing itself is not affected.
        val allowed = Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (allowed) runCatching { NotificationManagerCompat.from(this).notify(id, n) }
    }

    companion object {
        const val ACTION_CANCEL = "com.pdfmanager.app.PRINT_CANCEL"
        const val EXTRA_OPEN_PRINT = "open_print_session"
        const val EXTRA_AUTO_START = "print_auto_start"
        private const val CHANNEL_PROGRESS = "print_progress"
        private const val CHANNEL_RESULT = "print_result"
        private const val ID_PROGRESS = 4101
        private const val ID_RESULT = 4102

        /** Starts following [PrintJobRegistry.job]. Call it while the app is in front. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(context.applicationContext, Intent(context.applicationContext, PrintWatchService::class.java))
        }
    }
}
