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
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
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

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    private fun rounded(color: String, radius: Int, stroke: String? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(Color.parseColor(color))
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), Color.parseColor(stroke))
        }

    private fun actionButton(label: String, color: String, textColor: String = "#FFFFFF"): Button =
        Button(this).apply {
            text = label
            isAllCaps = false
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor(textColor))
            val shape = rounded(color, 15)
            val rippleMask = rounded("#FFFFFF", 15)
            background = RippleDrawable(
                ColorStateList.valueOf(Color.parseColor("#55FFFFFF")),
                shape,
                rippleMask
            )
            stateListAnimator = null
            minHeight = dp(50)
            minimumHeight = dp(50)
            setPadding(dp(12), 0, dp(12), 0)
        }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(16))
            setBackgroundColor(Color.parseColor("#0B1220"))
        }

        val heading = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(2), 0, dp(10))
        }
        heading.addView(TextView(this).apply {
            text = "Cellmause"
            textSize = 25f
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            setTextColor(Color.parseColor("#F4F7FC"))
        })
        heading.addView(TextView(this).apply {
            text = "Tu teléfono como ratón Bluetooth"
            textSize = 13f
            setTextColor(Color.parseColor("#8FA3BD"))
        })
        root.addView(heading, LinearLayout.LayoutParams(-1, -2))

        status = TextView(this).apply {
            text = "Iniciando Bluetooth…"
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(Color.parseColor("#DCE8F8"))
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            minHeight = dp(48)
            background = rounded("#17263A", 14, "#2A405D")
        }
        root.addView(status, LinearLayout.LayoutParams(-1, -2).apply {
            bottomMargin = dp(12)
        })

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        top.addView(actionButton("Conectar", "#1677E8").apply {
            setOnClickListener { pickDevice() }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginEnd = dp(6)
        })
        top.addView(actionButton("Hacer visible", "#24364D", "#E5EEF9").apply {
            setOnClickListener {
                startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 120)
                })
            }
        }, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(6)
        })
        root.addView(top)

        root.addView(Touchpad(this), LinearLayout.LayoutParams(-1, 0, 1f).apply {
            topMargin = dp(14)
            bottomMargin = dp(14)
        })

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        buttons.addView(actionButton("●  Clic izquierdo", "#1B2B40", "#EAF2FC").apply {
            setOnClickListener { click(1) }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            marginEnd = dp(6)
        })
        buttons.addView(actionButton("●  Clic derecho", "#1B2B40", "#EAF2FC").apply {
            setOnClickListener { click(2) }
        }, LinearLayout.LayoutParams(0, dp(54), 1f).apply {
            marginStart = dp(6)
        })
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

        private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#526984")
            strokeWidth = dp(1).toFloat()
            style = Paint.Style.STROKE
        }
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#7890AC")
            textSize = dp(12).toFloat()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        }

        init {
            background = rounded("#111E30", 22, "#2A405D")
            contentDescription = "Panel táctil: desliza para mover el puntero"
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val guide = dp(24).toFloat()
            canvas.drawLine(cx - guide, cy, cx + guide, cy, guidePaint)
            canvas.drawLine(cx, cy - guide, cx, cy + guide, guidePaint)
            canvas.drawCircle(cx, cy, dp(38).toFloat(), guidePaint)
            canvas.drawText("DESLIZA PARA MOVER", cx, height - dp(24).toFloat(), labelPaint)
        }

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
