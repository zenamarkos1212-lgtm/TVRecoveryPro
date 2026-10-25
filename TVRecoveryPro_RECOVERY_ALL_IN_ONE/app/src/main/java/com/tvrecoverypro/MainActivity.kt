package com.tvrecoverypro

import android.app.PendingIntent
import android.content.*
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import android.net.Uri
import android.provider.OpenableColumns
import android.os.Environment
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.io.InputStream
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.nio.charset.Charset
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private lateinit var usbManager: UsbManager
    private lateinit var deviceSpinner: Spinner
    private lateinit var profileSpinner: Spinner
    private lateinit var baudSpinner: Spinner
    private lateinit var dataSpinner: Spinner
    private lateinit var paritySpinner: Spinner
    private lateinit var stopSpinner: Spinner
    private lateinit var status: TextView
    private lateinit var console: TextView
    private lateinit var sendEdit: EditText
    private lateinit var binInfo: TextView
    private lateinit var recoveryProgress: ProgressBar
    private var selectedBin: Uri? = null
    private var selectedBinName: String = ""
    private var rxBytes: Long = 0
    private var txBytes: Long = 0
    private var scanRunning = false

    private val devices = mutableListOf<UsbDevice>()
    private var port: UsbSerialPort? = null
    private val executor = Executors.newSingleThreadExecutor()

    private val profiles = listOf(
        "Generic UART",
        "MStar / MS",
        "MediaTek / MTK",
        "Realtek",
        "HiSilicon",
        "Panasonic",
        "Amlogic",
        "Nugget-MStar",
        "Amazon Fire TV"
    )

    private val baudRates = arrayOf("9600","19200","38400","57600","115200","230400","460800","921600")
    private val dataBits = arrayOf("8","7","6","5")
    private val parities = arrayOf("NONE","EVEN","ODD","MARK","SPACE")
    private val stopBits = arrayOf("1","1.5","2")

    companion object {
        private const val ACTION_USB_PERMISSION = "com.tvrecoverypro.USB_PERMISSION"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Play the bundled NewTech intro video over the main screen on launch.
        val introVideo = findViewById<VideoView>(R.id.introVideo)
        fun finishIntro() {
            if (introVideo.visibility != android.view.View.GONE) {
                introVideo.stopPlayback()
                introVideo.visibility = android.view.View.GONE
            }
        }
        introVideo.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.intro_video}"))
        introVideo.setOnCompletionListener { finishIntro() }
        introVideo.setOnErrorListener { _, _, _ ->
            finishIntro()
            true
        }
        introVideo.setOnPreparedListener { player ->
            player.isLooping = false
            introVideo.start()
        }

        usbManager = getSystemService(USB_SERVICE) as UsbManager

        deviceSpinner = findViewById(R.id.deviceSpinner)
        profileSpinner = findViewById(R.id.profileSpinner)
        baudSpinner = findViewById(R.id.baudSpinner)
        dataSpinner = findViewById(R.id.dataSpinner)
        paritySpinner = findViewById(R.id.paritySpinner)
        stopSpinner = findViewById(R.id.stopSpinner)
        status = findViewById(R.id.status)
        console = findViewById(R.id.console)
        sendEdit = findViewById(R.id.sendEdit)
        binInfo = findViewById(R.id.binInfo)
        recoveryProgress = findViewById(R.id.recoveryProgress)

        setupSpinners()

        findViewById<Button>(R.id.connectButton).setOnClickListener { detectDevices() }
        findViewById<Button>(R.id.openButton).setOnClickListener { openSelected() }
        findViewById<Button>(R.id.scanBaudButton).setOnClickListener { scanBaudRates() }
        findViewById<Button>(R.id.saveLogButton).setOnClickListener { saveLog() }
        findViewById<Button>(R.id.sendTextButton).setOnClickListener { sendText() }
        findViewById<Button>(R.id.sendHexButton).setOnClickListener { sendHex() }
        findViewById<Button>(R.id.clearButton).setOnClickListener { console.text = "" }

        findViewById<Button>(R.id.analyzeBinButton).setOnClickListener { analyzeBin() }

        findViewById<Button>(R.id.selectBinButton).setOnClickListener {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "application/octet-stream"
                addCategory(Intent.CATEGORY_OPENABLE)
            }
            startActivityForResult(i, 2001)
        }

        findViewById<Button>(R.id.probeButton).setOnClickListener { safeProbe() }
        findViewById<Button>(R.id.recoveryButton).setOnClickListener { startProfileRecovery() }

        detectDevices()
    }

    private fun setupSpinners() {
        profileSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, profiles)
        baudSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, baudRates)
        dataSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, dataBits)
        paritySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, parities)
        stopSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, stopBits)
        baudSpinner.setSelection(4) // 115200
    }

    private fun detectDevices() {
        devices.clear()
        devices.addAll(usbManager.deviceList.values)

        val names = if (devices.isEmpty()) {
            listOf("No USB serial device detected")
        } else {
            devices.map {
                "VID %04X / PID %04X / %s".format(it.vendorId, it.productId, it.deviceName)
            }
        }

        deviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)

        if (devices.isEmpty()) {
            status.text = "USB: No device"
            append("No USB device found. Connect PL2303/CH340/CP210x/FTDI with USB OTG.")
        } else {
            status.text = "USB: ${devices.size} device(s) detected"
            devices.forEach {
                append("Detected VID=%04X PID=%04X %s".format(it.vendorId, it.productId, it.deviceName))
            }
        }
    }

    private fun openSelected() {
        if (devices.isEmpty()) {
            append("Nothing to open.")
            return
        }

        val device = devices[deviceSpinner.selectedItemPosition]
        if (!usbManager.hasPermission(device)) {
            val pi = PendingIntent.getBroadcast(
                this, 0,
                Intent(ACTION_USB_PERMISSION),
                PendingIntent.FLAG_IMMUTABLE
            )
            val filter = IntentFilter(ACTION_USB_PERMISSION)
            registerReceiver(usbReceiver, filter, RECEIVER_NOT_EXPORTED)
            usbManager.requestPermission(device, pi)
            return
        }

        connectDevice(device)
    }

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && device != null) {
                    connectDevice(device)
                } else {
                    append("USB permission denied.")
                }
            }
        }
    }

    private fun connectDevice(device: UsbDevice) {
        try {
            val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            val driver: UsbSerialDriver = drivers.firstOrNull { it.device.deviceId == device.deviceId }
                ?: throw Exception("No supported serial driver for this USB device")

            val connection = usbManager.openDevice(driver.device)
                ?: throw Exception("USB open failed")

            val p = driver.ports.firstOrNull() ?: throw Exception("No serial port")
            p.open(connection)

            val baud = baudRates[baudSpinner.selectedItemPosition].toInt()
            val data = dataBits[dataSpinner.selectedItemPosition].toInt()
            val parity = when (paritySpinner.selectedItemPosition) {
                1 -> UsbSerialPort.PARITY_EVEN
                2 -> UsbSerialPort.PARITY_ODD
                3 -> UsbSerialPort.PARITY_MARK
                4 -> UsbSerialPort.PARITY_SPACE
                else -> UsbSerialPort.PARITY_NONE
            }
            val stop = when (stopSpinner.selectedItemPosition) {
                1 -> UsbSerialPort.STOPBITS_1_5
                2 -> UsbSerialPort.STOPBITS_2
                else -> UsbSerialPort.STOPBITS_1
            }

            p.setParameters(baud, data, stop, parity)
            port = p

            status.text = "USB: Connected"
            append("CONNECTED: ${driver.javaClass.simpleName}")
            append("UART: $baud ${data}N$stop")

            startReader()
        } catch (e: Exception) {
            status.text = "USB: Error"
            append("ERROR: ${e.message}")
        }
    }

    private fun startReader() {
        executor.execute {
            val buffer = ByteArray(4096)
            while (port != null) {
                try {
                    val n = port!!.read(buffer, 200)
                    if (n > 0) {
                        val data = buffer.copyOf(n)
                        runOnUiThread {
                            rxBytes += n
                            updateCounters()
                            append("RX: " + data.toString(Charset.defaultCharset()))
                        }
                    }
                } catch (_: Exception) {
                    break
                }
            }
        }
    }


    private fun updateCounters() {
        val v = findViewById<TextView>(R.id.counters)
        v.text = "RX: $rxBytes bytes    TX: $txBytes bytes"
    }

    private fun scanBaudRates() {
        val current = port ?: run {
            append("Connect USB serial first.")
            return
        }
        if (scanRunning) return

        // We cannot reliably identify a TV UART baud rate without traffic or
        // a protocol-specific handshake. This scanner therefore listens only.
        // It does NOT transmit bootloader/erase/write commands.
        scanRunning = true
        append("BAUD SCAN: passive listening only.")
        val candidates = baudRates.map { it.toInt() }

        executor.execute {
            val found = mutableListOf<Int>()
            for (b in candidates) {
                if (!scanRunning) break
                try {
                    current.setParameters(
                        b,
                        8,
                        1,
                        UsbSerialPort.PARITY_NONE
                    )
                    Thread.sleep(500)
                    val buf = ByteArray(512)
                    val n = current.read(buf, 100)
                    if (n > 0) {
                        found.add(b)
                        val sample = buf.copyOf(n).joinToString(" ") { "%02X".format(it) }
                        runOnUiThread { append("TRAFFIC @ $b baud: $sample") }
                    }
                } catch (_: Exception) { }
            }

            runOnUiThread {
                scanRunning = false
                if (found.isEmpty()) {
                    append("No traffic detected. Set the baud rate from the TV/board documentation.")
                } else {
                    append("Possible baud rates: ${found.joinToString(", ")}")
                }
            }
        }
    }

    private fun saveLog() {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val filename = "TVRecoveryPro_$stamp.txt"
        val text = console.text.toString()

        try {
            val dir = getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            val file = java.io.File(dir, filename)
            file.writeText(text, Charsets.UTF_8)
            append("LOG SAVED: ${file.absolutePath}")
        } catch (e: Exception) {
            append("SAVE LOG ERROR: ${e.message}")
        }
    }

    private fun sendText() {
        val p = port ?: run {
            append("Not connected.")
            return
        }
        val text = sendEdit.text.toString()
        if (text.isEmpty()) return
        try {
            val bytes = text.toByteArray()
            p.write(bytes, 1000)
            txBytes += bytes.size
            updateCounters()
            append("TX: $text")
        } catch (e: Exception) {
            append("TX ERROR: ${e.message}")
        }
    }

    private fun sendHex() {
        val p = port ?: run {
            append("Not connected.")
            return
        }

        try {
            val bytes = sendEdit.text.toString()
                .trim()
                .split(Regex("[ ,:]+"))
                .filter { it.isNotEmpty() }
                .map { it.toInt(16).toByte() }
                .toByteArray()

            p.write(bytes, 1000)
            txBytes += bytes.size
            updateCounters()
            append("TX HEX: " + bytes.joinToString(" ") { "%02X".format(it) })
        } catch (e: Exception) {
            append("HEX ERROR: ${e.message}")
        }
    }

    private fun append(s: String) {
        console.append(s + "\n")
    }


    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 2001 && resultCode == RESULT_OK) {
            selectedBin = data?.data
            selectedBinName = selectedBin?.let { uri ->
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { c -> if (c.moveToFirst()) c.getString(0) else uri.lastPathSegment ?: "firmware.bin" }
            } ?: "firmware.bin"
            binInfo.text = "Firmware: $selectedBinName"
            append("BIN selected: $selectedBinName")
        }
    }

    private fun safeProbe() {
        val p = port ?: run { append("Connect USB serial first."); return }
        val profile = profiles[profileSpinner.selectedItemPosition]
        append("SAFE PROBE: $profile")
        append("Read-only probe mode. No erase/write command will be sent.")

        // A newline is intentionally the only generic byte sent here.
        // Bootloader-specific handshake bytes must be supplied from a verified
        // protocol for the exact board/model before enabling them.
        try {
            p.write(byteArrayOf(0x0A), 1000)
            append("TX: 0A (newline)")
        } catch (e: Exception) {
            append("Probe error: ${e.message}")
        }
    }

    private fun analyzeBin() {
        val uri = selectedBin ?: run { append("Select a BIN first."); return }
        executor.execute {
            try {
                contentResolver.openInputStream(uri).use { input ->
                    if (input == null) throw Exception("Cannot open BIN")
                    val head = ByteArray(64)
                    val n = input.read(head)
                    val hex = head.copyOf(maxOf(0, n)).joinToString(" ") { "%02X".format(it) }
                    val ascii = head.copyOf(maxOf(0, n)).map {
                        val v = it.toInt() and 0xFF
                        if (v in 32..126) v.toChar() else '.'
                    }.joinToString("")
                    runOnUiThread {
                        append("BIN HEADER HEX: $hex")
                        append("BIN HEADER ASCII: $ascii")
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { append("BIN analysis error: ${e.message}") }
            }
        }
    }

    private fun startProfileRecovery() {
        val p = port ?: run { append("Connect USB serial first."); return }
        val uri = selectedBin ?: run { append("Select a BIN file first."); return }
        val profile = profiles[profileSpinner.selectedItemPosition]

        // This starter intentionally refuses to invent a flashing protocol.
        // Actual recovery must be implemented from a verified protocol for the exact
        // SoC/board/model. We can still validate/read the BIN locally.
        executor.execute {
            try {
                val size = contentResolver.openFileDescriptor(uri, "r")?.use { it.statSize } ?: -1L
                runOnUiThread {
                    recoveryProgress.visibility = ProgressBar.VISIBLE
                    recoveryProgress.progress = 0
                    append("PROFILE: $profile")
                    append("BIN size: $size bytes")
                    append("RECOVERY ENGINE: protocol verification required.")
                    append("No erase/write command was sent.")
                    recoveryProgress.progress = 100
                    recoveryProgress.visibility = ProgressBar.GONE
                    append("SAFE STOP: firmware was NOT written.")
                }
            } catch (e: Exception) {
                runOnUiThread { append("BIN error: ${e.message}") }
            }
        }
    }

    override fun onDestroy() {
        try { port?.close() } catch (_: Exception) {}
        executor.shutdownNow()
        super.onDestroy()
    }
}
