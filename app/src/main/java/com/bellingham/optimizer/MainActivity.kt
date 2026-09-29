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
import android.widget.ArrayAdapter
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

        binding.btnModoGamer.setOnClickListener { modoGamer() }
        binding.btnModoNormal.setOnClickListener { modoNormal() }

        binding.btnElegirJuego.setOnClickListener { showAppPicker() }
        binding.btnCompilar.setOnClickListener {
            val pkg = getPackageName2() ?: return@setOnClickListener
            runCommand("cmd package compile -m speed-profile $pkg")
        }
        binding.btnGameReset.setOnClickListener {
            val pkg = getPackageName2() ?: return@setOnClickListener
            runCommand("cmd game reset $pkg")
        }

        binding.btnOriginal?.let { }
        binding.btnOverlayStart.setOnClickListener { startOverlay() }
        binding.btnOverlayStop.setOnClickListener { stopService(Intent(this, OverlayService::class.java)) }

        binding.btnAnimOff.setOnClickListener {
            runCommand("settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0")
        }
        binding.btnAnimOn.setOnClickListener {
            runCommand("settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1")
        }
        binding.btnRam.setOnClickListener {
            runCommand("for p in \$(pm list packages -3 | sed 's/package://'); do am force-stop \$p; done")
        }

        // Perfiles
        binding.btnSaveProfile.setOnClickListener { saveCurrentProfile() }
        binding.btnApplyProfile.setOnClickListener { applySelectedProfile() }
        binding.btnDeleteProfile.setOnClickListener { deleteSelectedProfile() }

        // Auto-activación
        binding.btnAutoStart.setOnClickListener { startAutoActivation() }
        binding.btnAutoStop.setOnClickListener { stopService(Intent(this, GameWatcherService::class.java)) }

        // Burbuja WhatsApp
        binding.btnBubbleStart.setOnClickListener { startBubble() }
        binding.btnBubbleStop.setOnClickListener { stopService(Intent(this, WhatsAppBubbleService::class.java)) }

        refreshProfileSpinners()
        checkAndBind()
    }

    // ---------- MODO RÁPIDO ----------
    private fun modoGamer() {
        runCommand("wm size 540x960; settings put system min_refresh_rate 60.0; settings put system peak_refresh_rate 60.0; settings put global window_animation_scale 0; settings put global transition_animation_scale 0; settings put global animator_duration_scale 0; for p in \$(pm list packages -3 | sed 's/package://'); do am force-stop \$p; done")
        Toast.makeText(this, "Modo Gamer activado", Toast.LENGTH_SHORT).show()
    }

    private fun modoNormal() {
        runCommand(ProfileStorage.NORMAL_COMMAND)
        Toast.makeText(this, "Modo Normal restaurado", Toast.LENGTH_SHORT).show()
    }

    // ---------- PERFILES ----------
    private fun saveCurrentProfile() {
        val name = binding.etProfileName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Ponle un nombre al perfil", Toast.LENGTH_SHORT).show()
            return
        }

        val resolution = when (binding.radioGroupResolution.checkedRadioButtonId) {
            binding.radioRes720.id -> "720x1280"
            binding.radioRes540.id -> "540x960"
            binding.radioRes480.id -> "480x854"
            else -> "original"
        }

        val refresh = when (binding.radioGroupRefresh.checkedRadioButtonId) {
            binding.radioRefresh30.id -> "30"
            binding.radioRefresh60.id -> "60"
            binding.radioRefresh90.id -> "90"
            else -> "auto"
        }

        val useGameMode = binding.checkGameMode.isChecked
        val downscale: String
        val fps: String
        if (useGameMode) {
            if (binding.radioGroupGameMode.checkedRadioButtonId == binding.radioGameMax.id) {
                downscale = "0.5"; fps = "30"
            } else {
                downscale = "0.7"; fps = "60"
            }
        } else {
            downscale = "1.0"; fps = "60"
        }

        val profile = GameProfile(
            name = name,
            resolution = resolution,
            refreshHz = refresh,
            downscale = downscale,
            fps = fps,
            animOff = binding.checkAnimOffProfile.isChecked,
            cleanRam = binding.checkRamProfile.isChecked
        )
        ProfileStorage.save(this, profile)
        Toast.makeText(this, "Perfil \"$name\" guardado", Toast.LENGTH_SHORT).show()
        refreshProfileSpinners()
    }

    private fun refreshProfileSpinners() {
        val names = ProfileStorage.getAll(this).map { it.name }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerProfiles.adapter = adapter

        val adapter2 = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        binding.spinnerAutoProfile.adapter = adapter2
    }

    private fun applySelectedProfile() {
        val name = binding.spinnerProfiles.selectedItem as? String ?: run {
            Toast.makeText(this, "No hay perfiles guardados", Toast.LENGTH_SHORT).show()
            return
        }
        val profile = ProfileStorage.getAll(this).find { it.name == name } ?: return
        runCommand(ProfileStorage.buildApplyCommand(profile))
        val pkg = binding.etPackage.text.toString().trim()
        if (pkg.isNotEmpty() && profile.downscale != "1.0") {
            runCommand(ProfileStorage.buildGameModeCommand(pkg, profile))
        }
        Toast.makeText(this, "Perfil \"$name\" aplicado", Toast.LENGTH_SHORT).show()
    }

    private fun deleteSelectedProfile() {
        val name = binding.spinnerProfiles.selectedItem as? String ?: return
        ProfileStorage.delete(this, name)
        Toast.makeText(this, "Perfil \"$name\" eliminado", Toast.LENGTH_SHORT).show()
        refreshProfileSpinners()
    }

    // ---------- AUTO-ACTIVACIÓN ----------
    private fun startAutoActivation() {
        val pkg = getPackageName2() ?: return
        val profileName = binding.spinnerAutoProfile.selectedItem as? String
        if (profileName == null) {
            Toast.makeText(this, "Crea y elige un perfil primero", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, GameWatcherService::class.java)
        intent.putExtra("package", pkg)
        intent.putExtra("profile", profileName)
        startService(intent)
        Toast.makeText(this, "Auto-activación encendida para $pkg", Toast.LENGTH_SHORT).show()
    }

    // ---------- BURBUJA WHATSAPP ----------
    private fun startBubble() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Dale permiso de superposición y vuelve a tocar", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        if (packageManager.getLaunchIntentForPackage("com.whatsapp") == null) {
            Toast.makeText(this, "No encontré WhatsApp instalado", Toast.LENGTH_SHORT).show()
            return
        }
        startService(Intent(this, WhatsAppBubbleService::class.java))
        Toast.makeText(this, "Burbuja activa, minimiza y abre tu juego", Toast.LENGTH_LONG).show()
    }

    // ---------- OTROS ----------
    private fun showAppPicker() {
        val pm = packageManager
        val apps = pm.getInstalledApplications(0)
            .filter { pm.getLaunchIntentForPackage(it.packageName) != null }
            .sortedBy { pm.getApplicationLabel(it).toString() }

        val labels = apps.map { pm.getApplicationLabel(it).toString() }.toTypedArray()
        val packages = apps.map { it.packageName }

        AlertDialog.Builder(this)
            .setTitle("Elige un juego")
            .setItems(labels) { _, i -> binding.etPackage.setText(packages[i]) }
            .show()
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Dale permiso de superposición y vuelve a tocar", Toast.LENGTH_LONG).show()
            startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val pkg = binding.etPackage.text.toString().trim()
        val intent = Intent(this, OverlayService::class.java)
        intent.putExtra("package", pkg)
        startService(intent)
        Toast.makeText(this, "Overlay activo, minimiza y abre tu juego", Toast.LENGTH_LONG).show()
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
        val args = Shizuku.UserServiceArgs(ComponentName(packageName, UserService::class.java.name))
            .daemon(false).processNameSuffix("service").debuggable(false).version(1)
        Shizuku.bindUserService(args, connection)
    }

    private fun runCommand(cmd: String) {
        val service = userService
        if (service == null) {
            Toast.makeText(this, "Conecta Shizuku primero", Toast.LENGTH_SHORT).show()
            return
        }
        Thread {
            val result = try { service.execCommand(cmd).trim() } catch (e: Exception) { "Error: ${e.message}" }
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
