package com.krxzy.krxzyinstall;

import java.util.Locale;

public final class Format {
    private Format() {
    }

    public static String bytes(long bytes) {
        if (bytes < 0) {
            return "?";
        }
        if (bytes >= 1024L * 1024L * 1024L) {
            return String.format(Locale.US, "%.2f GB", bytes / 1073741824.0);
        }
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    public static String speed(long bytesPerSecond) {
        return bytes(bytesPerSecond) + "/s";
    }

    public static int percent(long done, long total) {
        if (total <= 0) {
            return 0;
        }
        return (int) Math.max(0, Math.min(100, (done * 100L) / total));
    }

    public static String version(String name, long code) {
        if (name == null || name.trim().isEmpty()) {
            return "v" + code;
        }
        return "v" + name + " (" + code + ")";
    }
}
