package com.krxzy.krxzyinstall;

import java.io.File;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ObbInfo {
    private static final Pattern STANDARD =
            Pattern.compile("^(main|patch)\\.(\\d+)\\.([A-Za-z0-9_.]+)\\.obb$", Pattern.CASE_INSENSITIVE);

    public String path = "";
    public String name = "";
    public String packageName;
    public String kind = "OBB";
    public long versionCode = -1;
    public long size;
    public long modified;
    public boolean manual;

    public static ObbInfo from(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        ObbInfo info = new ObbInfo();
        info.path = file.getAbsolutePath();
        info.name = file.getName();
        info.size = file.length();
        info.modified = file.lastModified();

        Matcher matcher = STANDARD.matcher(info.name);
        if (matcher.matches()) {
            String prefix = matcher.group(1).toLowerCase(Locale.US);
            info.kind = "main".equals(prefix) ? "Main" : "Patch";
            try {
                info.versionCode = Long.parseLong(matcher.group(2));
            } catch (NumberFormatException ignored) {
                info.versionCode = -1;
            }
            info.packageName = matcher.group(3);
        }
        return info;
    }

    public boolean matches(ApkInfo apk) {
        if (apk == null || packageName == null) {
            return false;
        }
        return packageName.equalsIgnoreCase(apk.packageName);
    }

    public boolean versionMatches(ApkInfo apk) {
        return versionCode <= 0 || (apk != null && versionCode == apk.versionCode);
    }
}
