package com.krxzy.krxzyinstall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.os.Build;

public class InstallResultReceiver extends BroadcastReceiver {
    private static final String CONFIRM_CHANNEL = "krxzy_confirm";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (intent == null) {
            return;
        }
        int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS,
                PackageInstaller.STATUS_FAILURE);

        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            sendWaiting(context);
            Intent confirm = null;
            try {
                if (Build.VERSION.SDK_INT >= 33) {
                    confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent.class);
                } else {
                    confirm = (Intent) intent.getParcelableExtra(Intent.EXTRA_INTENT);
                }
            } catch (Throwable ignored) {
            }

            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try {
                    context.startActivity(confirm);
                } catch (Throwable error) {
                    // Some Android versions restrict background activity starts.
                    // The notification still lets the user finish the install.
                    postConfirmNotification(context, confirm);
                }
            } else {
                sendResult(context, PackageInstaller.STATUS_FAILURE,
                        "The system installer could not open its confirmation screen.");
            }
            return;
        }

        sendResult(context, status,
                intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE));
    }

    private void sendWaiting(Context context) {
        Intent service = new Intent(context, InstallService.class);
        service.setAction(InstallService.ACTION_WAITING);
        startService(context, service);
    }

    private void sendResult(Context context, int status, String message) {
        Intent service = new Intent(context, InstallService.class);
        service.setAction(InstallService.ACTION_RESULT);
        service.putExtra(InstallService.EXTRA_STATUS, status);
        service.putExtra(InstallService.EXTRA_STATUS_MESSAGE, message);
        startService(context, service);
    }

    private void startService(Context context, Intent intent) {
        try {
            context.startService(intent);
        } catch (Throwable ignored) {
            try {
                if (Build.VERSION.SDK_INT >= 26) {
                    context.startForegroundService(intent);
                }
            } catch (Throwable ignoredAgain) {
            }
        }
    }

    private void postConfirmNotification(Context context, Intent confirm) {
        try {
            NotificationManager manager =
                    (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = new NotificationChannel(
                        CONFIRM_CHANNEL, "Install confirmation",
                        NotificationManager.IMPORTANCE_HIGH);
                manager.createNotificationChannel(channel);
            }
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent pending = PendingIntent.getActivity(
                    context, 77, confirm, flags);
            String channel = Build.VERSION.SDK_INT >= 26 ? CONFIRM_CHANNEL : null;
            Notification.Builder builder = channel == null
                    ? new Notification.Builder(context)
                    : new Notification.Builder(context, channel);
            Notification notification = builder
                    .setSmallIcon(R.drawable.ic_stat_install)
                    .setContentTitle("Confirm installation")
                    .setContentText("Tap to finish installing the APK.")
                    .setContentIntent(pending)
                    .setAutoCancel(true)
                    .setOngoing(false)
                    .build();
            manager.notify(9001, notification);
        } catch (Throwable ignored) {
        }
    }
}
