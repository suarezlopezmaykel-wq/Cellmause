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
import android.os.Handler
import android.os.Looper
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
    private var registered = false
    private lateinit var status: TextView
    private val exec = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())

    private val descriptor = byteArrayOf(
        0x05, 0x01, 0x09, 0x02, 0xA1.toByte(), 0x01, 0x09, 0x01, 0xA1.toByte(), 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x03, 0x15, 0x00, 0x25, 0x01,
        0x95.toByte(), 0x03, 0x75, 0x01, 0x81.toByte(), 0x02,
        0x95.toByte(), 0x01, 0x75, 0x05, 0x81.toByte(), 0x03,
        0x05, 0x01, 0x09, 0x30, 0x09, 0x31, 0x09, 0x38,
        0x15, 0x81.toByte(), 0x25, 0x7F, 0x75, 0x08, 0x95.toByte(), 0x03, 0x81.toByte(), 0x06,
        0xC0.toByte(), 0xC0.toByte()
    )

    private fun setStatus(msg: String) = runOnUiThread { status.text = msg }

    private val callback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, isRegistered: Boolean) {
            registered = isRegistered
            setStatus(
                if (isRegistered) "HID registrado. Pulsa «Conectar» o conecta desde la PC."
                else "HID NO registrado. Pulsa «Reiniciar HID»."
            )
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    setStatus("Conectado: ${device.name ?: device.address}")
                }
                BluetoothProfile.STATE_CONNECTING ->
                    setStatus("Conectando con ${device.name ?: device.address}…")
                else -> if (device == host || host == null) {
                    host = null
                    setStatus("Desconectado")
                }
            }
        }

        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            hid?.replyReport(device, type, id, byteArrayOf(0, 0, 0, 0))
        }

        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hid?.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hid = proxy as BluetoothHidDevice
            registerHid()
        }

        override fun onServiceDisconnected(profile: Int) {
            hid = null
            registered = false
            setStatus("Servicio HID no disponible")
        }
    }

    private fun registerHid() {
        val h = hid ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            "BT Mouse", "Mouse virtual", "Android",
            BluetoothHidDevice.SUBCLASS1_MOUSE, descriptor
        )
        val ok = h.registerApp(sdp, null, null, exec, callback)
        if (!ok) setStatus("registerApp falló (¿otra app usa HID?)")
    }

    private fun restartHid() {
        setStatus("Reiniciando HID…")
        hid?.unregisterApp()
        host = null
        registered = false
        ui.postDelayed({ registerHid() }, 800)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        if (needsPermissions()) requestPermissions(permissions(), 1) else startHid()
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
        if (res.isNotEmpty() && res.all { it == PackageManager.PERMISSION_GRANTED }) startHid()
        else status.text = "Se necesitan los permisos de Bluetooth"
    }

    private fun startHid() {
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null || !adapter.isEnabled) {
            status.text = "Activa el Bluetooth y reinicia la app"
            return
        }
        if (!adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)) {
            status.text = "Este teléfono no soporta HID Device"
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

        fun row(vararg b: Button) = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            b.forEach { addView(it, LinearLayout.LayoutParams(0, -2, 1f)) }
        }

        root.addView(row(
            Button(this).apply { text = "Conectar"; setOnClickListener { pickDevice() } },
            Button(this).apply {
                text = "Hacer visible"
                setOnClickListener {
                    startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                        putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                    })
                }
            },
            Button(this).apply { text = "Reiniciar HID"; setOnClickListener { restartHid() } }
        ))

        root.addView(Touchpad(this), LinearLayout.LayoutParams(-1, 0, 1f).apply {
            setMargins(16, 16, 16, 16)
        })

        val l = Button(this).apply { text = "Clic izq."; setOnClickListener { click(1) } }
        val r = Button(this).apply { text = "Clic der."; setOnClickListener { click(2) } }
        root.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(l, LinearLayout.LayoutParams(0, 160, 1f))
            addView(r, LinearLayout.LayoutParams(0, 160, 1f))
        })

        setContentView(root)
    }

    private fun pickDevice() {
        if (!registered) {
            status.text = "HID no registrado. Pulsa «Reiniciar HID»."
            return
        }
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val list = adapter?.bondedDevices?.toList().orEmpty()
        if (list.isEmpty()) {
            status.text = "Sin dispositivos vinculados. Pulsa «Hacer visible» y vincula desde la PC."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Dispositivo")
            .setItems(list.map { it.name ?: it.address }.toTypedArray()) { _, i ->
                val ok = hid?.connect(list[i]) ?: false
                status.text = if (ok) "Conectando…" else "connect() falló. Reinicia HID y reintenta."
            }.show()
    }

    private fun send(buttons: Int, dx: Int, dy: Int, wheel: Int) {
        val d = host ?: return
        hid?.sendReport(
            d, 0,
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
                    lastX = e.x; lastY = e.y
                    downTime = e.eventTime
                    moved = 0f
                    maxPointers = 1
                }
                MotionEvent.ACTION_POINTER_DOWN -> {
                    maxPointers = maxOf(maxPointers, e.pointerCount)
                    lastX = e.x; lastY = e.y
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.x - lastX
                    val dy = e.y - lastY
                    lastX = e.x; lastY = e.y
                    moved += abs(dx) + abs(dy)
                    if (e.pointerCount >= 2) send(0, 0, 0, (-dy / 6).toInt())
                    else send(0, (dx * 1.5f).toInt(), (dy * 1.5f).toInt(), 0)
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
            }
            return true
        }
    }

    override fun onDestroy() {
        hid?.unregisterApp()
        super.onDestroy()
    }
}