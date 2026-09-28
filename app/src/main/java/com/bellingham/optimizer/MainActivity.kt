package com.bellingham.optimizer

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.Settings
import android.widget.SeekBar
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bellingham.optimizer.databinding.ActivityMainBinding
import rikka.shizuku.Shizuku

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private var userService: IUserService? = null
    private val REQUEST_CODE = 1001

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            userService = IUserService.Stub.asInterface(binder)
            runOnUiThread { binding.statusText.text = "Shizuku conectado ✅" }
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            userService = null
            runOnUiThread { binding.statusText.text = "Shizuku desconectado" }
        }
    }

    private val permissionListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            if (requestCode == REQUEST_CODE) {
                if (grantResult == PackageManager.PERMISSION_GRANTED) {
                    bindUserService()
                } else {
                    Toast.makeText(this, "Permiso de Shizuku denegado", Toast.LENGTH_SHORT).show()
                }
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        Shizuku.addRequestPermissionResultListener(permissionListener)

        binding.connectButton.setOnClickListener { checkAndBind() }

        // Modo rápido
        binding.btnModoGamer.setOnClickListener { modoGamer() }
        binding.btnModoNormal.setOnClickListener { modoNormal() }

        // Elegir juego
        binding.btnElegirJuego.setOnClickListener { showAppPicker() }

        // Modo Juego por app
        binding.btnGameMedio.setOnClickListener { gameMode("0.7", "60") }
        binding.btnGameMax.setOnClickListener { gameMode("0.5", "30") }
        binding.btnCompilar.setOnClickListener {
            val pkg = getPackageName2() ?: return@setOnClickListener
            runCommand("cmd package compile -m speed-profile $pkg")
        }
        binding.btnGameReset.setOnClickListener {
            val pkg = getPackageName2() ?: return@setOnClickListener
            runCommand("cmd game reset $pkg")
        }

        // Limitador de FPS (tasa de refresco)
        binding.btnFps30.setOnClickListener { setRefresh("30.0") }
        binding.btnFps60.setOnClickListener { setRefresh("60.0") }
        binding.btnFps90.setOnClickListener { setRefresh("90.0") }
        binding.btnFpsReset.setOnClickListener {
            runCommand("settings delete system min_refresh_rate; settings delete system peak_refresh_rate")
        }

        // Resolución
        binding.btnOriginal.setOnClickListener { runCommand("wm size reset") }
        binding.btnMedia.setOnClickListener { runCommand("wm size 720x1280") }
        binding.btnBaja.setOnClickListener { runCommand("wm size 540x960") }
        binding.btnUltraBaja.setOnClickListener { runCommand("wm size 480x854") }

        // DPI slider (rango real 160 a 480)
        val currentDpi = resources.displayMetrics.densityDpi
        binding.dpiSlider.progress = (currentDpi - 160).coerceIn(0, 320)
        binding.dpiLabel.text = "Densidad (DPI): $currentDpi"
        binding.dpiSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                binding.dpiLabel.text = "Densidad (DPI): ${160 + progress}"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                val dpi = 160 + (seekBar?.progress ?: 0)
                runCommand("wm density $dpi")
            }
        })

        // Overlay
        binding.btnOverlayStart.setOnClickListener { startOverlay() }
        binding.btnOverlayStop.setOnClickListener {
            stopService(Intent(this, OverlayService::class.java))
        }

        // Animaciones
        binding.btnAnimOff.setOnClickListener {
            runCommand("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
        }
        binding.btnAnimOn.setOnClickListener {
            runCommand("settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1")
        }

        // RAM
        binding.btnRam.setOnClickListener {
            runCommand("for p in \$(pm list packages -3 | sed 's/package://'); do am force-stop \$p; done")
        }

        checkAndBind()
    }

    private fun modoGamer() {
        runCommand("wm size 540x960; settings put system min_refresh_rate 60.0; settings put system peak_refresh_rate 60.0; settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0; for p in \$(pm list packages -3 | sed 's/package://'); do am force-stop \$p; done")
        Toast.makeText(this, "Modo Gamer activado ⚡", Toast.LENGTH_SHORT).show()
    }

    private fun modoNormal() {
        runCommand("wm size reset; settings delete system min_refresh_rate; settings delete system peak_refresh_rate; settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1")
        Toast.makeText(this, "Modo Normal restaurado", Toast.LENGTH_SHORT).show()
    }

    private fun showAppPicker() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val labels = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val packages = apps.map { it.packageName }

        AlertDialog.Builder(this)
            .setTitle("Elige un juego")
            .setItems(labels) { _, i ->
                binding.etPackage.setText(packages[i])
            }
            .show()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Dale permiso de superposición y vuelve a tocar", Toast.LENGTH_LONG).show()
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
            return
        }
        val pkg = binding.etPackage.text.toString().trim()
        val intent = Intent(this, OverlayService::class.java)
        intent.putExtra("package", pkg)
        startService(intent)
        Toast.makeText(this, "Overlay activo, minimiza y abre tu juego", Toast.LENGTH_LONG).show()
    }

    private fun setRefresh(hz: String) {
        runCommand("settings put system min_refresh_rate $hz; settings put system peak_refresh_rate $hz")
    }

    private fun gameMode(downscale: String, fps: String) {
        val pkg = getPackageName2() ?: return
        runCommand("cmd game mode performance $pkg; cmd game set --mode performance --downscale $downscale --fps $fps $pkg")
    }

    private fun getPackageName2(): String? {
        val pkg = binding.etPackage.text.toString().trim()
        if (pkg.isEmpty() || !Regex("^[A-Za-z0-9._]+$").matches(pkg)) {
            Toast.makeText(this, "Paquete inválido", Toast.LENGTH_SHORT).show()
            return null
        }
        return pkg
    }

    private fun checkAndBind() {
        if (!Shizuku.pingBinder()) {
            binding.statusText.text = "Abre la app Shizuku e inícialo primero"
            return
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bindUserService()
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
        }
    }

    private fun bindUserService() {
        val args = Shizuku.UserServiceArgs(
            ComponentName(packageName, UserService::class.java.name)
        )
            .daemon(false)
            .processNameSuffix("service")
            .debuggable(false)
            .version(1)
        Shizuku.bindUserService(args, connection)
    }

    private fun runCommand(cmd: String) {
        val service = userService
        if (service == null) {
            Toast.makeText(this, "Conecta Shizuku primero", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val result = try {
                service.execCommand(cmd).trim()
            } catch (e: Exception) {
                "Error: ${e.message}"
            }
            runOnUiThread {
                val msg = if (result.isEmpty()) "Listo ✅" else result.take(150)
                Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}
