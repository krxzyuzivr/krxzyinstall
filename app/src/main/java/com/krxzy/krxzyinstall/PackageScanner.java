package com.krxzy.krxzyinstall;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Environment;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public final class PackageScanner {
    private static final int MAX_DEPTH = 5;
    private static final int MAX_FILES = 30000;

    public static class ScanResult {
        public final List<InstallPackage> packages = new ArrayList<>();
        public int invalidApks;
        public int unmatchedObbs;
    }

    private static class ManifestInfo {
        String packageName;
        String gameName;
        String releaseName;
        long versionCode;
        long modified;
    }

    private static class Collector {
        final Set<String> apks = new LinkedHashSet<>();
        final Set<String> obbs = new LinkedHashSet<>();
        final Set<String> manifests = new LinkedHashSet<>();
        final Set<String> visited = new HashSet<>();
        int files;

        void walk(File dir, int depth, int maxDepth) {
            if (dir == null || depth > maxDepth || files > MAX_FILES) {
                return;
            }
            String canonical;
            try {
                canonical = dir.getCanonicalPath();
            } catch (IOException ignored) {
                canonical = dir.getAbsolutePath();
            }
            if (!visited.add(canonical)) {
                return;
            }
            File[] children;
            try {
                children = dir.listFiles();
            } catch (Throwable ignored) {
                return;
            }
            if (children == null) {
                return;
            }
            for (File child : children) {
                if (files++ > MAX_FILES) {
                    return;
                }
                String name = child.getName();
                if (child.isDirectory()) {
                    if (name.startsWith(".")
                            || "Android".equalsIgnoreCase(name)
                            || "LOST.DIR".equalsIgnoreCase(name)) {
                        continue;
                    }
                    if (depth < maxDepth) {
                        walk(child, depth + 1, maxDepth);
                    }
                } else {
                    addFile(child, name);
                }
            }
        }

        void flat(File dir) {
            if (dir == null) {
                return;
            }
            File[] children;
            try {
                children = dir.listFiles();
            } catch (Throwable ignored) {
                return;
            }
            if (children == null) {
                return;
            }
            for (File child : children) {
                if (!child.isDirectory()) {
                    addFile(child, child.getName());
                }
            }
        }

        private void addFile(File file, String name) {
            String lower = name.toLowerCase(Locale.US);
            if (lower.endsWith(".apk")) {
                apks.add(file.getAbsolutePath());
            } else if (lower.endsWith(".obb")) {
                obbs.add(file.getAbsolutePath());
            } else if (lower.equals("release.manifest")) {
                manifests.add(file.getAbsolutePath());
            }
        }
    }

    private PackageScanner() {
    }

    public static ScanResult scan(Context context, Prefs prefs) {
        ScanResult result = new ScanResult();
        Collector collector = new Collector();

        File external = Environment.getExternalStorageDirectory();
        File downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);

        if (downloads != null) {
            collector.walk(downloads, 0, MAX_DEPTH);
        }
        File altDownloads = new File(external, "Downloads");
        if (!samePath(altDownloads, downloads)) {
            collector.walk(altDownloads, 0, MAX_DEPTH);
        }
        // Loose APK/OBB files that people dropped in the storage root.
        collector.flat(external);
        scanRemovableVolumes(context, collector, external, downloads);

        for (String path : prefs.manualApks()) {
            File file = new File(path);
            if (file.isFile()) {
                collector.apks.add(file.getAbsolutePath());
                File parent = file.getParentFile();
                if (parent != null) {
                    collector.walk(parent, 0, 3);
                }
            } else {
                prefs.removeManualApk(path);
            }
        }

        Map<String, ManifestInfo> manifests = new HashMap<>();
        for (String path : collector.manifests) {
            ManifestInfo info = readManifest(new File(path));
            if (info == null) {
                continue;
            }
            ManifestInfo existing = manifests.get(info.packageName);
            if (existing == null || info.modified > existing.modified) {
                manifests.put(info.packageName, info);
            }
        }

        Map<String, InstallPackage> groups = new LinkedHashMap<>();
        for (String path : collector.apks) {
            ApkInfo apk = readApkInfo(context, path);
            if (apk == null) {
                result.invalidApks++;
                continue;
            }
            if (apk.packageName.equals(context.getPackageName())) {
                // Reinstalling krxzyinstall from inside itself would kill the
                // process that is moving the OBB files.
                continue;
            }
            String key = apk.packageName + "@" + apk.versionCode;
            InstallPackage existing = groups.get(key);
            if (existing == null) {
                groups.put(key, new InstallPackage(apk));
            } else if (apk.modified > existing.apk.modified) {
                existing.apk = apk;
            }
        }

        List<ObbInfo> obbs = new ArrayList<>();
        for (String path : collector.obbs) {
            ObbInfo info = ObbInfo.from(new File(path));
            if (info != null) {
                obbs.add(info);
            }
        }

        // Some games ship OBBs with non-standard names. If the filename contains
        // a package name we know about, we can still associate it.
        for (ObbInfo obb : obbs) {
            if (obb.packageName != null) {
                continue;
            }
            for (InstallPackage pkg : groups.values()) {
                if (obb.name.contains(pkg.apk.packageName)) {
                    obb.packageName = pkg.apk.packageName;
                    obb.kind = "OBB";
                    break;
                }
            }
            if (obb.packageName == null) {
                result.unmatchedObbs++;
            }
        }

        for (InstallPackage pkg : groups.values()) {
            ManifestInfo manifest = manifests.get(pkg.apk.packageName);
            if (manifest != null) {
                if (manifest.releaseName != null && !manifest.releaseName.trim().isEmpty()) {
                    pkg.displayName = manifest.releaseName.trim();
                } else if (manifest.gameName != null && !manifest.gameName.trim().isEmpty()) {
                    pkg.displayName = manifest.gameName.trim();
                }
            }
            if (pkg.displayName == null || pkg.displayName.trim().isEmpty()) {
                pkg.displayName = pkg.apk.label;
            }

            Set<String> attached = new HashSet<>();
            for (ObbInfo obb : obbs) {
                if (obb.matches(pkg.apk)) {
                    pkg.obbs.add(obb);
                    attached.add(obb.path);
                }
            }
            for (String path : prefs.manualObbs(pkg.apk.packageName)) {
                if (attached.contains(path)) {
                    continue;
                }
                ObbInfo manual = ObbInfo.from(new File(path));
                if (manual == null) {
                    prefs.removeManualObb(pkg.apk.packageName, path);
                    continue;
                }
                manual.manual = true;
                if (manual.packageName == null) {
                    manual.packageName = pkg.apk.packageName;
                }
                pkg.obbs.add(manual);
                attached.add(manual.path);
            }
            attachDestinationObbs(pkg);
            sortObbs(pkg.obbs);
            pkg.installedVersionCode = getInstalledVersion(context, pkg.apk.packageName);
            result.packages.add(pkg);
        }

        Collections.sort(result.packages, new Comparator<InstallPackage>() {
            @Override
            public int compare(InstallPackage a, InstallPackage b) {
                return a.displayName.compareToIgnoreCase(b.displayName);
            }
        });
        return result;
    }

    /**
     * Rebuilds one package for the detail screen. Extra OBB paths come from the main
     * screen's scan, and manual OBBs are remembered per package.
     */
    public static InstallPackage buildPackage(Context context, Prefs prefs,
                                               String apkPath, List<String> extraObbPaths) {
        ApkInfo apk = readApkInfo(context, apkPath);
        if (apk == null) {
            return null;
        }
        InstallPackage pkg = new InstallPackage(apk);

        ManifestInfo manifest = findManifestNear(apkPath, apk.packageName);
        if (manifest != null) {
            if (manifest.releaseName != null && !manifest.releaseName.trim().isEmpty()) {
                pkg.displayName = manifest.releaseName.trim();
            } else if (manifest.gameName != null && !manifest.gameName.trim().isEmpty()) {
                pkg.displayName = manifest.gameName.trim();
            }
        }
        if (pkg.displayName == null || pkg.displayName.trim().isEmpty()) {
            pkg.displayName = apk.label;
        }

        Set<String> added = new LinkedHashSet<>();
        File apkFile = new File(apkPath);
        File parent = apkFile.getParentFile();
        if (parent != null) {
            Collector collector = new Collector();
            collector.walk(parent, 0, 3);
            for (String path : collector.obbs) {
                ObbInfo obb = ObbInfo.from(new File(path));
                if (obb == null) {
                    continue;
                }
                boolean matches = obb.matches(apk)
                        || (obb.packageName == null && obb.name.contains(apk.packageName));
                if (matches && added.add(obb.path)) {
                    pkg.obbs.add(obb);
                }
            }
        }

        if (extraObbPaths != null) {
            for (String path : extraObbPaths) {
                if (path == null || added.contains(path)) {
                    continue;
                }
                ObbInfo obb = ObbInfo.from(new File(path));
                if (obb != null && added.add(obb.path)) {
                    pkg.obbs.add(obb);
                }
            }
        }

        for (String path : prefs.manualObbs(apk.packageName)) {
            if (added.contains(path)) {
                continue;
            }
            ObbInfo obb = ObbInfo.from(new File(path));
            if (obb == null) {
                prefs.removeManualObb(apk.packageName, path);
                continue;
            }
            obb.manual = true;
            if (obb.packageName == null) {
                obb.packageName = apk.packageName;
            }
            if (added.add(obb.path)) {
                pkg.obbs.add(obb);
            }
        }

        attachDestinationObbs(pkg);
        sortObbs(pkg.obbs);
        pkg.installedVersionCode = getInstalledVersion(context, apk.packageName);
        return pkg;
    }

    public static ApkInfo readApkInfo(Context context, String path) {
        try {
            File file = new File(path);
            if (!file.isFile()) {
                return null;
            }
            PackageManager pm = context.getPackageManager();
            PackageInfo pi = pm.getPackageArchiveInfo(path, PackageManager.GET_META_DATA);
            if (pi == null || pi.applicationInfo == null || pi.packageName == null) {
                return null;
            }
            ApplicationInfo ai = pi.applicationInfo;
            ai.sourceDir = path;
            ai.publicSourceDir = path;

            ApkInfo info = new ApkInfo();
            info.path = path;
            info.packageName = pi.packageName;
            info.versionCode = pi.getLongVersionCode();
            info.versionName = pi.versionName == null ? "" : pi.versionName;
            info.size = file.length();
            info.modified = file.lastModified();
            info.minSdk = ai.minSdkVersion;
            info.targetSdk = ai.targetSdkVersion;

            CharSequence label = pm.getApplicationLabel(ai);
            info.label = label == null ? file.getName() : label.toString();
            try {
                Drawable icon = pm.getApplicationIcon(ai);
                if (icon != null) {
                    info.icon = icon;
                }
            } catch (Throwable ignored) {
            }
            return info;
        } catch (Throwable ignored) {
            return null;
        }
    }

    public static int getInstalledVersion(Context context, String packageName) {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, 0);
            return (int) info.getLongVersionCode();
        } catch (Throwable ignored) {
            return -1;
        }
    }

    private static ManifestInfo findManifestNear(String apkPath, String packageName) {
        File parent = new File(apkPath).getParentFile();
        if (parent == null) {
            return null;
        }
        File manifest = new File(parent, "release.manifest");
        if (!manifest.isFile()) {
            File grandparent = parent.getParentFile();
            if (grandparent == null) {
                return null;
            }
            manifest = new File(grandparent, "release.manifest");
        }
        ManifestInfo info = readManifest(manifest);
        if (info != null && packageName.equals(info.packageName)) {
            return info;
        }
        return null;
    }

    private static ManifestInfo readManifest(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            int count = 0;
            while ((line = reader.readLine()) != null && count++ < 40) {
                line = line.replace("\uFEFF", "").trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                String[] parts = line.split(";", -1);
                if (parts.length < 4) {
                    continue;
                }
                String packageName = parts[2].trim();
                if (!packageName.contains(".")) {
                    continue;
                }
                ManifestInfo info = new ManifestInfo();
                info.packageName = packageName;
                info.gameName = parts[0].trim();
                info.releaseName = parts[1].trim();
                try {
                    info.versionCode = Long.parseLong(parts[3].trim());
                } catch (NumberFormatException ignored) {
                    info.versionCode = -1;
                }
                info.modified = file.lastModified();
                return info;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static void sortObbs(List<ObbInfo> obbs) {
        Collections.sort(obbs, new Comparator<ObbInfo>() {
            @Override
            public int compare(ObbInfo a, ObbInfo b) {
                int pa = "Main".equals(a.kind) ? 0 : ("Patch".equals(a.kind) ? 1 : 2);
                int pb = "Main".equals(b.kind) ? 0 : ("Patch".equals(b.kind) ? 1 : 2);
                if (pa != pb) {
                    return pa - pb;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    /**
     * OBBs that are already in Android/obb belong to the installed game, so
     * screens should show them instead of treating them as unknown files.
     */
    private static void attachDestinationObbs(InstallPackage pkg) {
        File dir = new File(Environment.getExternalStorageDirectory(),
                "Android/obb/" + pkg.apk.packageName);
        File[] files = null;
        try {
            files = dir.listFiles();
        } catch (Throwable ignored) {
        }
        if (files == null) {
            return;
        }
        Set<String> names = new HashSet<>();
        for (ObbInfo obb : pkg.obbs) {
            names.add(obb.name.toLowerCase(Locale.US));
        }
        for (File file : files) {
            if (file == null || !file.isFile()) {
                continue;
            }
            String name = file.getName();
            if (!name.toLowerCase(Locale.US).endsWith(".obb")
                    || !names.add(name.toLowerCase(Locale.US))) {
                continue;
            }
            ObbInfo obb = ObbInfo.from(file);
            if (obb == null) {
                continue;
            }
            if (obb.packageName == null) {
                obb.packageName = pkg.apk.packageName;
            }
            pkg.obbs.add(obb);
        }
    }

    private static void scanRemovableVolumes(Context context, Collector collector,
                                             File external, File downloads) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return;
        }
        try {
            StorageManager manager =
                    (StorageManager) context.getSystemService(Context.STORAGE_SERVICE);
            if (manager == null) {
                return;
            }
            for (StorageVolume volume : manager.getStorageVolumes()) {
                File dir = volume.getDirectory();
                if (dir == null || !dir.isDirectory() || !dir.canRead()) {
                    continue;
                }
                if (samePath(dir, external) || samePath(dir, downloads)) {
                    continue;
                }
                // USB drives are usually FAT/exFAT and may contain the whole
                // download folder, so look a little deeper than the primary scan.
                collector.walk(dir, 0, MAX_DEPTH);
            }
        } catch (Throwable ignored) {
        }
    }

    private static boolean samePath(File a, File b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return a.getCanonicalPath().equals(b.getCanonicalPath());
        } catch (IOException ignored) {
            return a.getAbsolutePath().equals(b.getAbsolutePath());
        }
    }
}
