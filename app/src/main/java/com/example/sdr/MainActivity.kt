package com.example.sdr

import HackRFDetector
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import androidx.activity.enableEdgeToEdge
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.sdr.*
import com.example.sdr.device.Hackrf

class MainActivity : AppCompatActivity() {

    companion object {
        const val ACTION_USB_PERMISSION = "com.example.sdr.USB_PERMISSION"
    }

    private lateinit var usbManager: UsbManager

    @RequiresApi(Build.VERSION_CODES.O)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }



        val askButton: Button = findViewById(R.id.askButton)
        val askUsb: Button = findViewById(R.id.askUsb)

        val hackRFDetector = HackRFDetector()
        usbManager = hackRFDetector.getUsbManager(applicationContext)
            ?: throw IllegalStateException("HackRF usb manager not found!")

        val hackRFDevice: UsbDevice? = hackRFDetector.getHackRFDevice(applicationContext)

        askButton.setOnClickListener {
            Log.i("HACKRF", "CLICK")

            val myHackrf = hackRFDevice?.let { it1 -> Hackrf(usbManager, it1, 512) }
            if (myHackrf != null) {
                myHackrf.setFrequency(98000000L)
            }

        }


        askUsb.setOnClickListener {
            if (hackRFDevice != null && !usbManager.hasPermission(hackRFDevice)) {
                requestUsbPermission(hackRFDevice!!)
            } else {
                Log.i("HACKRF", "Permission already granted or no device connected.")
            }
        }
    }

    /**
     * Request permission for the USB device.
     */
    @SuppressLint("NewApi")
    private fun requestUsbPermission(device: UsbDevice) {
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        registerReceiver(usbPermissionReceiver, filter, RECEIVER_NOT_EXPORTED)

        usbManager.requestPermission(device, permissionIntent)
    }

    /**
     * BroadcastReceiver to handle USB permission result.
     */
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                val device = intent.getParcelableExtra<UsbDevice>(UsbManager.EXTRA_DEVICE)
                val permissionGranted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
    }
}
