package lol.yukichat.wallet;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** Reconnects for payment notifications after a reboot or an app update. */
public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context c, Intent i) {
        WalletService.start(c);
    }
}
