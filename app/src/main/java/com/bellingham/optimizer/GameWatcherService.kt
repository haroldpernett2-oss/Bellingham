package com.bellingham.optimizer

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku

class GameWatcherService : Service() {

    private var userService: IUserService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var targetPackage = ""
    private var profileName = ""
    private var isApplied = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = IUserService.Stub.asInterface(binder)
        }
        override fun onServiceDisconnected(name: ComponentName?) { userService = null }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        bindShizuku()
        handler.post(watchRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        targetPackage = intent?.getStringExtra("package") ?: ""
        profileName = intent?.getStringExtra("profile") ?: ""
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "victory_hard_watcher"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Victory Hard Auto", NotificationManager.IMPORTANCE_MIN)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Victory Hard vigilando")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(3001, notif)
    }

    private fun bindShizuku() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            val args = Shizuku.UserServiceArgs(ComponentName(packageName, UserService::class.java.name))
                .daemon(false).processNameSuffix("watcher").debuggable(false).version(1)
            Shizuku.bindUserService(args, connection)
        }
    }

    private val watchRunnable = object : Runnable {
        override fun run() {
            checkForeground()
            handler.postDelayed(this, 2000)
        }
    }

    private fun checkForeground() {
        val service = userService ?: return
        if (targetPackage.isEmpty()) return
        Thread {
            try {
                val output = service.execCommand("dumpsys activity activities | grep mResumedActivity")
                val isGameOpen = output.contains(targetPackage)
                if (isGameOpen && !isApplied) {
                    applyProfile(service)
                    isApplied = true
                } else if (!isGameOpen && isApplied) {
                    service.execCommand(ProfileStorage.NORMAL_COMMAND)
                    isApplied = false
                }
            } catch (e: Exception) { }
        }.start()
    }

    private fun applyProfile(service: IUserService) {
        val profile = ProfileStorage.getAll(this).find { it.name == profileName } ?: return
        service.execCommand(ProfileStorage.buildApplyCommand(profile))
        service.execCommand(ProfileStorage.buildGameModeCommand(targetPackage, profile))
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(watchRunnable)
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
