import com.example.sdr.device.Hackrf

interface HackrfCallbackInterface {
    /**
     * Called by initHackrf() after the device is ready to be used.
     *
     * @param hackrf    Instance of the HackRF that provides access to the device
     */
    fun onHackrfReady(hackrf: Hackrf?)


    /**
     * Called if there was an error when accessing the device.
     *
     * @param message    Reason for the Error
     */
    fun onHackrfError(message: String?)
}
