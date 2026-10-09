package com.parchi.billing

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Runs all the time: watches the recording folder, hunts for the printer, listens to Telegram. */
class ParchiService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var lastText = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("run", "Parchi running", NotificationManager.IMPORTANCE_LOW))
        val n = note("Watching for new orders")
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(1, n)

        scope.launch {
            while (isActive) {
                try { Pipeline.scan(this@ParchiService) } catch (e: Exception) { Repo.note("Scan problem: ${e.message}") }
                delay(45_000)
            }
        }
        scope.launch {
            var waited = 0
            while (isActive) {
                val q = try { Repo.dao.printQueue().size } catch (e: Exception) { 0 }
                if (q > 0) {
                    val n2 = try { Printer.flush(this@ParchiService) } catch (e: Exception) { 0 }
                    if (n2 > 0) {
                        if (waited > 0) Tg.note("Printer found. $n2 bill${if (n2 == 1) "" else "s"} printed.")
                        waited = 0
                        update("Watching for new orders")
                    } else {
                        waited++
                        update("Searching for printer · $q bill${if (q == 1) "" else "s"} waiting")
                    }
                } else {
                    waited = 0
                    update("Watching for new orders")
                }
                withTimeoutOrNull(if (q > 0) 15_000L else 60_000L) { Repo.wake.first() }
            }
        }
        scope.launch {
            while (isActive) {
                try { Tg.poll() } catch (e: Exception) { delay(15_000) }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun note(text: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "run")
            .setContentTitle("Parchi")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_fg)
            .setOngoing(true)
            .setContentIntent(open)
            .build()
    }

    private fun update(text: String) {
        if (text == lastText) return
        lastText = text
        getSystemService(NotificationManager::class.java).notify(1, note(text))
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        if (i.action == Intent.ACTION_BOOT_COMPLETED) {
            Repo.init(c)
            Repo.startService(c)
        }
    }
}
