package com.lettemin

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * USB CDC bridge to the Teensy. Commands documented in lettem_in.ino.
 *
 * Threading: open()/send*()/close() are @Synchronized — safe to call from
 * the service main thread + the file-upload worker. Reads (waitByte) are
 * blocking and must be called off the main thread for non-trivial waits.
 */
class TeensyBridge(private val ctx: Context) {

    companion object {
        private const val ACTION_USB_PERM = "com.lettemin.USB_PERM"
        private const val BAUD = 115200
    }

    private var port: UsbSerialPort? = null
    private var permReceiver: BroadcastReceiver? = null

    @Synchronized
    fun open(): Boolean {
        if (port?.isOpen == true) return true
        val usb = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(usb)
        val driver: UsbSerialDriver = drivers.firstOrNull() ?: return false
        val device = driver.device

        if (!usb.hasPermission(device)) {
            requestPerm(usb, device)
            return false
        }

        val conn = usb.openDevice(device) ?: return false
        val p = driver.ports.firstOrNull() ?: return false
        return try {
            p.open(conn)
            p.setParameters(BAUD, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            p.dtr = true
            port = p
            true
        } catch (e: IOException) {
            try { p.close() } catch (_: Exception) {}
            false
        }
    }

    private fun requestPerm(usb: UsbManager, device: android.hardware.usb.UsbDevice) {
        if (permReceiver != null) return
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
        val pi = PendingIntent.getBroadcast(ctx, 0, Intent(ACTION_USB_PERM).setPackage(ctx.packageName), flags)
        val rcv = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                if (i.action == ACTION_USB_PERM) {
                    try { ctx.unregisterReceiver(this) } catch (_: Exception) {}
                    permReceiver = null
                    open()
                }
            }
        }
        permReceiver = rcv
        val filter = IntentFilter(ACTION_USB_PERM)
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(rcv, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            ctx.registerReceiver(rcv, filter)
        }
        usb.requestPermission(device, pi)
    }

    @Synchronized
    fun send(cmd: Char): Boolean {
        val p = port ?: return false
        return try {
            p.write(byteArrayOf(cmd.code.toByte()), 500)
            true
        } catch (e: IOException) {
            close()
            false
        }
    }

    /** Tell Teensy which file on its SD to play on next 'G'/'A'. Returns true on 'f' ack. */
    @Synchronized
    fun setActiveFile(name: String, timeoutMs: Int = 1500): Boolean {
        val p = port ?: return false
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        require(nameBytes.size in 1..63) { "file name length must be 1..63" }
        val header = ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN).apply {
            put('F'.code.toByte())
            putShort(nameBytes.size.toShort())
        }.array()
        return try {
            p.write(header, 500)
            p.write(nameBytes, 500)
            waitForByte(p, 'f'.code.toByte(), timeoutMs)
        } catch (e: IOException) { close(); false }
    }

    /**
     * Upload bytes as a file on the Teensy SD.
     *  - Replaces any existing file with the same name.
     *  - Caller should off-load to a worker thread; data may be multi-MB.
     *  - Optional progress callback receives bytesSent/total after each chunk.
     */
    @Synchronized
    fun uploadFile(
        name: String,
        data: ByteArray,
        progress: ((sent: Int, total: Int) -> Unit)? = null,
        chunkSize: Int = 4096,
        timeoutMs: Int = 30_000
    ): Boolean {
        val p = port ?: return false
        val nameBytes = name.toByteArray(Charsets.US_ASCII)
        require(nameBytes.size in 1..63) { "file name length must be 1..63" }
        require(data.size in 1..(16 * 1024 * 1024)) { "data size out of range" }

        val header = ByteBuffer.allocate(1 + 2 + nameBytes.size + 4)
            .order(ByteOrder.LITTLE_ENDIAN).apply {
                put('W'.code.toByte())
                putShort(nameBytes.size.toShort())
                put(nameBytes)
                putInt(data.size)
            }.array()
        try {
            p.write(header, 1000)
            var sent = 0
            while (sent < data.size) {
                val take = minOf(chunkSize, data.size - sent)
                val slice = data.copyOfRange(sent, sent + take)
                p.write(slice, 2000)
                sent += take
                progress?.invoke(sent, data.size)
            }
            return waitForByte(p, 'D'.code.toByte(), timeoutMs)
        } catch (e: IOException) {
            close()
            return false
        }
    }

    private fun waitForByte(p: UsbSerialPort, expected: Byte, timeoutMs: Int): Boolean {
        val buf = ByteArray(1)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val n = try {
                p.read(buf, 250)
            } catch (e: IOException) { return false }
            if (n > 0) {
                if (buf[0] == expected) return true
                if (buf[0] == 'E'.code.toByte() || buf[0] == 'e'.code.toByte()) return false
            }
        }
        return false
    }

    @Synchronized
    fun close() {
        try { port?.close() } catch (_: Exception) {}
        port = null
        permReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        permReceiver = null
    }

    val isReady: Boolean get() = port?.isOpen == true
}
