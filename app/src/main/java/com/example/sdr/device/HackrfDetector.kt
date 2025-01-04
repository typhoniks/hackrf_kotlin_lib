import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

class HackRFDetector {

    companion object {
        // Replace with the Vendor ID and Product ID for HackRF
        const val HACKRF_VENDOR_ID = 0x1D50 // Replace with HackRF Vendor ID
        const val HACKRF_PRODUCT_ID = 0x6089 // Replace with HackRF Product ID
    }



    /**
     * Retrieves the HackRF device if connected.
     * @param context The context to access the UsbManager.
     * @return The HackRF UsbDevice? (nullable) or null if not found.
     */
    fun getHackRFDevice(context: Context): UsbDevice? {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
        val deviceList: HashMap<String, UsbDevice>? = usbManager?.deviceList

        // If usbManager or deviceList is null, return null
        deviceList?.let {
            for ((_, device) in it) {
                if (device.vendorId == HACKRF_VENDOR_ID && device.productId == HACKRF_PRODUCT_ID) {
                    return device!! // Return the matching device
                }
            }
        }
        return null // No matching device found
    }

    /**
     * Retrieves the UsbManager.
     * @param context The context to access the UsbManager.
     * @return The UsbManager? (nullable) instance or null if unavailable.
     */
    fun getUsbManager(context: Context): UsbManager? {
        return context.getSystemService(Context.USB_SERVICE) as? UsbManager
    }

}
