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

/**
 * Talks to the Teensy over USB CDC.
 * Teensy firmware listens for single-byte commands:
 *   'G' -> play greeting WAV, then DTMF 9
 *   'P' -> ping (Teensy replies 'p')
 *   'S' -> stop playback
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
            return false  // caller retries after broadcast
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

    @Synchronized
    fun close() {
        try { port?.close() } catch (_: Exception) {}
        port = null
        permReceiver?.let { try { ctx.unregisterReceiver(it) } catch (_: Exception) {} }
        permReceiver = null
    }

    val isReady: Boolean get() = port?.isOpen == true
}
