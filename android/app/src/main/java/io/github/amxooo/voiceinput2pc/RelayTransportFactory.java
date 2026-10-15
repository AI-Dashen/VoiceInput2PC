package io.github.amxooo.voiceinput2pc;

import android.content.Context;
import android.content.SharedPreferences;

/** Single construction path for the already-paired LAN/Tailcat transport. */
final class RelayTransportFactory {
    private RelayTransportFactory() {}
    static RelayTransport create(Context context,SharedPreferences prefs,PairingConfig pairing) throws Exception {
        if (pairing == null) throw new IllegalArgumentException("尚未配对电脑");
        if (prefs != null && prefs.getBoolean("lanOnly_"+pairing.fingerprint,false)) {
            PairingConfig local=new PairingConfig(pairing.host,pairing.port,pairing.token,
                    pairing.fingerprint,PairingConfig.TRANSPORT_LAN,"",pairing.deviceId);
            return new RelayClient(context,local);
        }
        return pairing.isAuto() ? new ConnectionManager(context,pairing) : new RelayClient(context,pairing);
    }
}
