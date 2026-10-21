package com.krxzy.krxzyinstall;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_APK = 100;
    private static final int REQ_DETAIL = 101;

    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<InstallPackage> packages = new ArrayList<>();

    private Prefs prefs;
    private PermissionRow filesRow;
    private PermissionRow installRow;
    private LinearLayout packageList;
    private TextView scanStatus;
    private TextView setupNote;
    private TextView emptyTitle;
    private TextView emptyBody;
    private TextView activeTitle;
    private TextView activeSub;
    private ImageView emptyIcon;
    private View emptyCard;
    private View activeCard;
    private Button emptyButton;
    private Button refreshButton;

    private boolean loading;
    private long lastScanAt;
    private long lastDoneJobId = -1;
    private BroadcastReceiver stateReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        prefs = new Prefs(this);

        LinearLayout permissionRows = findViewById(R.id.permissionRows);
        filesRow = new PermissionRow(this, permissionRows,
                getString(R.string.file_access), getString(R.string.file_access_desc),
                () -> Perms.openFilesSettings(this));
        installRow = new PermissionRow(this, permissionRows,
                getString(R.string.install_access), getString(R.string.install_access_desc),
                () -> Perms.openInstallSettings(this));

        packageList = findViewById(R.id.packageList);
        scanStatus = findViewById(R.id.scanStatus);
        setupNote = findViewById(R.id.setupNote);
        emptyTitle = findViewById(R.id.emptyTitle);
        emptyBody = findViewById(R.id.emptyBody);
        emptyIcon = findViewById(R.id.emptyIcon);
        emptyCard = findViewById(R.id.emptyCard);
        activeCard = findViewById(R.id.activeCard);
        emptyButton = findViewById(R.id.emptyButton);
        refreshButton = findViewById(R.id.refreshButton);
        activeTitle = findViewById(R.id.activeTitle);
        activeSub = findViewById(R.id.activeSub);

        findViewById(R.id.helpButton).setOnClickListener(view -> Ui.showHelp(this));
        findViewById(R.id.creditsButton).setOnClickListener(view -> Ui.showCredits(this));
        refreshButton.setOnClickListener(view -> scan(true));
        emptyButton.setOnClickListener(view -> {
            if (!Perms.hasFiles(this)) {
                Perms.openFilesSettings(this);
            } else {
                pickApk();
            }
        });
        activeCard.setOnClickListener(view -> openActiveInstall());

        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateActiveCard();
                InstallState state = InstallState.get();
                if (state.phase == InstallState.Phase.DONE
                        && state.jobId != lastDoneJobId && Perms.hasFiles(MainActivity.this)) {
                    lastDoneJobId = state.jobId;
                    scan(false);
                }
            }
        };

        scan(false);
    }

    @Override
    protected void onStart() {
        super.onStart();
        IntentFilter filter = new IntentFilter(InstallService.ACTION_STATE);
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stateReceiver, filter);
        }
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(stateReceiver);
        } catch (Throwable ignored) {
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissions();
        updateActiveCard();
        if (Perms.hasFiles(this) && !InstallState.get().isActive()
                && System.currentTimeMillis() - lastScanAt > 1200) {
            scan(false);
        }
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_APK && resultCode == RESULT_OK && data != null) {
            ArrayList<String> paths = data.getStringArrayListExtra(FileBrowserActivity.EXTRA_RESULT_PATHS);
            if (paths != null && !paths.isEmpty()) {
                prefs.addManualApk(paths.get(0));
                scan(true);
            }
        } else if (requestCode == REQ_DETAIL) {
            scan(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        updatePermissions();
        if (Perms.hasFiles(this)) {
            scan(true);
        }
    }

    private void updatePermissions() {
        boolean files = Perms.hasFiles(this);
        boolean install = Perms.hasInstall(this);
        filesRow.update(files);
        installRow.update(install);
        if (files && install) {
            setupNote.setText("Ready. Downloads are scanned automatically.");
        } else {
            setupNote.setText(R.string.setup_note);
        }
        if (!files) {
            packages.clear();
            packageList.removeAllViews();
            renderEmptyState();
        } else if (packages.isEmpty() && !loading) {
            renderEmptyState();
        }
    }

    private void scan(boolean force) {
        if (loading) {
            return;
        }
        if (!Perms.hasFiles(this)) {
            renderEmptyState();
            return;
        }
        long now = System.currentTimeMillis();
        if (!force && now - lastScanAt < 1000) {
            return;
        }
        loading = true;
        scanStatus.setText(R.string.scanning);
        refreshButton.setEnabled(false);
        if (packages.isEmpty()) {
            emptyCard.setVisibility(View.GONE);
        }

        background.execute(() -> {
            PackageScanner.ScanResult result = null;
            Throwable failure = null;
            try {
                result = PackageScanner.scan(MainActivity.this, prefs);
            } catch (Throwable error) {
                failure = error;
            }
            final PackageScanner.ScanResult scanResult = result;
            final Throwable scanFailure = failure;
            main.post(() -> {
                loading = false;
                lastScanAt = System.currentTimeMillis();
                refreshButton.setEnabled(true);
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (scanFailure != null) {
                    scanStatus.setText("Scan failed");
                    Ui.toast(this, "Scan failed: " + scanFailure.getMessage());
                    if (packages.isEmpty()) {
                        renderEmptyState();
                    }
                    return;
                }
                packages.clear();
                packages.addAll(scanResult.packages);
                if (packages.isEmpty()) {
                    scanStatus.setText("0 found");
                } else {
                    String suffix = scanResult.invalidApks > 0
                            ? "  |  " + scanResult.invalidApks + " unreadable" : "";
                    scanStatus.setText(packages.size() + " found" + suffix);
                }
                renderPackages();
                updateActiveCard();
            });
        });
    }

    private void renderPackages() {
        packageList.removeAllViews();
        if (packages.isEmpty()) {
            renderEmptyState();
            return;
        }
        emptyCard.setVisibility(View.GONE);
        LayoutInflater inflater = LayoutInflater.from(this);
        for (InstallPackage pkg : packages) {
            View card = inflater.inflate(R.layout.item_package, packageList, false);
            ImageView icon = card.findViewById(R.id.pkgIcon);
            if (pkg.apk.icon != null) {
                icon.setImageDrawable(pkg.apk.icon);
            } else {
                icon.setImageResource(R.drawable.ic_apk_24);
            }

            TextView name = card.findViewById(R.id.pkgName);
            name.setText(pkg.displayName);
            name.setMaxLines(2);

            TextView meta = card.findViewById(R.id.pkgMeta);
            String metaText = pkg.apk.packageName + "  |  "
                    + Format.version(pkg.apk.versionName, pkg.apk.versionCode);
            if (pkg.isInstalled()) {
                metaText += "  |  installed " + pkg.installedVersionCode;
            }
            meta.setText(metaText);

            TextView apkBadge = card.findViewById(R.id.apkBadge);
            Ui.chip(apkBadge, "APK " + Format.bytes(pkg.apk.size),
                    R.drawable.bg_chip_blue, getColor(R.color.blue));

            TextView obbBadge = card.findViewById(R.id.obbBadge);
            if (pkg.obbs.isEmpty()) {
                Ui.chip(obbBadge, getString(R.string.apk_only),
                        R.drawable.bg_chip_neutral, getColor(R.color.muted));
            } else {
                String obbText = pkg.obbs.size() + (pkg.obbs.size() == 1 ? " OBB  " : " OBBs  ")
                        + Format.bytes(pkg.obbSize());
                Ui.chip(obbBadge, obbText, R.drawable.bg_chip_good, getColor(R.color.accent));
            }

            TextView warnBadge = card.findViewById(R.id.warnBadge);
            if (pkg.hasVersionMismatch()) {
                warnBadge.setVisibility(View.VISIBLE);
                Ui.chip(warnBadge, "OBB VERSION MISMATCH",
                        R.drawable.bg_chip_warn, getColor(R.color.warn));
            } else if (pkg.isDowngrade()) {
                warnBadge.setVisibility(View.VISIBLE);
                Ui.chip(warnBadge, "DOWNGRADE", R.drawable.bg_chip_bad, getColor(R.color.danger));
            } else if (pkg.isSameVersionInstalled()) {
                warnBadge.setVisibility(View.VISIBLE);
                Ui.chip(warnBadge, "INSTALLED", R.drawable.bg_chip_good, getColor(R.color.accent));
            } else {
                warnBadge.setVisibility(View.GONE);
            }

            TextView size = card.findViewById(R.id.pkgSize);
            size.setText(Format.bytes(pkg.totalSize()));

            card.setOnClickListener(view -> openDetail(pkg));
            packageList.addView(card);
        }
    }

    private void renderEmptyState() {
        if (emptyCard == null) {
            return;
        }
        emptyCard.setVisibility(View.VISIBLE);
        if (!Perms.hasFiles(this)) {
            emptyIcon.setImageResource(R.drawable.ic_warn_24);
            emptyTitle.setText(R.string.no_file_access_title);
            emptyBody.setText(R.string.no_file_access_body);
            emptyButton.setText(R.string.allow_file_access);
        } else {
            emptyIcon.setImageResource(R.drawable.ic_download_24);
            emptyTitle.setText(R.string.no_games_title);
            emptyBody.setText(R.string.no_games_body);
            emptyButton.setText(R.string.browse_for_apk);
        }
    }

    private void openDetail(InstallPackage pkg) {
        ArrayList<String> paths = new ArrayList<>();
        for (ObbInfo obb : pkg.obbs) {
            paths.add(obb.path);
        }
        Intent intent = new Intent(this, InstallActivity.class);
        intent.putExtra(InstallActivity.EXTRA_APK_PATH, pkg.apk.path);
        intent.putStringArrayListExtra(InstallActivity.EXTRA_OBB_PATHS, paths);
        startActivityForResult(intent, REQ_DETAIL);
    }

    private void openActiveInstall() {
        InstallState state = InstallState.get();
        if (state.apkPath == null || state.apkPath.isEmpty()) {
            return;
        }
        Intent intent = new Intent(this, InstallActivity.class);
        intent.putExtra(InstallActivity.EXTRA_APK_PATH, state.apkPath);
        intent.putStringArrayListExtra(InstallActivity.EXTRA_OBB_PATHS,
                new ArrayList<>(state.jobObbPaths()));
        startActivityForResult(intent, REQ_DETAIL);
    }

    private void pickApk() {
        Intent intent = new Intent(this, FileBrowserActivity.class);
        intent.putExtra(FileBrowserActivity.EXTRA_MODE, FileBrowserActivity.MODE_APK);
        startActivityForResult(intent, REQ_APK);
    }

    private void updateActiveCard() {
        InstallState state = InstallState.get();
        if (!state.isActive()) {
            activeCard.setVisibility(View.GONE);
            return;
        }
        activeCard.setVisibility(View.VISIBLE);
        String name = state.label == null || state.label.trim().isEmpty()
                ? state.packageName : state.label;
        activeTitle.setText("Installing " + name);
        activeSub.setText(activeSummary(state));
    }

    private String activeSummary(InstallState state) {
        switch (state.phase) {
            case PREPARING:
                return "Preparing...";
            case INSTALLING_APK:
                return "Installing APK  |  "
                        + Format.percent(state.copiedBytes, state.totalBytes) + "%";
            case WAITING_USER:
                return "Confirm the install in the system window";
            case COPYING:
                String file = state.currentFile == null ? "" : state.currentFile;
                return "OBB " + state.fileIndex + " of " + state.fileCount
                        + "  |  " + Format.percent(state.copiedBytes, state.totalBytes)
                        + "%\n" + file;
            default:
                return state.message == null ? "" : state.message;
        }
    }
}
