package com.krxzy.krxzyinstall;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-process state shared by the foreground service and the activities.
 * The service broadcasts ACTION_STATE after every meaningful change.
 */
public final class InstallState {
    public enum Phase {
        IDLE,
        PREPARING,
        INSTALLING_APK,
        WAITING_USER,
        COPYING,
        DONE,
        FAILED,
        CANCELED
    }

    private static final InstallState INSTANCE = new InstallState();

    public volatile Phase phase = Phase.IDLE;
    public volatile long jobId;
    public volatile String packageName = "";
    public volatile String label = "";
    public volatile String apkPath = "";
    public volatile String targetDir = "";
    public volatile String message = "";
    public volatile String error = "";
    public volatile String currentFile = "";
    public volatile long totalBytes;
    public volatile long copiedBytes;
    public volatile int fileIndex;
    public volatile int fileCount;
    public volatile long speedBps;
    public volatile boolean apkInstalled;
    public volatile boolean canceled;
    public volatile int installedVersion = -1;
    public volatile long updatedAt;
    private volatile List<String> jobObbPaths = Collections.emptyList();

    private InstallState() {
    }

    public static InstallState get() {
        return INSTANCE;
    }

    public void reset(String packageName, String label, String apkPath, List<String> obbPaths) {
        this.jobId = System.currentTimeMillis();
        this.packageName = packageName == null ? "" : packageName;
        this.label = label == null ? "" : label;
        this.apkPath = apkPath == null ? "" : apkPath;
        this.targetDir = "";
        this.message = "";
        this.error = "";
        this.currentFile = "";
        this.totalBytes = 0;
        this.copiedBytes = 0;
        this.fileIndex = 0;
        this.fileCount = 0;
        this.speedBps = 0;
        this.apkInstalled = false;
        this.canceled = false;
        this.installedVersion = -1;
        this.phase = Phase.PREPARING;
        this.jobObbPaths = obbPaths == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(obbPaths));
        this.updatedAt = System.currentTimeMillis();
    }

    public List<String> jobObbPaths() {
        return jobObbPaths;
    }

    public boolean isActive() {
        Phase p = phase;
        return p == Phase.PREPARING
                || p == Phase.INSTALLING_APK
                || p == Phase.WAITING_USER
                || p == Phase.COPYING;
    }

    public boolean isFinished() {
        Phase p = phase;
        return p == Phase.DONE || p == Phase.FAILED || p == Phase.CANCELED;
    }

    public synchronized void touch() {
        updatedAt = System.currentTimeMillis();
    }
}
