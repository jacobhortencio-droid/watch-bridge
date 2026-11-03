package io.github.jacobhortencio.watchbridge.watch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (MainActivity.hasBlePermissions(context)) WatchService.start(context);
    }
}
