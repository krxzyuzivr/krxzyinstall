package com.krxzy.krxzyinstall;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;

public final class Perms {
    private Perms() {
    }

    public static boolean hasFiles(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        }
        return context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasInstall(Context context) {
        try {
            return context.getPackageManager().canRequestPackageInstalls();
        } catch (Throwable ignored) {
            return true;
        }
    }

    public static boolean hasNotifications(Context context) {
        if (Build.VERSION.SDK_INT >= 33) {
            return context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                    == PackageManager.PERMISSION_GRANTED;
        }
        return true;
    }

    public static void requestNotifications(Activity activity, int requestCode) {
        if (Build.VERSION.SDK_INT >= 33) {
            activity.requestPermissions(
                    new String[]{Manifest.permission.POST_NOTIFICATIONS}, requestCode);
        }
    }

    public static void openFilesSettings(Activity activity) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            activity.requestPermissions(new String[]{
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
            }, 11);
            return;
        }

        Intent app = new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
        app.setData(Uri.parse("package:" + activity.getPackageName()));
        if (start(activity, app)) {
            return;
        }

        Intent all = new Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION);
        if (start(activity, all)) {
            return;
        }

        openAppDetails(activity);
    }

    public static void openInstallSettings(Activity activity) {
        Intent app = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        app.setData(Uri.parse("package:" + activity.getPackageName()));
        if (start(activity, app)) {
            return;
        }

        Intent all = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES);
        if (start(activity, all)) {
            return;
        }

        openAppDetails(activity);
    }

    public static void openAppDetails(Activity activity) {
        Intent details = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        details.setData(Uri.parse("package:" + activity.getPackageName()));
        start(activity, details);
    }

    private static boolean start(Context context, Intent intent) {
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            return true;
        } catch (ActivityNotFoundException | SecurityException ignored) {
            return false;
        }
    }
}
