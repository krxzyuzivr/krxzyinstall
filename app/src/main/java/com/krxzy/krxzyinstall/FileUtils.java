package com.krxzy.krxzyinstall;

import android.content.Context;
import android.os.Build;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public final class FileUtils {
    public interface Progress {
        /**
         * @return false to cancel the copy.
         */
        boolean onProgress(long copied, long total);
    }

    private FileUtils() {
    }

    public static boolean copy(InputStream in, OutputStream out, long total, Progress progress)
            throws IOException {
        byte[] buffer = new byte[1024 * 1024];
        long copied = 0;
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
            copied += read;
            if (progress != null && !progress.onProgress(copied, total)) {
                return false;
            }
        }
        out.flush();
        return true;
    }

    public static void deleteQuietly(File file) {
        if (file != null) {
            try {
                if (file.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    public static boolean isSameVolume(Context context, File a, File b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                StorageManager manager =
                        (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
                StorageVolume va = manager.getStorageVolume(a);
                StorageVolume vb = manager.getStorageVolume(b);
                if (va != null && vb != null) {
                    if (va.isPrimary() && vb.isPrimary()) {
                        return true;
                    }
                    String ua = va.getUuid();
                    String ub = vb.getUuid();
                    return ua != null && ua.equals(ub);
                }
            }
            String ca = a.getCanonicalPath();
            String cb = b.getCanonicalPath();
            return volumeRoot(ca).equals(volumeRoot(cb));
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String volumeRoot(String canonical) {
        String[] parts = canonical.split("/");
        if (parts.length >= 4 && "storage".equals(parts[1])) {
            if ("emulated".equals(parts[2])) {
                return "/storage/emulated/" + parts[3];
            }
            return "/storage/" + parts[2];
        }
        return canonical;
    }

    public static String sizeOf(Context context, File file) {
        return Format.bytes(file == null ? 0 : file.length());
    }
}
