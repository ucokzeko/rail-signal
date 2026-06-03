// Interface for the Shizuku user-service that runs in the shell (uid 2000) process,
// where MODIFY_PHONE_STATE is held, so allowed-network-types can be changed.
package com.railsignal.radio.shizuku;

interface IShizukuRadio {
    // Shizuku's reserved destroy transaction id.
    void destroy() = 16777114;

    // Force a re-register by briefly restricting allowed network types to LTE-only
    // (dropping NR/NSA), holding for holdMs, then restoring the previous types.
    // Returns true if the calls succeeded. Restore is guaranteed (finally).
    boolean reRegister(long holdMs) = 1;
}
