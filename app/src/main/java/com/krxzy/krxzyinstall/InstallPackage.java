package com.krxzy.krxzyinstall;

import java.util.ArrayList;
import java.util.List;

public class InstallPackage {
    public ApkInfo apk;
    public final List<ObbInfo> obbs = new ArrayList<>();
    public String displayName = "";
    public int installedVersionCode = -1;

    public InstallPackage(ApkInfo apk) {
        this.apk = apk;
        this.displayName = apk.label;
    }

    public long obbSize() {
        long total = 0;
        for (ObbInfo obb : obbs) {
            total += obb.size;
        }
        return total;
    }

    public long totalSize() {
        return apk.size + obbSize();
    }

    public boolean hasVersionMismatch() {
        for (ObbInfo obb : obbs) {
            if (!obb.versionMatches(apk)) {
                return true;
            }
        }
        return false;
    }

    public boolean isInstalled() {
        return installedVersionCode >= 0;
    }

    public boolean isDowngrade() {
        return installedVersionCode > 0 && apk != null && installedVersionCode > apk.versionCode;
    }

    public boolean isSameVersionInstalled() {
        return apk != null && installedVersionCode == apk.versionCode;
    }
}
