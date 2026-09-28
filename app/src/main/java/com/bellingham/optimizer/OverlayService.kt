package com.bellingham.optimizer

import android.app.ActivityManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.ComponentName
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import rikka.shizuku.Shizuku

class OverlayService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var overlayView: LinearLayout
    private lateinit var fpsText: TextView
    private lateinit var ramText: TextView
    private lateinit var tempText: TextView
    private lateinit var battText: TextView

    private var userService: IUserService? = null
    private val handler = Handler(Looper.getMainLooper())
    private var lastFrames = -1L
    private var lastTime = 0L
    private var targetPackage = ""

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = IUserService.Stub.asInterface(binder)
        }
        override fun onServiceDisconnected(name: ComponentName?) { userService = null }
    }

    override fun onCreate() {
        super.onCreate()
        startForegroundNotification()
        setupOverlay()
        bindShizuku()
        handler.post(updateRunnable)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        targetPackage = intent?.getStringExtra("package") ?: ""
        lastFrames = -1L
        return START_STICKY
    }

    private fun startForegroundNotification() {
        val channelId = "bellingham_overlay"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Bellingham Overlay", NotificationManager.IMPORTANCE_MIN)
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        }
        val notif = NotificationCompat.Builder(this, channelId)
            .setContentTitle("Bellingham activo")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
        startForeground(2001, notif)
    }

    private fun setupOverlay() {
        windowManager = getSystemService(WINDOW_SERVICE) as WindowManager

        overlayView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#DD1A1A1A"))
            setPadding(24, 18, 24, 18)
        }

        fpsText = makeLine("FPS: --")
        ramText = makeLine("RAM: --")
        tempText = makeLine("Temp: --")
        battText = makeLine("Batería: --")

        overlayView.addView(fpsText)
        overlayView.addView(ramText)
        overlayView.addView(tempText)
        overlayView.addView(battText)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else
                WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START
        params.x = 20
        params.y = 100

        overlayView.setOnTouchListener(object : View.OnTouchListener {
            var initialX = 0
            var initialY = 0
            var touchX = 0f
            var touchY = 0f
            override fun onTouch(v: View?, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        touchX = event.rawX
                        touchY = event.rawY
                    }
                    MotionEvent.ACTION_MOVE -> {
                        params.x = initialX + (event.rawX - touchX).toInt()
                        params.y = initialY + (event.rawY - touchY).toInt()
                        windowManager.updateViewLayout(overlayView, params)
                    }
                }
                return true
            }
        })

        windowManager.addView(overlayView, params)
    }

    private fun makeLine(text: String): TextView {
        return TextView(this).apply {
            this.text = text
            setTextColor(Color.parseColor("#D4AF37"))
            textSize = 12f
        }
    }

    private fun bindShizuku() {
        if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            val args = Shizuku.UserServiceArgs(ComponentName(packageName, UserService::class.java.name))
                .daemon(false)
                .processNameSuffix("overlay")
                .debuggable(false)
                .version(1)
            Shizuku.bindUserService(args, connection)
        }
    }

    private val updateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            handler.postDelayed(this, 1000)
        }
    }

    private fun updateStats() {
        val am = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val freeMb = mi.availMem / (1024 * 1024)
        val totalMb = mi.totalMem / (1024 * 1024)
        ramText.text = "RAM libre: ${freeMb}MB / ${totalMb}MB"

        val batIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = batIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val tempTenths = batIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) ?: -1
        battText.text = "Batería: $level%"
        tempText.text = "Temp: ${tempTenths / 10.0}°C"

        if (targetPackage.isNotEmpty()) {
            Thread {
                val frames = getRenderedFrames(targetPackage)
                val now = System.currentTimeMillis()
                if (lastFrames >= 0 && frames >= 0) {
                    val deltaFrames = frames - lastFrames
                    val deltaTime = (now - lastTime) / 1000.0
                    if (deltaTime > 0 && deltaFrames >= 0) {
                        val fps = (deltaFrames / deltaTime).toInt()
                        handler.post { fpsText.text = "FPS: $fps" }
                    }
                }
                lastFrames = frames
                lastTime = now
            }.start()
        }
    }

    private fun getRenderedFrames(pkg: String): Long {
        return try {
            val service = userService ?: return -1
            val output = service.execCommand("dumpsys gfxinfo $pkg")
            val regex = Regex("Total frames rendered:\\s*(\\d+)")
            regex.find(output)?.groupValues?.get(1)?.toLong() ?: -1
        } catch (e: Exception) {
            -1
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacks(updateRunnable)
        try { windowManager.removeView(overlayView) } catch (e: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
