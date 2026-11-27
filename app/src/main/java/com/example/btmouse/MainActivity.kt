package com.example.btmouse

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.abs

@SuppressLint("MissingPermission")
class MainActivity : Activity() {

    private var hid: BluetoothHidDevice? = null
    private var host: BluetoothDevice? = null
    private lateinit var status: TextView
    private val exec = Executors.newSingleThreadExecutor()
    @Volatile private var hidRegistered = false
    @Volatile private var hidRegistrationPending = false
    private var isForeground = false
    private var leftDown = false

    // Descriptor HID: ratón de 3 botones + X, Y y rueda (reporte de 4 bytes).
    private val descriptor = byteArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
        0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
        0xC0.toByte(), 0xC0.toByte()
    )

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            hidRegistrationPending = false
            hidRegistered = registered
            runOnUiThread {
                if (registered) {
                    host = pluggedDevice
                    status.text = if (host == null) {
                        "Bluetooth HID listo. Pulsa «Conectar»."
                    } else {
                        "Conectado: ${host?.name ?: "dispositivo"}"
                    }
                } else {
                    host = null
                    status.text = if (isForeground) {
                        "HID no registrado. Esperando registro…"
                    } else {
                        "Bluetooth HID pausado"
                    }
                }
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            runOnUiThread {
                if (state == BluetoothProfile.STATE_CONNECTED) {
                    host = device
                    status.text = "Conectado: ${device.name ?: "dispositivo"}"
                } else if (device == host) {
                    host = null
                    status.text = if (hidRegistered) "Desconectado" else "HID no registrado"
                }
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            if (isForeground) registerHidApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            hidRegistered = false
            hidRegistrationPending = false
            host = null
            runOnUiThread { status.text = "Servicio HID no disponible" }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (needsPermissions()) {
            requestPermissions(permissions(), 1)
        } else {
            startHid()
        }
    }

    override fun onResume() {
        super.onResume()
        isForeground = true
        if (hid != null && !hidRegistered) registerHidApp()
    }

    override fun onPause() {
        isForeground = false
        super.onPause()
    }

    private fun registerHidApp() {
        val device = hid ?: return
        if (!isForeground || hidRegistered || hidRegistrationPending) return
        hidRegistrationPending = true

        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BT Mouse", "Mouse virtual", "Android",
            BluetoothHidDevice.SUBCLASS1_MOUSE, descriptor
        )
        val accepted = device.registerApp(sdp, null, null, exec, callback)
        if (!accepted) hidRegistrationPending = false
        runOnUiThread {
            status.text = if (accepted) {
                "Registrando Bluetooth HID…"
            } else {
                "Android no aceptó el registro HID. Vuelve a abrir la app."
            }
        }
    }

    private fun permissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_SCAN
        ) else emptyArray()

    private fun needsPermissions() = permissions().any {
        checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (code == 1 && res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) {
            startHid()
        } else if (code == 1) {
            status.text = "Se necesitan los permisos de Bluetooth"
        }
    }

    private fun startHid() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            status.text = "Activa el Bluetooth y vuelve a abrir la app"
            return
        }
        if (!adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)) {
            status.text = "Este teléfono no soporta el perfil Bluetooth HID Device"
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        status = TextView(this).apply {
            text = "Iniciando…"
            setPadding(24, 24, 24, 12)
            textSize = 16f
        }
        root.addView(status)

        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        top.addView(Button(this).apply {
            text = "Conectar"
            setOnClickListener { pickDevice() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(Button(this).apply {
            text = "Hacer visible"
            setOnClickListener {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                })
            }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(top)

        root.addView(Touchpad(this), LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(16, 16, 16, 16)
        })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "Clic izq."
            setOnClickListener { click(1) }
        }, LinearLayout.LayoutParams(0, 160, 1f))
        buttons.addView(Button(this).apply {
            text = "Clic der."
            setOnClickListener { click(2) }
        }, LinearLayout.LayoutParams(0, 160, 1f))
        root.addView(buttons)

        setContentView(root)
    }

    private fun pickDevice() {
        if (!hidRegistered) {
            status.text = "Espera a que Bluetooth HID termine de registrarse"
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val list = adapter?.bondedDevices?.toList().orEmpty()
        if (list.isEmpty()) {
            status.text = "Vincula primero el teléfono desde los ajustes Bluetooth de la PC"
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Dispositivo")
            .setItems(list.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                val accepted = hid?.connect(list[i]) == true
                status.text = if (accepted) {
                    "Solicitud de conexión enviada…"
                } else {
                    "No se pudo iniciar la conexión Bluetooth"
                }
            }.show()
    }

    // ---- Envío de reportes ----
    private fun send(buttons: Int, dx: Int, dy: Int, wheel: Int): Boolean {
        val device = host ?: return false
        val profile = hid ?: return false
        if (!hidRegistered) return false

        return profile.sendReport(
            device, 0,
            byteArrayOf(
                buttons.toByte(),
                dx.coerceIn(-127, 127).toByte(),
                dy.coerceIn(-127, 127).toByte(),
                wheel.coerceIn(-127, 127).toByte()
            )
        )
    }

    private fun click(mask: Int) {
        send(mask, 0, 0, 0)
        send(0, 0, 0, 0)
    }

    // ---- Touchpad ----
    inner class Touchpad(ctx: Context) : View(ctx) {
        private var lastX = 0f
        private var lastY = 0f
        private var downTime = 0L
        private var moved = 0f
        private var maxPointers = 0

        init { setBackgroundColor(Color.parseColor("#DDDDDD")) }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = e.x
                    lastY = e.y
                    downTime = e.eventTime
                    moved = 0f
                    maxPointers = 1
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxPointers = maxOf(maxPointers, e.pointerCount)
                    lastX = e.x
                    lastY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x
                    lastY = e.y
                    moved += abs(dx) + abs(dy)
                    if (e.pointerCount >= 2) {
                        send(if (leftDown) 1 else 0, 0, 0, (-dy / 6).toInt())
                    } else {
                        send(if (leftDown) 1 else 0, (dx * 1.5f).toInt(), (dy * 1.5f).toInt(), 0)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dur = e.eventTime - downTime
                    if (moved < 20f) {
                        when {
                            maxPointers >= 2 -> click(2)
                            dur > 500 -> click(2)
                            else -> click(1)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    leftDown = false
                }
            }
            return true
        }
    }

    override fun onDestroy() {
        val device = hid
        if (device != null) {
            if (hidRegistered) device.unregisterApp()
            (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
                .adapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, device)
        }
        hid = null
        hidRegistered = false
        hidRegistrationPending = false
        exec.shutdownNow()
        super.onDestroy()
    }
}
