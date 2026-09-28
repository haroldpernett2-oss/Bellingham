package com.bellingham.optimizer

import android.content.ComponentName
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.IBinder
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
        binding.btnOriginal.setOnClickListener { runCommand("wm size reset") }
        binding.btnMedia.setOnClickListener { runCommand("wm size 720x1280") }
        binding.btnBaja.setOnClickListener { runCommand("wm size 540x960") }
        binding.btnUltraBaja.setOnClickListener { runCommand("wm size 480x854") }
        binding.btnRam.setOnClickListener {
            runCommand("for p in \$(pm list packages -3 | sed 's/package://'); do am force-stop \$p; done")
        }

        checkAndBind()
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
            try {
                service.execCommand(cmd)
            } catch (e: Exception) {
                // ignore
            }
            runOnUiThread {
                Toast.makeText(this, "Listo ✅", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        Shizuku.removeRequestPermissionResultListener(permissionListener)
    }
}
