package com.example.sdr.device
import HackrfCallbackInterface
import HackrfUsbException
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.hardware.usb.UsbRequest
import android.os.Parcelable
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * <h1>HackRF USB Library for Android</h1>
 *
 * Module:      Hackrf.java
 * Description: The Hackrf class represents the HackRF device and
 * acts as abstraction layer that manages the USB
 * communication between the device and the application.
 *
 * @author Dennis Mantz
 *
 * Copyright (C) 2014 Dennis Mantz
 * based on code of libhackrf [https://github.com/mossmann/hackrf/tree/master/host/libhackrf]:
 * Copyright (c) 2012, Jared Boone <jared></jared>@sharebrained.com>
 * Copyright (c) 2013, Benjamin Vernoux <titanmkd></titanmkd>@gmail.com>
 * Copyright (c) 2013, Michael Ossmann <mike></mike>@ossmann.com>
 * All rights reserved.
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 * - Redistributions of source code must retain the above copyright notice, this list
 * of conditions and the following disclaimer.
 * - Redistributions in binary form must reproduce the above copyright notice, this
 * list of conditions and the following disclaimer in the documentation and/or other
 * materials provided with the distribution.
 * - Neither the name of Great Scott Gadgets nor the names of its contributors may be
 * used to endorse or promote products derived from this software without specific
 * prior written permission.
 * License: http://www.gnu.org/licenses/gpl.html GPL version 2 or higher
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation; either
 * version 2 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * General Public License for more details.
 *
 * You should have received a copy of the GNU General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA
 */
class Hackrf constructor(usbManager: UsbManager, usbDevice: UsbDevice, queueSize: Int) :
    Runnable {
    // Attributes to hold the USB related objects:
    private var usbManager: UsbManager? = null
    private var usbDevice: UsbDevice? = null
    private var usbInterface: UsbInterface? = null
    private var usbConnection: UsbDeviceConnection? = null
    private var usbEndpointIN: UsbEndpoint
    private var usbEndpointOUT: UsbEndpoint

    /**
     * Returns the current mode of receiving / transmitting
     *
     * @return HACKRF_TRANSCEIVER_MODE_OFF, *_RECEIVE, *_TRANSMIT
     */
    var transceiverMode: Int = HACKRF_TRANSCEIVER_MODE_OFF // current mode of the HackRF
        private set
    private var usbThread: Thread? = null // hold the transceiver Thread if running
    private var queue: ArrayBlockingQueue<ByteArray>? =
        null // queue that buffers samples to pass them

    // between hackrf_android and the application
    private var bufferPool: ArrayBlockingQueue<ByteArray>? =
        null // queue that holds old buffers which can be

    // reused while receiving or transmitting samples
    // startTime (in ms since 1970) and packetCounter for statistics:
    private var transceiveStartTime: Long = 0

    /**
     * This returns the number of packets (of size getPacketSize()) received/transmitted since start.
     *
     * @return Number of packets (of size getPacketSize()) received/transmitted since start
     */
    var transceiverPacketCounter: Long = 0
        private set

    /**
     * Initializing the Hackrf Instance with a USB Device.
     * Note: The application must have reclaimed permissions to
     * access the USB Device BEFOR calling this constructor.
     *
     * @param usbManager    Instance of the USB Manager (System Service)
     * @param usbDevice        Instance of an USB Device representing the HackRF
     * @param queueSize        Size of the receive/transmit queue in bytes
     * @throws HackrfUsbException
     */
    init {
        // Initialize the class attributes:
        this.usbManager = usbManager
        this.usbDevice = usbDevice


        // For detailed trouble shooting: Read out information of the device:
        Log.i(
            logTag, ("constructor: create Hackrf instance from " + usbDevice.deviceName
                    + ". Vendor ID: " + usbDevice.vendorId + " Product ID: " + usbDevice.productId)
        )
        Log.i(logTag, "constructor: device protocol: " + usbDevice.deviceProtocol)
        Log.i(
            logTag, ("constructor: device class: " + usbDevice.deviceClass
                    + " subclass: " + usbDevice.deviceSubclass)
        )
        Log.i(logTag, "constructor: interface count: " + usbDevice.interfaceCount)

        try {
            // Extract interface from the device:
            this.usbInterface = usbDevice.getInterface(0)


            // For detailed trouble shooting: Read out interface information of the device:
            Log.i(
                logTag,
                ("constructor: [interface 0] interface protocol: " + usbInterface!!.getInterfaceProtocol()
                        + " subclass: " + usbInterface!!.getInterfaceSubclass())
            )
            Log.i(
                logTag,
                "constructor: [interface 0] interface class: " + usbInterface!!.getInterfaceClass()
            )
            Log.i(
                logTag,
                "constructor: [interface 0] endpoint count: " + usbInterface!!.getEndpointCount()
            )


            // Extract the endpoints from the device:
            this.usbEndpointIN = usbInterface!!.getEndpoint(0)
            this.usbEndpointOUT = usbInterface!!.getEndpoint(1)


            // For detailed trouble shooting: Read out endpoint information of the interface:
            Log.i(
                logTag, ("constructor:     [endpoint 0 (IN)] address: " + usbEndpointIN.getAddress()
                        + " attributes: " + usbEndpointIN.getAttributes() + " direction: " + usbEndpointIN.getDirection()
                        + " max_packet_size: " + usbEndpointIN.getMaxPacketSize())
            )
            Log.i(
                logTag,
                ("constructor:     [endpoint 1 (OUT)] address: " + usbEndpointOUT.getAddress()
                        + " attributes: " + usbEndpointOUT.getAttributes() + " direction: " + usbEndpointOUT.getDirection()
                        + " max_packet_size: " + usbEndpointOUT.getMaxPacketSize())
            )


            // Open the device:
            this.usbConnection = usbManager.openDevice(usbDevice)

            if (this.usbConnection == null) {
                Log.e(
                    logTag,
                    "constructor: Couldn't open HackRF USB Device: openDevice() returned null!"
                )
                throw (HackrfUsbException("Couldn't open HackRF USB Device! (device is gone)"))
            }
        } catch (e: Exception) {
            Log.e(logTag, "constructor: Couldn't open HackRF USB Device: " + e.message)
            throw (HackrfUsbException("Error: Couldn't open HackRF USB Device!"))
        }


        // ++++++++++++++This will complicate everything, so commenting it out for now !

        // Create the queue that is used to transport samples to the application.
        // Each queue element is a byte array of size usbEndpointIN.getMaxPacketSize() (512 Bytes)
        //this.queue = ArrayBlockingQueue(queueSize / packetSize)


        // Create another queue that will be used to collect old buffers for reusing them.
        // This will speed up things a lot!
        //this.bufferPool = ArrayBlockingQueue(queueSize / packetSize)
    }

    val packetSize: Int
        /**
         * This returns the size of the packets that are used in receiving /
         * transmitting samples. Note that the size is measured in bytes and
         * a complex sample always consists of 2 bytes!
         *
         * @return Packet size in Bytes
         */
        get() =//return this.usbEndpointIN.getMaxPacketSize(); <= gives 512 which is way too small
            Companion.packetSize

    val bufferFromBufferPool: ByteArray
        /**
         * Get a buffer (byte array with size getPacketSize()) that can be used to hold samples
         * for transmitting. Use this function to allocate your buffers which you will pass into the
         * queue while transmitting. It will reuse old buffers and save a lot of expensive memory
         * allocation and garbage collection time. If no old buffers are existing, it will allocate
         * a new one.
         *
         * @return allocated buffer of size getPacketSize()
         */
        get() {
            var buffer = bufferPool!!.poll()


            // Check if we got a buffer:
            if (buffer == null) buffer = ByteArray(packetSize)

            return buffer
        }

    /**
     * Returns a buffer that isn't used by the application any more to the buffer pool of this hackrf instance.
     * The buffer must be a byte array with size getPacketSize() (the one you got from the queue while receiving).
     * This will reuse old buffers while receiving and save a lot of expensive memory
     * allocation and garbage collection time.
     *
     * @param buffer    a byte array of size getPacketSize() that is not used by the application any more.
     */



    fun returnBufferToBufferPool(buffer: ByteArray) {
        if (buffer.size == packetSize) {
            // Throw it into the pool (don't care if it's working or not):
            bufferPool!!.offer(buffer)
        } else Log.w(logTag, "returnBuffer: Got a buffer with wrong size. Ignore it!")
    }

    val transceivingTime: Long
        /**
         * This returns the time in milliseconds since receiving/transmitting was started.
         *
         * @return time in milliseconds since receiving/transmitting was started.
         */
        get() {
            if (this.transceiveStartTime == 0L) return 0
            return System.currentTimeMillis() - this.transceiveStartTime
        }

    val averageTransceiveRate: Long
        /**
         * Returns the average rx/tx transfer rate in byte/seconds.
         *
         * @return average transfer rate in byte/seconds
         */
        get() {
            val transTime = this.transceivingTime / 1000 // Transfer Time in seconds
            if (transTime == 0L) return 0
            return this.transceiverPacketCounter * this.packetSize / transTime
        }

    /**
     * Converts a byte array into an integer using little endian byteorder.
     *
     * @param b            byte array (length 4)
     * @param offset    offset pointing to the first byte in the bytearray that should be used
     * @return            integer
     */
    private fun byteArrayToInt(b: ByteArray, offset: Int): Int {
        return b[offset + 0].toInt() and 0xFF or ((b[offset + 1].toInt() and 0xFF) shl 8) or (
                (b[offset + 2].toInt() and 0xFF) shl 16) or ((b[offset + 3].toInt() and 0xFF) shl 24)
    }

    /**
     * Converts a byte array into a long integer using little endian byteorder.
     *
     * @param b            byte array (length 8)
     * @param offset    offset pointing to the first byte in the bytearray that should be used
     * @return            long integer
     */
    private fun byteArrayToLong(b: ByteArray, offset: Int): Long {
        return (b[offset + 0].toInt() and 0xFF or ((b[offset + 1].toInt() and 0xFF) shl 8) or ((b[offset + 2].toInt() and 0xFF) shl 16) or (
                (b[offset + 3].toInt() and 0xFF) shl 24) or ((b[offset + 4].toInt() and 0xFF) shl 32) or ((b[offset + 5].toInt() and 0xFF) shl 40) or (
                (b[offset + 6].toInt() and 0xFF) shl 48) or ((b[offset + 7].toInt() and 0xFF) shl 56)).toLong()
    }

    /**
     * Converts an integer into a byte array using little endian byteorder.
     *
     * @param i        integer
     * @return        byte array (length 4)
     */
    private fun intToByteArray(i: Int): ByteArray {
        val b = ByteArray(4)
        b[0] = (i and 0xff).toByte()
        b[1] = ((i shr 8) and 0xff).toByte()
        b[2] = ((i shr 16) and 0xff).toByte()
        b[3] = ((i shr 24) and 0xff).toByte()
        return b
    }

    /**
     * Converts a long integer into a byte array using little endian byteorder.
     *
     * @param i        long integer
     * @return        byte array (length 8)
     */
    private fun longToByteArray(i: Long): ByteArray {
        val b = ByteArray(8)
        b[0] = (i and 0xffL).toByte()
        b[1] = ((i shr 8) and 0xffL).toByte()
        b[2] = ((i shr 16) and 0xffL).toByte()
        b[3] = ((i shr 24) and 0xffL).toByte()
        b[4] = ((i shr 32) and 0xffL).toByte()
        b[5] = ((i shr 40) and 0xffL).toByte()
        b[6] = ((i shr 48) and 0xffL).toByte()
        b[7] = ((i shr 56) and 0xffL).toByte()
        return b
    }

    /**
     * Executes a Request to the USB interface.
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param endpoint    USB_DIR_IN or USB_DIR_OUT
     * @param request    request type (HACKRF_VENDOR_REQUEST_**_READ)
     * @param value        value to use in the controlTransfer call
     * @param index        index to use in the controlTransfer call
     * @param buffer    buffer to use in the controlTransfer call
     * @return count of received bytes. Negative on error
     * @throws HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun sendUsbRequest(
        endpoint: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?
    ): Int {
        var len = 0


        // Determine the length of the buffer:
        if (buffer != null) len = buffer.size


        // Claim the usb interface
        if (!usbConnection!!.claimInterface(this.usbInterface, true)) {
            Log.e(logTag, "Couldn't claim HackRF USB Interface!")
            throw (HackrfUsbException("Couldn't claim HackRF USB Interface!"))
        }


        // Send Board ID Read request
        len = usbConnection!!.controlTransfer(
            endpoint or UsbConstants.USB_TYPE_VENDOR,  // Request Type
            request,  // Request
            value,  // Value (unused)
            index,  // Index (unused)
            buffer,  // Buffer
            len,  // Length
            0 // Timeout
        )


        // Release usb interface
        usbConnection!!.releaseInterface(this.usbInterface)

        return len
    }

    @get:Throws(HackrfUsbException::class)
    val boardID: Byte
        /**
         * Returns the Board ID of the HackRF.
         *
         * Note: This function interacts with the USB Hardware and
         * should not be called from a GUI Thread!
         *
         * @return HackRF Board ID
         * @throws HackrfUsbException
         */
        get() {
            val buffer = ByteArray(1)

            if (this.sendUsbRequest(
                    UsbConstants.USB_DIR_IN,
                    HACKRF_VENDOR_REQUEST_BOARD_ID_READ,
                    0,
                    0,
                    buffer
                ) != 1
            ) {
                Log.e(logTag, "getBoardID: USB Transfer failed!")
                throw (HackrfUsbException("USB Transfer failed!"))
            }

            return buffer[0]
        }

    @get:Throws(HackrfUsbException::class)
    val versionString: String
        /**
         * Returns the Version String of the HackRF.
         *
         * Note: This function interacts with the USB Hardware and
         * should not be called from a GUI Thread!
         *
         * @return HackRF Version String
         * @throws HackrfUsbException
         */
        get() {
            val buffer = ByteArray(255)
            var len = 0

            len = this.sendUsbRequest(
                UsbConstants.USB_DIR_IN,
                HACKRF_VENDOR_REQUEST_VERSION_STRING_READ,
                0,
                0,
                buffer
            )

            if (len < 1) {
                Log.e(logTag, "getVersionString: USB Transfer failed!")
                throw (HackrfUsbException("USB Transfer failed!"))
            }

            return String(buffer)
        }


    @get:Throws(HackrfUsbException::class)
    val partIdAndSerialNo: IntArray
        /**
         * Returns the Part ID + Serial Number of the HackRF.
         *
         * Note: This function interacts with the USB Hardware and
         * should not be called from a GUI Thread!
         *
         * @return int[2+6] => int[0-1] is Part ID; int[2-5] is Serial No
         * @throws HackrfUsbException
         */
        get() {
            val buffer = ByteArray(8 + 16)
            val ret = IntArray(2 + 4)

            if (this.sendUsbRequest(
                    UsbConstants.USB_DIR_IN, HACKRF_VENDOR_REQUEST_BOARD_PARTID_SERIALNO_READ,
                    0, 0, buffer
                ) != 8 + 16
            ) {
                Log.e(logTag, "getPartIdAndSerialNo: USB Transfer failed!")
                throw (HackrfUsbException("USB Transfer failed!"))
            }

            for (i in 0..5) {
                ret[i] = this.byteArrayToInt(buffer, 4 * i)
            }

            return ret
        }

    /**
     * Sets the Sample Rate of the HackRF.
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param    sampRate    Sample Rate in Hz
     * @param    divider        Divider
     * @return    true on success
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun setSampleRate(sampRate: Int, divider: Int): Boolean {
        val byteOut = ByteArrayOutputStream()

        try {
            byteOut.write(this.intToByteArray(sampRate))
            byteOut.write(this.intToByteArray(divider))
        } catch (e: IOException) {
            Log.e(logTag, "setSampleRate: Error while converting arguments to byte buffer.")
            return false
        }

        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_OUT, HACKRF_VENDOR_REQUEST_SAMPLE_RATE_SET,
                0, 0, byteOut.toByteArray()
            ) != 8
        ) {
            Log.e(logTag, "setSampleRate: USB Transfer failed!")
            throw (HackrfUsbException("USB Transfer failed!"))
        }

        return true
    }

    /**
     * Sets the baseband filter bandwidth of the HackRF.
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param    bandwidth    Bandwidth for the Baseband Filter
     * @return    true on success
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun setBasebandFilterBandwidth(bandwidth: Int): Boolean {
        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_OUT, HACKRF_VENDOR_REQUEST_BASEBAND_FILTER_BANDWIDTH_SET,
                bandwidth and 0xffff, (bandwidth shr 16) and 0xffff, null
            ) != 0
        ) {
            Log.e(logTag, "setBasebandFilterBandwidth: USB Transfer failed!")
            throw (HackrfUsbException("USB Transfer failed!"))
        }

        return true
    }

    /**
     * Sets the RX VGA Gain of the HackRF.
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param    gain    RX VGA Gain (0-62 in steps of 2)
     * @return    true on success
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun setRxVGAGain(gain: Int): Boolean {
        var gain = gain
        val retVal = ByteArray(1)

        if (gain > 62) {
            Log.e(logTag, "setRxVGAGain: RX VGA Gain must be within 0-62!")
            return false
        }


        // Must be in steps of two!
        if (gain % 2 != 0) gain = gain - (gain % 2)

        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_IN, HACKRF_VENDOR_REQUEST_SET_VGA_GAIN,
                0, gain, retVal
            ) != 1
        ) {
            Log.e(logTag, "setRxVGAGain: USB Transfer failed!")
            throw (HackrfUsbException("USB Transfer failed!"))
        }

        if (retVal[0].toInt() == 0) {
            Log.e(logTag, "setRxVGAGain: HackRF returned with an error!")
            return false
        }

        return true
    }

    /**
     * Sets the TX VGA Gain of the HackRF.
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param    gain    TX VGA Gain (0-62)
     * @return    true on success
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun setTxVGAGain(gain: Int): Boolean {
        val retVal = ByteArray(1)

        if (gain > 47) {
            Log.e(logTag, "setTxVGAGain: TX VGA Gain must be within 0-47!")
            return false
        }

        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_IN, HACKRF_VENDOR_REQUEST_SET_TXVGA_GAIN,
                0, gain, retVal
            ) != 1
        ) {
            Log.e(logTag, "setTxVGAGain: USB Transfer failed!")
            throw (HackrfUsbException("USB Transfer failed!"))
        }

        if (retVal[0].toInt() == 0) {
            Log.e(logTag, "setTxVGAGain: HackRF returned with an error!")
            return false
        }

        return true
    }

    /**
     * Sets the RX LNA Gain of the HackRF.
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param    gain    RX LNA Gain (0-40 in steps of 8)
     * @return    true on success
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun setRxLNAGain(gain: Int): Boolean {
        var gain = gain
        val retVal = ByteArray(1)

        if (gain > 40) {
            Log.e(logTag, "setRxLNAGain: RX LNA Gain must be within 0-40!")
            return false
        }


        // Must be in steps of 8!
        if (gain % 8 != 0) gain = gain - (gain % 8)

        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_IN, HACKRF_VENDOR_REQUEST_SET_LNA_GAIN,
                0, gain, retVal
            ) != 1
        ) {
            Log.e(logTag, "setRxLNAGain: USB Transfer failed!")
            throw (HackrfUsbException("USB Transfer failed!"))
        }

        if (retVal[0].toInt() == 0) {
            Log.e(logTag, "setRxLNAGain: HackRF returned with an error!")
            return false
        }

        return true
    }

    /**
     * Sets the Frequency of the HackRF.
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param    frequency    Frequency in Hz
     * @return    true on success
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun setFrequency(frequency: Long): Boolean {
        val byteOut = ByteArrayOutputStream()
        val mhz = (frequency / 1000000L).toInt()
        val hz = (frequency % 1000000L).toInt()

        Log.d(logTag, "Tune HackRF to " + mhz + "." + hz + "MHz...")

        try {
            byteOut.write(this.intToByteArray(mhz))
            byteOut.write(this.intToByteArray(hz))
        } catch (e: IOException) {
            Log.e(logTag, "setFrequency: Error while converting arguments to byte buffer.")
            return false
        }

        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_OUT, HACKRF_VENDOR_REQUEST_SET_FREQ,
                0, 0, byteOut.toByteArray()
            ) != 8
        ) {
            Log.e(logTag, "setFrequency: USB Transfer failed!")
            throw (HackrfUsbException("USB Transfer failed!"))
        }

        return true
    }

    /**
     * Sets the explicit IF and LO frequency of the HackRF.
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param    ifFrequency        Intermediate Frequency in Hz. Must be in [2150000000; 2750000000]
     * @param    loFrequency        Local Oscillator Frequency in Hz. Must be in [84375000; 5400000000]
     * @param    rfPath            RF_PATH_FILTER_BYPASS, *_HIGH_PASS or *_LOW_PASS
     * @return    true on success
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun setFrequencyExplicit(ifFrequency: Long, loFrequency: Long, rfPath: Int): Boolean {
        val byteOut = ByteArrayOutputStream()


        // check range of IF Frequency:
        if (ifFrequency < 2150000000L || ifFrequency > 2750000000L) {
            Log.e(logTag, "setFrequencyExplicit: IF Frequency must be in [2150000000; 2750000000]!")
            return false
        }

        if ((rfPath != RF_PATH_FILTER_BYPASS) && (loFrequency < 84375000L || loFrequency > 5400000000L)) {
            Log.e(logTag, "setFrequencyExplicit: LO Frequency must be in [84375000; 5400000000]!")
            return false
        }


        // Check if path is in the valid range:
        if (rfPath < 0 || rfPath > 2) {
            Log.e(logTag, "setFrequencyExplicit: Invalid value for rf_path!")
            return false
        }

        Log.d(
            logTag,
            "Tune HackRF to IF:$ifFrequency Hz; LO:$loFrequency Hz..."
        )

        try {
            byteOut.write(this.longToByteArray(ifFrequency))
            byteOut.write(this.longToByteArray(loFrequency))
            byteOut.write(rfPath)
        } catch (e: IOException) {
            Log.e(logTag, "setFrequencyExplicit: Error while converting arguments to byte buffer.")
            return false
        }

        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_OUT, HACKRF_VENDOR_REQUEST_SET_FREQ_EXPLICIT,
                0, 0, byteOut.toByteArray()
            ) != 17
        ) {
            Log.e(logTag, "setFrequencyExplicit: USB Transfer failed!")
            throw (HackrfUsbException("USB Transfer failed!"))
        }

        return true
    }

    /**
     * Enables or Disables the Amplifier of the HackRF.
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param    enable        true for enable or false for disable
     * @return    true on success
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun setAmp(enable: Boolean): Boolean {
        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_OUT, HACKRF_VENDOR_REQUEST_AMP_ENABLE,
                (if (enable) 1 else 0), 0, null
            ) != 0
        ) {
            Log.e(logTag, "setAmp: USB Transfer failed!")
            throw (HackrfUsbException("USB Transfer failed!"))
        }

        return true
    }

    /**
     * Enables or Disables the Antenna Port Power of the HackRF.
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param    enable        true for enable or false for disable
     * @return    true on success
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun setAntennaPower(enable: Boolean): Boolean {
        // The Jawbreaker doesn't support this command!
        if (boardID.toInt() == 1) {        // == Jawbreaker
            Log.w(
                logTag,
                "setAntennaPower: Antenna Power is not supported for HackRF Jawbreaker. Ignore."
            )
            return false
        }
        // The rad1o doesn't support this command!
        if (boardID.toInt() == 3) {        // == rad1o
            Log.w(logTag, "setAntennaPower: Antenna Power is not supported for rad1o. Ignore.")
            return false
        }
        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_OUT, HACKRF_VENDOR_REQUEST_ANTENNA_ENABLE,
                (if (enable) 1 else 0), 0, null
            ) != 0
        ) {
            Log.e(logTag, "setAntennaPower: USB Transfer failed!")
            throw (HackrfUsbException("USB Transfer failed!"))
        }

        return true
    }

    /**
     * Sets the Transceiver Mode of the HackRF (OFF,RX,TX)
     *
     * Note: This function interacts with the USB Hardware and
     * should not be called from a GUI Thread!
     *
     * @param    mode        HACKRF_TRANSCEIVER_MODE_OFF, *_RECEIVE or *_TRANSMIT
     * @return    true on success
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun setTransceiverMode(mode: Int): Boolean {
        if (mode < 0 || mode > 2) {
            Log.e(logTag, "Invalid Transceiver Mode: $mode")
            return false
        }

        this.transceiverMode = mode

        if (this.sendUsbRequest(
                UsbConstants.USB_DIR_OUT, HACKRF_VENDOR_REQUEST_SET_TRANSCEIVER_MODE,
                mode, 0, null
            ) != 0
        ) {
            Log.e(logTag, "setTransceiverMode: USB Transfer failed!")
            throw (HackrfUsbException("USB Transfer failed!"))
        }

        return true
    }

    /**
     * Starts receiving.
     *
     * @return    An ArrayBlockingQueue that will fill with the samples as they arrive.
     * Each queue element is a block of samples (byte[]) of size getPacketSize().
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun startRX(): ArrayBlockingQueue<ByteArray>? {
        // Flush the queue
        queue!!.clear()


        // Signal the HackRF Device to start receiving:
        this.setTransceiverMode(HACKRF_TRANSCEIVER_MODE_RECEIVE)


        // Start the Thread to queue the received samples:
        this.usbThread = Thread(this)
        usbThread!!.start()


        // Reset the packet counter and start time for statistics:
        this.transceiveStartTime = System.currentTimeMillis()
        this.transceiverPacketCounter = 0

        return this.queue
    }

    /**
     * Starts transmitting.
     *
     * @return    An ArrayBlockingQueue from which the hackrf will read the samples to transmit.
     * Each queue element must be a block of samples (byte[]) of size getPacketSize().
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun startTX(): ArrayBlockingQueue<ByteArray>? {
        // Flush the queue
        queue!!.clear()


        // Signal the HackRF Device to start transmitting:
        this.setTransceiverMode(HACKRF_TRANSCEIVER_MODE_TRANSMIT)


        // Start the Thread to queue the received samples:
        this.usbThread = Thread(this)
        usbThread!!.start()


        // Reset the packet counter and start time for statistics:
        this.transceiveStartTime = System.currentTimeMillis()
        this.transceiverPacketCounter = 0

        return this.queue
    }

    /**
     * Stops receiving or transmitting.
     *
     * @throws    HackrfUsbException
     */
    @Throws(HackrfUsbException::class)
    fun stop() {
        // Signal the HackRF Device to start receiving:
        this.setTransceiverMode(HACKRF_TRANSCEIVER_MODE_OFF)
    }

    /**
     * This method will be executed in a separate Thread after the HackRF starts receiving
     * Samples. It will return as soon as the transceiverMode changes or an error occurs.
     */
    private fun receiveLoop() {
        val usbRequests = arrayOfNulls<UsbRequest>(numUsbRequests)
        var buffer: ByteBuffer

        try {
            // Create, initialize and queue all usb requests:
            for (i in 0 until numUsbRequests) {
                // Get a ByteBuffer for the request from the buffer pool:
                buffer = ByteBuffer.wrap(this.bufferFromBufferPool)


                // Initialize the USB Request:
                usbRequests[i] = UsbRequest()
                usbRequests[i]!!.initialize(usbConnection, usbEndpointIN)
                usbRequests[i]!!.clientData = buffer


                // Queue the request
                if (usbRequests[i]!!.queue(buffer, packetSize) == false) {
                    Log.e(logTag, "receiveLoop: Couldn't queue USB Request.")
                    this.stop()
                    break
                }
            }


            // Run loop until transceiver mode changes...
            while (this.transceiverMode == HACKRF_TRANSCEIVER_MODE_RECEIVE) {
                // Wait for a request to return. This will block until one of the requests is ready.
                val request = usbConnection!!.requestWait()

                if (request == null) {
                    Log.e(logTag, "receiveLoop: Didn't receive USB Request.")
                    break
                }


                // Make sure we got an UsbRequest for the IN endpoint!
                if (request.endpoint !== usbEndpointIN) continue


                // Extract the buffer
                buffer = request.clientData as ByteBuffer


                // Increment the packetCounter (for statistics)
                transceiverPacketCounter++


                // Put the received samples into the queue, so that they can be read by the application
                if (!queue!!.offer(buffer.array())) {
                    // We hit the timeout.
                    Log.e(logTag, "receiveLoop: Queue is full. Stop receiving!")
                    break
                }


                // Get a fresh ByteBuffer for the request from the buffer pool:
                buffer = ByteBuffer.wrap(this.bufferFromBufferPool)
                request.clientData = buffer


                // Queue the request again...
                if (request.queue(buffer, packetSize) == false) {
                    Log.e(logTag, "receiveLoop: Couldn't queue USB Request.")
                    break
                }
            }
        } catch (e: HackrfUsbException) {
            Log.e(logTag, "receiveLoop: USB Error!")
        }


        // Receiving is done. Cancel and close all usb requests:
        for (request in usbRequests) {
            request?.cancel()
        }


        // If the transceiverMode is still on RECEIVE, we stop Receiving:
        if (this.transceiverMode == HACKRF_TRANSCEIVER_MODE_RECEIVE) {
            try {
                this.stop()
            } catch (e: HackrfUsbException) {
                Log.e(logTag, "receiveLoop: Error while stopping RX!")
            }
        }
    }

    /**
     * This method will be executed in a separate Thread after the HackRF starts transmitting
     * Samples. It will return as soon as the transceiverMode changes or an error occurs.
     */
    private fun transmitLoop() {
        val usbRequests = arrayOfNulls<UsbRequest>(numUsbRequests)
        var buffer: ByteBuffer
        var packet: ByteArray

        try {
            // Create, initialize and queue all usb requests:
            for (i in 0 until numUsbRequests) {
                // Get a packet from the queue:
                packet = queue!!.poll(1000, TimeUnit.MILLISECONDS) as ByteArray
                if (packet == null || packet.size != packetSize) {
                    Log.e(logTag, "transmitLoop: Queue empty or wrong packet format. Abort.")
                    this.stop()
                    break
                }


                // Wrap the packet in a ByteBuffer object:
                buffer = ByteBuffer.wrap(packet)


                // Initialize the USB Request:
                usbRequests[i] = UsbRequest()
                usbRequests[i]!!.initialize(usbConnection, usbEndpointOUT)
                usbRequests[i]!!.clientData = buffer


                // Queue the request
                if (usbRequests[i]!!.queue(buffer, packetSize) == false) {
                    Log.e(logTag, "receiveLoop: Couldn't queue USB Request.")
                    this.stop()
                    break
                }
            }


            // Run loop until transceiver mode changes...
            while (this.transceiverMode == HACKRF_TRANSCEIVER_MODE_TRANSMIT) {
                // Wait for a request to return. This will block until one of the requests is ready.
                val request = usbConnection!!.requestWait()

                if (request == null) {
                    Log.e(logTag, "transmitLoop: Didn't receive USB Request.")
                    break
                }


                // Make sure we got an UsbRequest for the OUT endpoint!
                if (request.endpoint !== usbEndpointOUT) continue

                // Increment the packetCounter (for statistics)
                transceiverPacketCounter++


                // Extract the buffer and return it to the buffer pool:
                buffer = request.clientData as ByteBuffer
                this.returnBufferToBufferPool(buffer.array())


                // Get the next packet from the queue:
                packet = queue!!.poll(1000, TimeUnit.MILLISECONDS) as ByteArray
                if (packet == null || packet.size != packetSize) {
                    Log.e(
                        logTag,
                        "transmitLoop: Queue empty or wrong packet format. Stop transmitting."
                    )
                    break
                }


                // Wrap the packet in a ByteBuffer object:
                buffer = ByteBuffer.wrap(packet)
                request.clientData = buffer


                // Queue the request again...
                if (request.queue(buffer, packetSize) == false) {
                    Log.e(logTag, "transmitLoop: Couldn't queue USB Request.")
                    break
                }
            }
        } catch (e: HackrfUsbException) {
            Log.e(logTag, "transmitLoop: USB Error!")
        } catch (e: InterruptedException) {
            Log.e(logTag, "transmitLoop: Interrup while waiting on queue!")
        }


        // Transmitting is done. Cancel and close all usb requests:
        for (request in usbRequests) {
            request?.cancel()
        }


        // If the transceiverMode is still on TRANSMIT, we stop Transmitting:
        if (this.transceiverMode == HACKRF_TRANSCEIVER_MODE_TRANSMIT) {
            try {
                this.stop()
            } catch (e: HackrfUsbException) {
                Log.e(logTag, "transmitLoop: Error while stopping TX!")
            }
        }
    }

    /**
     * This method will run when a new Thread was created. It simply calls
     * receiveLoop() or transmitLoop() according to the transceiveMode.
     */
    override fun run() {
        when (this.transceiverMode) {
            HACKRF_TRANSCEIVER_MODE_RECEIVE -> receiveLoop()
            HACKRF_TRANSCEIVER_MODE_TRANSMIT -> transmitLoop()
            else -> {}
        }
    }


    companion object {
        // Transceiver Modes:
        const val HACKRF_TRANSCEIVER_MODE_OFF: Int = 0
        const val HACKRF_TRANSCEIVER_MODE_RECEIVE: Int = 1
        const val HACKRF_TRANSCEIVER_MODE_TRANSMIT: Int = 2

        // USB Vendor Requests (from hackrf.c)
        private const val HACKRF_VENDOR_REQUEST_SET_TRANSCEIVER_MODE = 1
        private const val HACKRF_VENDOR_REQUEST_MAX2837_WRITE = 2
        private const val HACKRF_VENDOR_REQUEST_MAX2837_READ = 3
        private const val HACKRF_VENDOR_REQUEST_SI5351C_WRITE = 4
        private const val HACKRF_VENDOR_REQUEST_SI5351C_READ = 5
        private const val HACKRF_VENDOR_REQUEST_SAMPLE_RATE_SET = 6
        private const val HACKRF_VENDOR_REQUEST_BASEBAND_FILTER_BANDWIDTH_SET = 7
        private const val HACKRF_VENDOR_REQUEST_RFFC5071_WRITE = 8
        private const val HACKRF_VENDOR_REQUEST_RFFC5071_READ = 9
        private const val HACKRF_VENDOR_REQUEST_SPIFLASH_ERASE = 10
        private const val HACKRF_VENDOR_REQUEST_SPIFLASH_WRITE = 11
        private const val HACKRF_VENDOR_REQUEST_SPIFLASH_READ = 12
        private const val HACKRF_VENDOR_REQUEST_BOARD_ID_READ = 14
        private const val HACKRF_VENDOR_REQUEST_VERSION_STRING_READ = 15
        private const val HACKRF_VENDOR_REQUEST_SET_FREQ = 16
        private const val HACKRF_VENDOR_REQUEST_AMP_ENABLE = 17
        private const val HACKRF_VENDOR_REQUEST_BOARD_PARTID_SERIALNO_READ = 18
        private const val HACKRF_VENDOR_REQUEST_SET_LNA_GAIN = 19
        private const val HACKRF_VENDOR_REQUEST_SET_VGA_GAIN = 20
        private const val HACKRF_VENDOR_REQUEST_SET_TXVGA_GAIN = 21
        private const val HACKRF_VENDOR_REQUEST_ANTENNA_ENABLE = 23
        private const val HACKRF_VENDOR_REQUEST_SET_FREQ_EXPLICIT = 24

        // RF Filter Paths (from hackrf.c)
        const val RF_PATH_FILTER_BYPASS: Int = 0
        const val RF_PATH_FILTER_LOW_PASS: Int = 1
        const val RF_PATH_FILTER_HIGH_PASS: Int = 2

        // Some Constants:
        private const val logTag = "hackrf_android"
        private const val HACKRF_USB_PERMISSION = "com.mantz_it.hackrf_android.USB_PERMISSION"
        private const val numUsbRequests = 4 // Number of parallel UsbRequests
        private const val packetSize = 1024 * 16 // Buffer Size of each UsbRequest

        /**
         * Initializing the Hackrf Instance with a USB Device. This will try to request
         * the permissions to open the USB device and then create an instance of
         * the Hackrf class and pass it back via the callbackInterface
         *
         * @param context                Application context. Used to retrieve System Services (USB)
         * @param callbackInterface        This interface declares two methods that are called if the
         * device is ready or if there was an error
         * @param queueSize                Size of the receive/transmit queue in bytes
         * @return false if no Hackrf could be found
         */
        fun initHackrf(
            context: Context,
            callbackInterface: HackrfCallbackInterface,
            queueSize: Int
        ): Boolean {
            val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
            var hackrfUsbDvice: UsbDevice? = null

            if (usbManager == null) {
                Log.e(logTag, "initHackrf: Couldn't get an instance of UsbManager!")
                return false
            }


            // Get a list of connected devices
            val deviceList = usbManager.deviceList

            if (deviceList == null) {
                Log.e(logTag, "initHackrf: Couldn't read the USB device list!")
                return false
            }

            Log.i(logTag, "initHackrf: Found " + deviceList.size + " USB devices.")


            // Iterate over the list. Use the first Device that matches a HackRF
            val deviceIterator: Iterator<UsbDevice> = deviceList.values.iterator()
            while (deviceIterator.hasNext()) {
                val device = deviceIterator.next()

                Log.d(
                    logTag,
                    "initHackrf: deviceList: vendor=" + device.vendorId + " product=" + device.productId
                )


                // HackRF One (Vendor ID: 7504 [0x1d50]; Product ID: 24713 [0x6089] )
                if (device.vendorId == 7504 && device.productId == 24713) {
                    Log.i(logTag, "initHackrf: Found HackRF One at " + device.deviceName)
                    hackrfUsbDvice = device
                }

                // rad1o (Vendor ID: 7504 [0x1d50]; Product ID: 52245 [0xCC15] )
                if (device.vendorId == 7504 && device.productId == 52245) {
                    Log.i(logTag, "initHackrf: Found rad1o at " + device.deviceName)
                    hackrfUsbDvice = device
                }


                // HackRF Jawbreaker (Vendor ID: 7504 [0x1d50]; Product ID: 24651 [0x604b])
                if (device.vendorId == 7504 && device.productId == 24651) {
                    Log.i(logTag, "initHackrf: Found HackRF Jawbreaker at " + device.deviceName)
                    hackrfUsbDvice = device
                }
            }


            // Check if we found a device:
            if (hackrfUsbDvice == null) {
                Log.e(logTag, "initHackrf: No HackRF Device found.")
                return false
            }


            // Requesting Permissions:
            // First we define a broadcast receiver that handles the permission_granted intend:
            val permissionBroadcastReceiver: BroadcastReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (HACKRF_USB_PERMISSION == intent.action) {
                        val device =
                            intent.getParcelableExtra<Parcelable>(UsbManager.EXTRA_DEVICE) as UsbDevice?
                        if (intent.getBooleanExtra(
                                UsbManager.EXTRA_PERMISSION_GRANTED,
                                false
                            ) && device != null
                        ) {
                            // We have permissions to open the device! Lets init the hackrf instance and
                            // return it to the calling application.
                            Log.d(
                                logTag,
                                "initHackrf: Permission granted for device " + device.deviceName
                            )
                            try {
                                val hackrf = Hackrf(usbManager, device, queueSize)
                                Toast.makeText(
                                    context,
                                    "HackRF at " + device.deviceName + " is ready!",
                                    Toast.LENGTH_LONG
                                ).show()
                                callbackInterface.onHackrfReady(hackrf)
                            } catch (e: HackrfUsbException) {
                                Log.e(
                                    logTag,
                                    "initHackrf: Couldn't open device " + device.deviceName
                                )
                                Toast.makeText(
                                    context,
                                    "Couldn't open HackRF device",
                                    Toast.LENGTH_LONG
                                ).show()
                                callbackInterface.onHackrfError("Couldn't open device " + device.deviceName)
                            }
                        } else if (device != null) {
                            Log.e(
                                logTag,
                                "initHackrf: Permission denied for device " + device.deviceName
                            )
                            Toast.makeText(
                                context,
                                "Permission denied to open HackRF device",
                                Toast.LENGTH_LONG
                            ).show()
                            callbackInterface.onHackrfError("Permission denied for device " + device.deviceName)
                        } else {
                            Log.e(
                                logTag,
                                "initHackrf: Error with USB Permission Intent: $intent"
                            )
                            Toast.makeText(
                                context,
                                "Error with USB Permission Intent",
                                Toast.LENGTH_LONG
                            ).show()
                            callbackInterface.onHackrfError("Error with USB Permission Intent: $intent")
                        }
                    }


                    // unregister the Broadcast Receiver:
                    context.unregisterReceiver(this)
                }
            }

            val innerIntent = Intent(HACKRF_USB_PERMISSION)
            // setting the package name of the inner intent makes it explicit
            // From Android 14 it is required that mutable PendingIntents have explicit inner intents!
            innerIntent.setPackage(context.packageName)
            val mPermissionIntent =
                PendingIntent.getBroadcast(context, 0, innerIntent, PendingIntent.FLAG_MUTABLE)
            val filter = IntentFilter(HACKRF_USB_PERMISSION)
            ContextCompat.registerReceiver(
                context,
                permissionBroadcastReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )


            // Fire the request:
            usbManager.requestPermission(hackrfUsbDvice, mPermissionIntent)
            Log.d(
                logTag,
                "Permission request for device " + hackrfUsbDvice.deviceName + " was send. waiting..."
            )

            return true
        }

        /**
         * Converts the Board ID into a human readable String (e.g. #2 => "HackRF One")
         *
         * @param boardID    boardID to convert
         * @return Board ID interpretation as String
         * @throws HackrfUsbException
         */
        fun convertBoardIdToString(boardID: Int): String {
            return when (boardID) {
                0 -> "Jellybean"
                1 -> "Jawbreaker"
                2 -> "HackRF One"
                3 -> "rad1o"
                else -> "INVALID BOARD ID"
            }
        }

        /**
         * Computes a valid Baseband Filter Bandwidth that is closest to
         * a given Sample Rate. If there is no exact match, the returned
         * Bandwidth will be smaller than the Sample Rate.
         *
         * @param    sampRate    Bandwidth for the Baseband Filter
         * @return    Baseband Filter Bandwidth
         * @throws    HackrfUsbException
         */
        fun computeBasebandFilterBandwidth(sampRate: Int): Int {
            var bandwidth = 1750000
            val supportedBandwidthValues = intArrayOf(
                1750000, 2500000, 3500000, 5000000, 5500000,
                6000000, 7000000, 8000000, 9000000, 10000000,
                12000000, 14000000, 15000000, 20000000, 24000000,
                28000000
            )

            for (candidate in supportedBandwidthValues) {
                if (sampRate < candidate) break
                bandwidth = candidate
            }

            return bandwidth
        }
    }
}

