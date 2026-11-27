package vn.karaoke.youtube;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

/**
 * Boot + self-revival receiver. Starts PlayerService after reboot and on
 * REVIVE/PING. No com.gathin.* references — standalone app only.
 */
public class BootReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context ctx, Intent intent) {
        StartupLog.log("Boot: " + (intent == null ? "?" : intent.getAction()) + " -> khoi dong PlayerService");
        Intent i = new Intent(ctx, PlayerService.class);
        try {
            if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i);
            else ctx.startService(i);
        } catch (Exception ignored) { }
    }
}
