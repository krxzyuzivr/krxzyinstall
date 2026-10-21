package com.krxzy.krxzyinstall;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InstallService extends Service {
    private static final String TAG = "krxzyinstall";

    public static final String ACTION_START = "com.krxzy.krxzyinstall.action.START";
    public static final String ACTION_CANCEL = "com.krxzy.krxzyinstall.action.CANCEL";
    public static final String ACTION_STATE = "com.krxzy.krxzyinstall.action.STATE";
    public static final String ACTION_WAITING = "com.krxzy.krxzyinstall.action.WAITING";
    public static final String ACTION_RESULT = "com.krxzy.krxzyinstall.action.RESULT";
    public static final String ACTION_INSTALL_RESULT = "com.krxzy.krxzyinstall.action.INSTALL_RESULT";

    public static final String EXTRA_APK = "apk";
    public static final String EXTRA_APK_VERSION = "apk_version";
    public static final String EXTRA_PACKAGE = "package";
    public static final String EXTRA_LABEL = "label";
    public static final String EXTRA_OBB_PATHS = "obb_paths";
    public static final String EXTRA_RETAIN_NAMES = "retain_names";
    public static final String EXTRA_MOVE = "move";
    public static final String EXTRA_DELETE_OLD = "delete_old";
    public static final String EXTRA_SKIP_APK = "skip_apk";
    public static final String EXTRA_STATUS = "status";
    public static final String EXTRA_STATUS_MESSAGE = "status_message";

    private static final String CHANNEL = "krxzy_install";
    private static final int NOTIFICATION_ID = 4242;
    private static final int DONE_NOTIFICATION_ID = 4243;

    private ExecutorService executor;
    private final Object installLock = new Object();

    private volatile boolean jobRunning;
    private volatile boolean cancelRequested;
    private volatile boolean installResultReceived;
    private volatile int installStatus = Integer.MIN_VALUE;
    private volatile String installStatusMessage = "";

    private PowerManager.WakeLock wakeLock;
    private long lastNotificationAt;

    private static class Job {
        String apkPath;
        String packageName;
        String label;
        long apkVersionCode;
        ArrayList<String> obbPaths = new ArrayList<>();
        boolean move;
        boolean deleteOld;
        boolean skipApk;
        ArrayList<String> retainNames = new ArrayList<>();
    }

    private static class UserFacingException extends Exception {
        UserFacingException(String message) {
            super(message);
        }
    }

    private static class CanceledException extends Exception {
        CanceledException() {
            super("Canceled");
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        executor = Executors.newSingleThreadExecutor();
        createChannel();
        Notification notification = buildNotification("Preparing...", 0, true, false);
        notification.flags |= Notification.FLAG_ONGOING_EVENT;
        startForegroundCompat(notification);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_NOT_STICKY;
        }

        String action = intent.getAction();
        if (ACTION_RESULT.equals(action)) {
            installStatus = intent.getIntExtra(EXTRA_STATUS, PackageInstaller.STATUS_FAILURE);
            installStatusMessage = intent.getStringExtra(EXTRA_STATUS_MESSAGE);
            if (installStatusMessage == null) {
                installStatusMessage = "";
            }
            installResultReceived = true;
            synchronized (installLock) {
                installLock.notifyAll();
            }
            if (!jobRunning) {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        if (ACTION_WAITING.equals(action)) {
            InstallState state = InstallState.get();
            if (jobRunning) {
                state.phase = InstallState.Phase.WAITING_USER;
                state.message = "Confirm the install in the system window";
                state.touch();
                broadcastState();
                updateNotification(state.message, 0, true, false);
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        if (ACTION_CANCEL.equals(action)) {
            if (jobRunning) {
                cancelRequested = true;
                InstallState state = InstallState.get();
                state.message = "Canceling...";
                state.touch();
                broadcastState();
                synchronized (installLock) {
                    installLock.notifyAll();
                }
            } else {
                stopSelf();
            }
            return START_NOT_STICKY;
        }

        if (!ACTION_START.equals(action)) {
            return START_NOT_STICKY;
        }

        if (jobRunning) {
            return START_NOT_STICKY;
        }

        Job job = new Job();
        job.apkPath = intent.getStringExtra(EXTRA_APK);
        job.packageName = intent.getStringExtra(EXTRA_PACKAGE);
        job.label = intent.getStringExtra(EXTRA_LABEL);
        job.apkVersionCode = intent.getLongExtra(EXTRA_APK_VERSION, 0L);
        job.move = intent.getBooleanExtra(EXTRA_MOVE, true);
        job.deleteOld = intent.getBooleanExtra(EXTRA_DELETE_OLD, false);
        job.skipApk = intent.getBooleanExtra(EXTRA_SKIP_APK, false);
        ArrayList<String> obbPaths = intent.getStringArrayListExtra(EXTRA_OBB_PATHS);
        if (obbPaths != null) {
            job.obbPaths = obbPaths;
        }
        ArrayList<String> retainNames = intent.getStringArrayListExtra(EXTRA_RETAIN_NAMES);
        if (retainNames != null) {
            job.retainNames = retainNames;
        }

        if (job.apkPath == null || job.apkPath.isEmpty()) {
            postResultNotification(false, "No APK was selected.");
            stopSelf();
            return START_NOT_STICKY;
        }

        jobRunning = true;
        cancelRequested = false;
        installResultReceived = false;
        installStatus = Integer.MIN_VALUE;
        installStatusMessage = "";

        InstallState state = InstallState.get();
        state.reset(job.packageName, job.label, job.apkPath, job.obbPaths);
        state.touch();
        broadcastState();

        acquireWakeLock();
        executor.execute(() -> runJob(job));
        return START_NOT_STICKY;
    }

    private void runJob(Job job) {
        InstallState state = InstallState.get();
        try {
            ApkInfo apk = PackageScanner.readApkInfo(this, job.apkPath);
            if (apk == null) {
                throw new UserFacingException("The APK could not be read. It may be corrupted or still downloading.");
            }
            job.packageName = apk.packageName;
            if (job.apkVersionCode <= 0) {
                job.apkVersionCode = apk.versionCode;
            }
            if (job.label == null || job.label.trim().isEmpty()) {
                job.label = apk.label;
            }
            state.packageName = apk.packageName;
            state.label = job.label;
            state.apkPath = job.apkPath;
            state.targetDir = obbDir(apk.packageName).getAbsolutePath();
            state.installedVersion = PackageScanner.getInstalledVersion(this, apk.packageName);
            state.touch();
            broadcastState();
            updateNotification("Preparing " + job.label, 0, true, false);

            if (!Perms.hasFiles(this)) {
                throw new UserFacingException(
                        "File access is missing. Enable all-files access for krxzyinstall and try again.");
            }
            if (!Perms.hasInstall(this)) {
                throw new UserFacingException(
                        "Install access is missing. Enable \\\"Install unknown apps\\\" for krxzyinstall and try again.");
            }
            if (!new File(job.apkPath).isFile()) {
                throw new UserFacingException("The APK file no longer exists.");
            }

            if (job.skipApk) {
                int installed = PackageScanner.getInstalledVersion(this, apk.packageName);
                if (installed < 0) {
                    throw new UserFacingException(
                            "The app is not installed, so the APK must be installed first.");
                }
                state.apkInstalled = true;
                state.installedVersion = installed;
            } else {
                installApk(job, state);
            }

            List<File> obbFiles = resolveObbFiles(job.obbPaths);
            if (!obbFiles.isEmpty()) {
                copyObbs(job, state, obbFiles);
            }

            // Removing "other" OBBs is only safe when at least one OBB was
            // selected. Otherwise an empty selection would wipe the folder.
            if (job.deleteOld && (!job.retainNames.isEmpty() || !obbFiles.isEmpty())) {
                deleteOldObbs(job.packageName, obbFiles, job.retainNames);
            }

            state.apkInstalled = true;
            state.installedVersion = PackageScanner.getInstalledVersion(this, apk.packageName);
            state.phase = InstallState.Phase.DONE;
            state.speedBps = 0;
            state.copiedBytes = state.totalBytes;
            state.currentFile = "";
            state.message = job.label + " is ready.";
            state.touch();
            broadcastState();
            postResultNotification(true, job.label + " installed"
                    + (obbFiles.isEmpty() ? "." : " with " + obbFiles.size() + " OBB file(s)."));
        } catch (CanceledException canceled) {
            state.phase = InstallState.Phase.CANCELED;
            state.canceled = true;
            state.message = "Canceled";
            state.error = "";
            state.speedBps = 0;
            state.touch();
            broadcastState();
            postResultNotification(false, "Install canceled.");
        } catch (UserFacingException error) {
            fail(state, error.getMessage());
        } catch (Throwable error) {
            String message = error.getMessage();
            if (message == null || message.trim().isEmpty()) {
                message = error.getClass().getSimpleName();
            }
            fail(state, "Install failed: " + message);
        } finally {
            jobRunning = false;
            releaseWakeLock();
            stopForegroundCompat();
            stopSelf();
        }
    }

    private void fail(InstallState state, String message) {
        state.phase = InstallState.Phase.FAILED;
        state.error = message == null ? "Install failed." : message;
        state.message = "Install failed";
        state.speedBps = 0;
        state.touch();
        broadcastState();
        postResultNotification(false, state.error);
    }

    private void installApk(Job job, InstallState state)
            throws UserFacingException, CanceledException {
        File apkFile = new File(job.apkPath);
        PackageInstaller installer = getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params =
                new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        params.setAppPackageName(job.packageName);
        params.setSize(apkFile.length());
        params.setInstallReason(PackageManager.INSTALL_REASON_USER);

        int sessionId;
        try {
            sessionId = installer.createSession(params);
        } catch (Throwable error) {
            throw new UserFacingException("Could not start the install session. "
                    + safeMessage(error));
        }

        state.phase = InstallState.Phase.INSTALLING_APK;
        state.message = "Installing the APK";
        state.totalBytes = apkFile.length();
        state.copiedBytes = 0;
        state.speedBps = 0;
        state.touch();
        broadcastState();
        updateNotification("Installing " + job.label, 0, false, true);

        long priorVersion = -1L;
        long priorUpdateTime = -1L;
        try {
            PackageInstaller.Session session = installer.openSession(sessionId);
            try {
                try (InputStream in = new FileInputStream(apkFile);
                     OutputStream out = session.openWrite("base.apk", 0, apkFile.length())) {
                    byte[] buffer = new byte[256 * 1024];
                    long copied = 0;
                    long lastUpdate = 0;
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        if (cancelRequested) {
                            throw new CanceledException();
                        }
                        out.write(buffer, 0, read);
                        copied += read;
                        state.copiedBytes = copied;
                        long now = SystemClock.elapsedRealtime();
                        if (now - lastUpdate > 400) {
                            lastUpdate = now;
                            state.touch();
                            broadcastState();
                            updateNotification("Installing " + job.label,
                                    Format.percent(copied, state.totalBytes), false, true);
                        }
                    }
                    out.flush();
                    session.fsync(out);
                }

                synchronized (installLock) {
                    installResultReceived = false;
                    installStatus = Integer.MIN_VALUE;
                    installStatusMessage = "";
                }

                Intent callback = new Intent(this, InstallResultReceiver.class);
                callback.setAction(ACTION_INSTALL_RESULT);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) {
                    flags |= PendingIntent.FLAG_MUTABLE;
                }
                PendingIntent pending = PendingIntent.getBroadcast(this, sessionId, callback, flags);

                state.phase = InstallState.Phase.WAITING_USER;
                state.message = "Confirm the install in the system window";
                state.touch();
                broadcastState();
                updateNotification("Tap Install in the system window", 0, true, false);

                // Remember the currently installed version so a successful
                // out-of-band install can be recognized even if the headset
                // never delivers the session result broadcast.
                priorVersion = installedVersionCode(job.packageName);
                priorUpdateTime = installedUpdateTime(job.packageName);
                session.commit(pending.getIntentSender());
            } finally {
                try {
                    session.close();
                } catch (Throwable ignored) {
                }
            }
        } catch (CanceledException canceled) {
            try {
                installer.abandonSession(sessionId);
            } catch (Throwable ignored) {
            }
            throw canceled;
        } catch (Throwable error) {
            throw new UserFacingException("Could not install the APK. " + safeMessage(error));
        }

        long deadline = SystemClock.elapsedRealtime() + 30L * 60L * 1000L;
        while (!cancelRequested && SystemClock.elapsedRealtime() < deadline) {
            synchronized (installLock) {
                if (installResultReceived) {
                    break;
                }
            }
            if (apkLanded(job, priorVersion, priorUpdateTime)) {
                synchronized (installLock) {
                    if (!installResultReceived) {
                        installResultReceived = true;
                        installStatus = PackageInstaller.STATUS_SUCCESS;
                        installStatusMessage = "";
                        Log.i(TAG, "No install result was delivered, but "
                                + job.packageName + " is installed at version "
                                + job.apkVersionCode + ".");
                    }
                }
                break;
            }
            synchronized (installLock) {
                if (!installResultReceived) {
                    try {
                        installLock.wait(1200);
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }

        if (cancelRequested) {
            throw new CanceledException();
        }

        boolean landed = apkLanded(job, priorVersion, priorUpdateTime);
        if (!installResultReceived) {
            if (!landed) {
                throw new UserFacingException(
                        "Timed out waiting for the system installer. Open the notification, "
                                + "tap Install, then try again.");
            }
            installResultReceived = true;
            installStatus = PackageInstaller.STATUS_SUCCESS;
            installStatusMessage = "";
        }

        if (installStatus != PackageInstaller.STATUS_SUCCESS && landed) {
            // Some headset builds finish the install out-of-band and still
            // report a failure to the calling app.
            Log.w(TAG, "Package " + job.packageName + " is installed even though the "
                    + "system installer reported status " + installStatus + ".");
            installStatus = PackageInstaller.STATUS_SUCCESS;
            installStatusMessage = "";
        }

        if (installStatus != PackageInstaller.STATUS_SUCCESS
                && !isDefinitiveFailure(installStatus)) {
            // A few OEM installers close their window and finish the package
            // install a moment later. Give the package manager a short grace
            // period so a false failure does not skip the OBB copy.
            long graceDeadline = SystemClock.elapsedRealtime() + 12000L;
            while (!cancelRequested && SystemClock.elapsedRealtime() < graceDeadline) {
                if (apkLanded(job, priorVersion, priorUpdateTime)) {
                    landed = true;
                    break;
                }
                try {
                    Thread.sleep(750L);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            if (landed) {
                Log.w(TAG, "Package " + job.packageName + " landed after the installer "
                        + "reported status " + installStatus + "; continuing.");
                installStatus = PackageInstaller.STATUS_SUCCESS;
                installStatusMessage = "";
            }
        }

        if (installStatus != PackageInstaller.STATUS_SUCCESS) {
            throw new UserFacingException(installFailure(installStatus, installStatusMessage));
        }

        state.apkInstalled = true;
        state.installedVersion = PackageScanner.getInstalledVersion(this, job.packageName);
        state.totalBytes = 0;
        state.copiedBytes = 0;
        state.message = "APK installed";
        state.touch();
        broadcastState();
        updateNotification("APK installed. Preparing OBB files...", 0, true, false);
    }

    private void copyObbs(Job job, InstallState state, List<File> files)
            throws UserFacingException, CanceledException {
        File destDir = obbDir(job.packageName);
        ensureDestination(destDir);

        Set<String> names = new HashSet<>();
        long total = 0;
        for (File file : files) {
            if (!names.add(file.getName().toLowerCase(Locale.US))) {
                throw new UserFacingException(
                        "Two selected OBB files have the same name (" + file.getName() + ").");
            }
            total += file.length();
        }

        state.phase = InstallState.Phase.COPYING;
        state.message = job.move ? "Moving OBB files" : "Copying OBB files";
        state.fileCount = files.size();
        state.fileIndex = 0;
        state.totalBytes = total;
        state.copiedBytes = 0;
        state.speedBps = 0;
        state.touch();
        broadcastState();

        long completed = 0;
        final long totalBytes = total;
        final long[] speedClock = {SystemClock.elapsedRealtime()};
        final long[] speedBytes = {0};

        for (int i = 0; i < files.size(); i++) {
            File source = files.get(i);
            if (!source.isFile()) {
                throw new UserFacingException("Missing OBB file: " + source.getName());
            }
            final long sourceLength = source.length();
            if (cancelRequested) {
                throw new CanceledException();
            }
            File destination = new File(destDir, source.getName());
            state.currentFile = source.getName();
            state.fileIndex = i + 1;
            state.message = (job.move ? "Moving " : "Copying ") + source.getName();
            state.touch();
            broadcastState();
            updateNotification(state.message, Format.percent(completed, total), false, true);

            if (sameFile(source, destination)) {
                completed += sourceLength;
                state.copiedBytes = completed;
                state.speedBps = 0;
                state.touch();
                broadcastState();
                continue;
            }

            boolean moved = false;
            if (job.move) {
                try {
                    moved = source.renameTo(destination);
                } catch (Throwable ignored) {
                    moved = false;
                }
                if (moved && (!destination.isFile() || destination.length() != sourceLength)) {
                    // A rename that produced a bad destination is not safe. The data
                    // is at the destination path; report it instead of deleting it.
                    throw new UserFacingException(
                            "Could not verify the moved file: " + source.getName());
                }
            }

            if (moved) {
                completed += sourceLength;
                state.copiedBytes = completed;
                state.speedBps = 0;
                state.touch();
                broadcastState();
                continue;
            }

            File temp = new File(destDir, source.getName() + ".krxpart");
            FileUtils.deleteQuietly(temp);
            try {
                long margin = 64L * 1024L * 1024L;
                long free = destDir.getUsableSpace();
                if (free < sourceLength + margin) {
                    long reclaimable = destination.isFile() ? destination.length() : 0;
                    if (free + reclaimable < sourceLength + margin) {
                        throw new UserFacingException(
                                "Not enough free space for " + source.getName() + ". Free: "
                                        + Format.bytes(free) + ", needed: " + Format.bytes(sourceLength)
                                        + ". Turn on Move, or free some storage.");
                    }
                    // Replacing a large OBB needs room for both copies. When
                    // only the new file fits, remove the old destination first.
                    // The source is not deleted until the copy succeeds.
                    if (!destination.delete()) {
                        throw new UserFacingException(
                                "Could not replace the existing " + destination.getName()
                                        + " to make room for the new copy.");
                    }
                }
                try (InputStream in = new FileInputStream(source);
                     OutputStream out = new FileOutputStream(temp)) {
                    final long base = completed;
                    final long[] lastUi = {0};
                    boolean ok = FileUtils.copy(in, out, sourceLength,
                            (copied, fileTotal) -> {
                                if (cancelRequested) {
                                    return false;
                                }
                                state.copiedBytes = base + copied;
                                long now = SystemClock.elapsedRealtime();
                                if (now - lastUi[0] > 450) {
                                    long elapsed = now - speedClock[0];
                                    if (elapsed > 0) {
                                        long delta = state.copiedBytes - speedBytes[0];
                                        state.speedBps = Math.max(0, (delta * 1000L) / elapsed);
                                    }
                                    speedClock[0] = now;
                                    speedBytes[0] = state.copiedBytes;
                                    lastUi[0] = now;
                                    state.touch();
                                    broadcastState();
                                    updateNotification(state.message,
                                            Format.percent(state.copiedBytes, totalBytes), false, true);
                                }
                                return true;
                            });
                    if (!ok) {
                        throw new CanceledException();
                    }
                    out.flush();
                    try {
                        ((FileOutputStream) out).getFD().sync();
                    } catch (Throwable ignored) {
                    }
                }
                if (temp.length() != sourceLength) {
                    throw new UserFacingException(
                            "The copy of " + source.getName() + " was incomplete.");
                }
                if (destination.exists() && !destination.delete()) {
                    throw new UserFacingException(
                            "Could not replace the existing " + destination.getName() + ".");
                }
                if (!temp.renameTo(destination)) {
                    throw new UserFacingException(
                            "Could not finalize " + destination.getName() + ".");
                }
                if (job.move) {
                    FileUtils.deleteQuietly(source);
                }
                completed += sourceLength;
                state.copiedBytes = completed;
                state.speedBps = 0;
                state.touch();
                broadcastState();
            } catch (CanceledException canceled) {
                FileUtils.deleteQuietly(temp);
                throw canceled;
            } catch (UserFacingException error) {
                FileUtils.deleteQuietly(temp);
                throw error;
            } catch (Throwable error) {
                FileUtils.deleteQuietly(temp);
                throw new UserFacingException("Could not copy " + source.getName() + ": "
                        + safeMessage(error));
            }
        }

        state.speedBps = 0;
        state.copiedBytes = total;
        state.currentFile = "";
        state.touch();
        broadcastState();
    }

    private void ensureDestination(File destDir) throws UserFacingException {
        if (!destDir.exists() && !destDir.mkdirs() && !destDir.exists()) {
            throw new UserFacingException(
                    "Could not create Android/obb/" + destDir.getName()
                            + ". Enable \\\"Install unknown apps\\\" for krxzyinstall.");
        }
        File probe = new File(destDir, ".krxzy_write_test");
        try {
            if (!probe.createNewFile() && !probe.exists()) {
                throw new IOException("probe failed");
            }
        } catch (Throwable error) {
            throw new UserFacingException(
                    "Cannot write to the game's OBB folder. Enable \\\"Install unknown apps\\\" for krxzyinstall.");
        } finally {
            FileUtils.deleteQuietly(probe);
        }
        // Remove half-written copies left behind by a killed process. They
        // are never valid OBBs and can waste several GB.
        try {
            File[] stale = destDir.listFiles();
            if (stale != null) {
                for (File file : stale) {
                    if (file.isFile()
                            && file.getName().toLowerCase(Locale.US).endsWith(".krxpart")) {
                        FileUtils.deleteQuietly(file);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void deleteOldObbs(String packageName, List<File> keepFiles,
                               List<String> retainNames) {
        try {
            File destDir = obbDir(packageName);
            Set<String> keep = new HashSet<>();
            for (File file : keepFiles) {
                keep.add(file.getName().toLowerCase(Locale.US));
            }
            // Files a previous attempt already placed there are still selected,
            // so they must survive a retry with "remove old" enabled.
            if (retainNames != null) {
                for (String name : retainNames) {
                    if (name != null) {
                        keep.add(name.toLowerCase(Locale.US));
                    }
                }
            }
            File[] existing = destDir.listFiles();
            if (existing == null) {
                return;
            }
            for (File file : existing) {
                if (!file.isFile()) {
                    continue;
                }
                String name = file.getName();
                String key = name.toLowerCase(Locale.US);
                if (key.endsWith(".obb") && !keep.contains(key)) {
                    FileUtils.deleteQuietly(file);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private List<File> resolveObbFiles(List<String> paths) throws UserFacingException {
        List<File> files = new ArrayList<>();
        Set<String> added = new HashSet<>();
        for (String path : paths) {
            if (path == null || path.trim().isEmpty()) {
                continue;
            }
            File file = new File(path);
            if (!file.isFile()) {
                throw new UserFacingException("An OBB file is missing: " + file.getName());
            }
            if (added.add(file.getAbsolutePath())) {
                files.add(file);
            }
        }
        return files;
    }

    private File obbDir(String packageName) {
        return new File(Environment.getExternalStorageDirectory(),
                "Android/obb/" + packageName);
    }

    private boolean apkLanded(Job job, long priorVersion, long priorUpdateTime) {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(job.packageName, 0);
            if (info.getLongVersionCode() != job.apkVersionCode) {
                return false;
            }
            if (priorVersion != job.apkVersionCode) {
                // Fresh install or an update/downgrade: the installed version
                // now matches the APK, so the package definitely landed.
                return true;
            }
            // Reinstalling the same version needs a timestamp check, otherwise
            // the old install would look like a successful new one.
            return info.lastUpdateTime > priorUpdateTime;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private long installedVersionCode(String packageName) {
        try {
            return getPackageManager().getPackageInfo(packageName, 0).getLongVersionCode();
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private long installedUpdateTime(String packageName) {
        try {
            return getPackageManager().getPackageInfo(packageName, 0).lastUpdateTime;
        } catch (Throwable ignored) {
            return -1L;
        }
    }

    private boolean isDefinitiveFailure(int status) {
        return status == PackageInstaller.STATUS_FAILURE_BLOCKED
                || status == PackageInstaller.STATUS_FAILURE_CONFLICT
                || status == PackageInstaller.STATUS_FAILURE_INCOMPATIBLE
                || status == PackageInstaller.STATUS_FAILURE_INVALID
                || status == PackageInstaller.STATUS_FAILURE_STORAGE;
    }

    private String installFailure(int status, String message) {
        String detail = message == null ? "" : message.trim();
        String suffix = detail.isEmpty() ? "" : " (" + detail + ")";
        switch (status) {
            case PackageInstaller.STATUS_FAILURE_ABORTED:
                return "Install canceled.";
            case PackageInstaller.STATUS_FAILURE_BLOCKED:
                return "The system blocked the install. Allow \\\"Install unknown apps\\\" for krxzyinstall.";
            case PackageInstaller.STATUS_FAILURE_CONFLICT:
                return "This APK conflicts with the installed app (different signature). "
                        + "Uninstall the existing app first, then install again.";
            case PackageInstaller.STATUS_FAILURE_INCOMPATIBLE:
                return "This APK is not compatible with this headset." + suffix;
            case PackageInstaller.STATUS_FAILURE_INVALID:
                return "The APK is invalid or corrupted." + suffix;
            case PackageInstaller.STATUS_FAILURE_STORAGE:
                return "Not enough storage to install the APK.";
            case PackageInstaller.STATUS_FAILURE:
                return "The system installer failed." + suffix;
            default:
                return "Install failed (code " + status + ")." + suffix;
        }
    }

    private String safeMessage(Throwable error) {
        String message = error.getMessage();
        if (message == null || message.trim().isEmpty()) {
            return error.getClass().getSimpleName();
        }
        return message;
    }

    private boolean sameFile(File a, File b) {
        try {
            return a.getCanonicalPath().equalsIgnoreCase(b.getCanonicalPath());
        } catch (Throwable ignored) {
            return a.getAbsolutePath().equalsIgnoreCase(b.getAbsolutePath());
        }
    }

    private void acquireWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(POWER_SERVICE);
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "krxzyinstall:job");
            wakeLock.acquire(6L * 60L * 60L * 1000L);
        } catch (Throwable ignored) {
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
        } catch (Throwable ignored) {
        }
        wakeLock = null;
    }

    private void createChannel() {
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) {
            return;
        }
        NotificationChannel channel = new NotificationChannel(
                CHANNEL, "Game installs", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("APK and OBB install progress");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification(String text, int percent,
                                           boolean indeterminate, boolean showCancel) {
        Intent open = new Intent(this, MainActivity.class);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 31) {
            flags |= PendingIntent.FLAG_IMMUTABLE;
        }
        PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);

        Notification.Builder builder = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_install)
                .setContentTitle("krxzyinstall")
                .setContentText(text)
                .setContentIntent(content)
                .setOnlyAlertOnce(true)
                .setOngoing(isActivePhase())
                .setProgress(100, Math.max(0, Math.min(100, percent)), indeterminate);

        if (showCancel) {
            Intent cancel = new Intent(this, InstallService.class);
            cancel.setAction(ACTION_CANCEL);
            PendingIntent cancelIntent;
            if (Build.VERSION.SDK_INT >= 26) {
                cancelIntent = PendingIntent.getForegroundService(this, 1, cancel, flags);
            } else {
                cancelIntent = PendingIntent.getService(this, 1, cancel, flags);
            }
            builder.addAction(new Notification.Action.Builder(
                    Icon.createWithResource(this, R.drawable.ic_close_24),
                    "Cancel", cancelIntent).build());
        }
        return builder.build();
    }

    private boolean isActivePhase() {
        InstallState.Phase phase = InstallState.get().phase;
        return phase == InstallState.Phase.PREPARING
                || phase == InstallState.Phase.INSTALLING_APK
                || phase == InstallState.Phase.WAITING_USER
                || phase == InstallState.Phase.COPYING;
    }

    private void updateNotification(String text, int percent,
                                    boolean indeterminate, boolean showCancel) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastNotificationAt < 350 && !indeterminate) {
            return;
        }
        lastNotificationAt = now;
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager != null) {
                manager.notify(NOTIFICATION_ID,
                        buildNotification(text, percent, indeterminate, showCancel));
            }
        } catch (Throwable ignored) {
        }
    }

    private void postResultNotification(boolean success, String text) {
        try {
            NotificationManager manager =
                    (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
            if (manager == null) {
                return;
            }
            Intent open = new Intent(this, MainActivity.class);
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 31) {
                flags |= PendingIntent.FLAG_IMMUTABLE;
            }
            PendingIntent content = PendingIntent.getActivity(this, 0, open, flags);
            String safeText = text == null ? "" : text;
            Notification notification = new Notification.Builder(this, CHANNEL)
                    .setSmallIcon(success ? R.drawable.ic_check_24 : R.drawable.ic_warn_24)
                    .setContentTitle(success ? "Install complete" : "Install failed")
                    .setContentText(safeText)
                    .setStyle(new Notification.BigTextStyle().bigText(safeText))
                    .setContentIntent(content)
                    .setAutoCancel(true)
                    .build();
            manager.notify(DONE_NOTIFICATION_ID, notification);
        } catch (Throwable ignored) {
        }
    }

    private void startForegroundCompat(Notification notification) {
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIFICATION_ID, notification);
        }
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Throwable ignored) {
        }
    }

    private void broadcastState() {
        try {
            Intent intent = new Intent(ACTION_STATE);
            intent.setPackage(getPackageName());
            sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    @Override
    public void onDestroy() {
        jobRunning = false;
        releaseWakeLock();
        if (executor != null) {
            executor.shutdownNow();
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
