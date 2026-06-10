// Interface for the Shizuku user-service that runs in the shell (uid 2000) process,
// where MODIFY_PHONE_STATE is held, so the cellular radio can be power-cycled.
package com.railsignal.radio.shizuku;

interface IShizukuRadio {
    // Shizuku's reserved destroy transaction id.
    void destroy() = 16777114;

    // Force a re-register by power-cycling ONLY the cellular radio via
    // ITelephony.setRadioPower(false) -> hold holdMs -> setRadioPower(true).
    // Wi-Fi stays up. Returns true if the cycle ran. Radio-on restore is guaranteed (finally).
    boolean reRegister(long holdMs) = 1;
}
