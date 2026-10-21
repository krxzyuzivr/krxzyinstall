package com.krxzy.krxzyinstall;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class InstallActivity extends Activity {
    public static final String EXTRA_APK_PATH = "apk_path";
    public static final String EXTRA_OBB_PATHS = "obb_paths";

    private static final int REQ_OBB = 301;
    private static final int REQ_NOTIFICATIONS = 302;

    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<ObbInfo> obbs = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();
    private final LinkedHashSet<String> manualPaths = new LinkedHashSet<>();
    private final LinkedHashSet<String> activeJobObbs = new LinkedHashSet<>();

    private Prefs prefs;
    private InstallPackage pkg;
    private String apkPath;
    private boolean loaded;
    private boolean busy;
    private boolean pendingInstall;
    private boolean notificationRequested;
    private boolean optionsInitialized;
    private boolean receiverRegistered;
    private boolean cancelClicked;
    private boolean spaceOk = true;
    private int oldObbCount;
    private long oldObbBytes;
    private InstallState.Phase lastPhase = InstallState.Phase.IDLE;

    private ScrollView installScroll;
    private View missingPermBanner;
    private TextView missingPermText;
    private Button missingPermButton;
    private ImageView gameIcon;
    private TextView gameName;
    private TextView gamePackage;
    private TextView versionChip;
    private TextView installedChip;
    private TextView apkName;
    private TextView apkMeta;
    private TextView apkSize;
    private TextView obbSummary;
    private Button addObbButton;
    private LinearLayout obbList;
    private TextView obbEmpty;
    private TextView destPath;
    private TextView destFree;
    private TextView oldObbInfo;
    private CheckBox moveCheck;
    private CheckBox deleteOldCheck;
    private View progressCard;
    private TextView progressPhase;
    private TextView progressPercent;
    private ProgressBar progressBar;
    private TextView progressDetail;
    private TextView progressSpeed;
    private Button cancelButton;
    private View resultCard;
    private ImageView resultIcon;
    private TextView resultTitle;
    private TextView resultMessage;
    private Button launchButton;
    private Button retryObbButton;
    private Button uninstallButton;
    private Button cleanupButton;
    private TextView bottomTitle;
    private TextView bottomSub;
    private Button installButton;
    private TextView topStatus;
    private TextView headerSub;

    private BroadcastReceiver stateReceiver;

    private static class UserError extends Exception {
        UserError(String message) {
            super(message);
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_install);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        apkPath = getIntent().getStringExtra(EXTRA_APK_PATH);
        if (apkPath == null || apkPath.trim().isEmpty()) {
            Ui.toast(this, "No APK was selected.");
            finish();
            return;
        }
        prefs = new Prefs(this);

        installScroll = findViewById(R.id.installScroll);
        missingPermBanner = findViewById(R.id.missingPermBanner);
        missingPermText = findViewById(R.id.missingPermText);
        missingPermButton = findViewById(R.id.missingPermButton);
        gameIcon = findViewById(R.id.gameIcon);
        gameName = findViewById(R.id.gameName);
        gamePackage = findViewById(R.id.gamePackage);
        versionChip = findViewById(R.id.versionChip);
        installedChip = findViewById(R.id.installedChip);
        apkName = findViewById(R.id.apkName);
        apkMeta = findViewById(R.id.apkMeta);
        apkSize = findViewById(R.id.apkSize);
        obbSummary = findViewById(R.id.obbSummary);
        addObbButton = findViewById(R.id.addObbButton);
        obbList = findViewById(R.id.obbList);
        obbEmpty = findViewById(R.id.obbEmpty);
        destPath = findViewById(R.id.destPath);
        destFree = findViewById(R.id.destFree);
        oldObbInfo = findViewById(R.id.oldObbInfo);
        moveCheck = findViewById(R.id.moveCheck);
        deleteOldCheck = findViewById(R.id.deleteOldCheck);
        progressCard = findViewById(R.id.progressCard);
        progressPhase = findViewById(R.id.progressPhase);
        progressPercent = findViewById(R.id.progressPercent);
        progressBar = findViewById(R.id.progressBar);
        progressDetail = findViewById(R.id.progressDetail);
        progressSpeed = findViewById(R.id.progressSpeed);
        cancelButton = findViewById(R.id.cancelButton);
        resultCard = findViewById(R.id.resultCard);
        resultIcon = findViewById(R.id.resultIcon);
        resultTitle = findViewById(R.id.resultTitle);
        resultMessage = findViewById(R.id.resultMessage);
        launchButton = findViewById(R.id.launchButton);
        retryObbButton = findViewById(R.id.retryObbButton);
        uninstallButton = findViewById(R.id.uninstallButton);
        cleanupButton = findViewById(R.id.cleanupButton);
        bottomTitle = findViewById(R.id.bottomTitle);
        bottomSub = findViewById(R.id.bottomSub);
        installButton = findViewById(R.id.installButton);
        topStatus = findViewById(R.id.topStatus);
        headerSub = findViewById(R.id.headerSub);

        findViewById(R.id.backButton).setOnClickListener(view -> finishScreen());
        addObbButton.setOnClickListener(view -> openObbPicker());
        missingPermButton.setOnClickListener(view -> openMissingPermission());
        cancelButton.setOnClickListener(view -> sendCancel());
        installButton.setOnClickListener(view -> startInstall(false));
        launchButton.setOnClickListener(view -> launchGame());
        retryObbButton.setOnClickListener(view -> startInstall(true));
        uninstallButton.setOnClickListener(view -> uninstallExisting());
        cleanupButton.setOnClickListener(view -> cleanupDownloads());
        moveCheck.setOnCheckedChangeListener((button, checked) -> {
            if (optionsInitialized) {
                updateDestination();
                updateBottom();
                setBusyUi();
            }
        });

        stateReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                updateFromState();
            }
        };

        versionChip.setVisibility(View.GONE);
        installedChip.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
        progressCard.setVisibility(View.GONE);
        launchButton.setVisibility(View.GONE);
        retryObbButton.setVisibility(View.GONE);
        uninstallButton.setVisibility(View.GONE);
        cleanupButton.setVisibility(View.GONE);

        headerSub.setText("Reading APK...");
        gameName.setText(new File(apkPath).getName());
        gamePackage.setText("Checking the package and matching OBBs...");
        bottomTitle.setText("Loading");
        bottomSub.setText("Reading the APK...");
        installButton.setEnabled(false);
        setTopStatus("LOADING", R.drawable.bg_chip_blue, getColor(R.color.blue));
        updatePermissionsUi();
        load();
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter(InstallService.ACTION_STATE);
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(stateReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                registerReceiver(stateReceiver, filter);
            }
            receiverRegistered = true;
        }
    }

    @Override
    protected void onStop() {
        if (receiverRegistered) {
            try {
                unregisterReceiver(stateReceiver);
            } catch (Throwable ignored) {
            }
            receiverRegistered = false;
        }
        super.onStop();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updatePermissionsUi();
        if (!loaded || pkg == null) {
            return;
        }
        pkg.installedVersionCode = PackageScanner.getInstalledVersion(this, pkg.apk.packageName);
        updateChips();
        if (uninstallButton.getVisibility() == View.VISIBLE && !pkg.isInstalled()) {
            uninstallButton.setVisibility(View.GONE);
        }

        InstallState state = InstallState.get();
        if (isSameStatePackage(state) && state.phase != InstallState.Phase.IDLE) {
            updateFromState();
        } else if (pendingInstall && Perms.hasFiles(this) && Perms.hasInstall(this)) {
            startInstall(false);
        } else {
            updateBottom();
            setBusyUi();
            if (!state.isActive()) {
                updateTopStatusNormal();
            }
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
        if (requestCode == REQ_OBB && resultCode == RESULT_OK && data != null) {
            ArrayList<String> paths = data.getStringArrayListExtra(
                    FileBrowserActivity.EXTRA_RESULT_PATHS);
            if (paths != null && !paths.isEmpty()) {
                addManualObbs(paths);
            }
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_NOTIFICATIONS && pendingInstall) {
            startInstall(false);
        }
    }

    @Override
    public void onBackPressed() {
        if (busy) {
            Ui.toast(this, "The install continues in the background. Reopen it from the main screen.");
        }
        super.onBackPressed();
    }

    private void finishScreen() {
        if (busy) {
            Ui.toast(this, "The install continues in the background. Reopen it from the main screen.");
        }
        finish();
    }

    private void load() {
        final ArrayList<String> extra = getIntent().getStringArrayListExtra(EXTRA_OBB_PATHS);
        background.execute(() -> {
            InstallPackage built = null;
            Throwable failure = null;
            try {
                built = PackageScanner.buildPackage(
                        InstallActivity.this, prefs, apkPath, extra);
            } catch (Throwable error) {
                failure = error;
            }
            final InstallPackage result = built;
            final Throwable error = failure;
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (result == null) {
                    showLoadError(error);
                } else {
                    bind(result);
                }
            });
        });
    }

    private void bind(InstallPackage loadedPackage) {
        pkg = loadedPackage;
        loaded = true;
        busy = false;
        cancelClicked = false;

        headerSub.setText(pkg.apk.packageName);
        if (pkg.apk.icon != null) {
            gameIcon.setImageDrawable(pkg.apk.icon);
        } else {
            gameIcon.setImageResource(R.drawable.ic_apk_24);
        }
        gameName.setText(pkg.displayName);
        gamePackage.setText(pkg.apk.packageName);
        updateChips();

        apkName.setText(new File(pkg.apk.path).getName());
        apkMeta.setText(Format.bytes(pkg.apk.size)
                + "  |  minSdk " + pkg.apk.minSdk
                + "  |  targetSdk " + pkg.apk.targetSdk);
        apkSize.setText(Format.bytes(pkg.apk.size));

        versionChip.setVisibility(View.VISIBLE);
        installedChip.setVisibility(View.VISIBLE);

        obbs.clear();
        obbs.addAll(pkg.obbs);
        selected.clear();
        manualPaths.clear();
        for (ObbInfo obb : obbs) {
            if (obb.manual) {
                manualPaths.add(obb.path);
            }
            if (obb.manual || obb.versionMatches(pkg.apk)) {
                selected.add(obb.path);
            }
        }
        renderObbs();

        if (!optionsInitialized) {
            optionsInitialized = true;
            moveCheck.setChecked(defaultMove());
            deleteOldCheck.setChecked(false);
        }
        updateDestination();
        updateBottom();
        setBusyUi();
        updatePermissionsUi();

        InstallState state = InstallState.get();
        if (isSameStatePackage(state) && state.phase != InstallState.Phase.IDLE) {
            updateFromState();
        } else if (state.isActive()) {
            updateBottom();
        } else {
            hideProgressAndResult();
            updateTopStatusNormal();
        }
    }

    private void updateChips() {
        if (pkg == null) {
            return;
        }
        Ui.chip(versionChip, "APK " + Format.version(pkg.apk.versionName, pkg.apk.versionCode),
                R.drawable.bg_chip_blue, getColor(R.color.blue));
        if (!pkg.isInstalled()) {
            Ui.chip(installedChip, "NOT INSTALLED",
                    R.drawable.bg_chip_neutral, getColor(R.color.muted));
        } else if (pkg.isSameVersionInstalled()) {
            Ui.chip(installedChip, "INSTALLED " + pkg.installedVersionCode,
                    R.drawable.bg_chip_good, getColor(R.color.accent));
        } else if (pkg.isDowngrade()) {
            Ui.chip(installedChip, "INSTALLED " + pkg.installedVersionCode + "  |  DOWNGRADE",
                    R.drawable.bg_chip_bad, getColor(R.color.danger));
        } else {
            Ui.chip(installedChip, "INSTALLED " + pkg.installedVersionCode,
                    R.drawable.bg_chip_warn, getColor(R.color.warn));
        }
    }

    private void renderObbs() {
        obbList.removeAllViews();
        LayoutInflater inflater = LayoutInflater.from(this);
        for (ObbInfo obb : obbs) {
            View row = inflater.inflate(R.layout.item_obb, obbList, false);
            TextView name = row.findViewById(R.id.obbName);
            TextView meta = row.findViewById(R.id.obbMeta);
            TextView warn = row.findViewById(R.id.obbWarn);
            TextView size = row.findViewById(R.id.obbSize);
            CheckBox check = row.findViewById(R.id.obbCheck);
            ImageButton remove = row.findViewById(R.id.obbRemove);

            name.setText(obb.name);
            StringBuilder metaText = new StringBuilder();
            metaText.append(obb.kind).append("  |  ").append(Format.bytes(obb.size));
            if (obb.versionCode > 0) {
                metaText.append("  |  v").append(obb.versionCode);
            }
            if (obb.manual) {
                metaText.append("  |  added manually");
            }
            meta.setText(metaText.toString());
            size.setText(Format.bytes(obb.size));
            check.setChecked(selected.contains(obb.path));

            if (!obb.versionMatches(pkg.apk)) {
                warn.setVisibility(View.VISIBLE);
                warn.setText(R.string.obb_version_warning);
            } else {
                warn.setVisibility(View.GONE);
            }

            remove.setVisibility(obb.manual ? View.VISIBLE : View.GONE);
            remove.setOnClickListener(view -> removeManualObb(obb));
            row.setOnClickListener(view -> toggleObb(obb));
            row.setAlpha(busy ? 0.55f : 1f);
            obbList.addView(row);
        }
        obbList.setVisibility(obbs.isEmpty() ? View.GONE : View.VISIBLE);
        obbEmpty.setVisibility(obbs.isEmpty() ? View.VISIBLE : View.GONE);
        updateSummary();
    }

    private void toggleObb(ObbInfo obb) {
        if (busy || !loaded) {
            return;
        }
        if (!selected.remove(obb.path)) {
            selected.add(obb.path);
        }
        renderObbs();
        updateDestination();
        updateBottom();
        setBusyUi();
    }

    private void addManualObbs(List<String> paths) {
        if (busy || pkg == null) {
            return;
        }
        int added = 0;
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            File file = new File(path);
            if (!file.isFile()) {
                continue;
            }
            ObbInfo existing = null;
            for (ObbInfo obb : obbs) {
                if (obb.path.equals(path)) {
                    existing = obb;
                    break;
                }
            }
            if (existing != null) {
                existing.manual = true;
                manualPaths.add(existing.path);
                selected.add(existing.path);
                prefs.addManualObb(pkg.apk.packageName, existing.path);
                added++;
                continue;
            }
            ObbInfo obb = ObbInfo.from(file);
            if (obb == null) {
                continue;
            }
            obb.manual = true;
            if (obb.packageName == null || !obb.matches(pkg.apk)) {
                obb.packageName = pkg.apk.packageName;
            }
            obbs.add(obb);
            selected.add(obb.path);
            manualPaths.add(obb.path);
            prefs.addManualObb(pkg.apk.packageName, obb.path);
            added++;
        }
        if (added > 0) {
            setResult(RESULT_OK);
            renderObbs();
            updateDestination();
            updateBottom();
            setBusyUi();
            Ui.toast(this, added + (added == 1 ? " OBB added." : " OBBs added."));
        }
    }

    private void removeManualObb(ObbInfo obb) {
        if (busy || pkg == null) {
            return;
        }
        prefs.removeManualObb(pkg.apk.packageName, obb.path);
        obbs.remove(obb);
        selected.remove(obb.path);
        manualPaths.remove(obb.path);
        setResult(RESULT_OK);
        renderObbs();
        updateDestination();
        updateBottom();
        setBusyUi();
    }

    private void openObbPicker() {
        if (busy) {
            return;
        }
        Intent intent = new Intent(this, FileBrowserActivity.class);
        intent.putExtra(FileBrowserActivity.EXTRA_MODE, FileBrowserActivity.MODE_OBB);
        File parent = new File(apkPath).getParentFile();
        if (parent != null && parent.isDirectory()) {
            intent.putExtra(FileBrowserActivity.EXTRA_START, parent.getAbsolutePath());
        }
        startActivityForResult(intent, REQ_OBB);
    }

    private void refreshCalculated() {
        renderObbs();
        updateDestination();
        updateBottom();
        setBusyUi();
    }

    private void updateSummary() {
        if (pkg == null) {
            return;
        }
        int count = selectedCount();
        long size = selectedSize();
        if (obbs.isEmpty()) {
            obbSummary.setText("None");
        } else {
            obbSummary.setText(count + " / " + obbs.size()
                    + " selected  |  " + Format.bytes(size));
        }
        obbEmpty.setText(R.string.no_obb_selected);
    }

    private int selectedCount() {
        int count = 0;
        for (ObbInfo obb : obbs) {
            if (selected.contains(obb.path)) {
                count++;
            }
        }
        return count;
    }

    private long selectedSize() {
        long total = 0;
        for (ObbInfo obb : obbs) {
            if (selected.contains(obb.path)) {
                total += obb.size;
            }
        }
        return total;
    }

    private boolean defaultMove() {
        File external = Environment.getExternalStorageDirectory();
        for (ObbInfo obb : obbs) {
            if (!selected.contains(obb.path)) {
                continue;
            }
            if (!FileUtils.isSameVolume(this, new File(obb.path), external)) {
                return false;
            }
        }
        return true;
    }

    private void updateDestination() {
        if (pkg == null) {
            return;
        }
        File dir = destDir();
        destPath.setText("Headset/Android/obb/" + pkg.apk.packageName + "/");

        long free = Environment.getExternalStorageDirectory().getUsableSpace();
        long needed = 0;
        boolean move = moveCheck.isChecked() && allSelectedOnHeadset();
        if (!move) {
            for (ObbInfo obb : obbs) {
                if (!selected.contains(obb.path)) {
                    continue;
                }
                // A selected OBB that is already at its exact destination is
                // skipped by the service and needs no free space.
                if (!isSameCanonical(new File(obb.path), new File(dir, obb.name))) {
                    needed += obb.size;
                }
            }
        }
        long extraNeeded = move ? 0 : needed;
        spaceOk = extraNeeded <= 0 || free > extraNeeded + 64L * 1024L * 1024L;

        if (!Perms.hasInstall(this)) {
            destFree.setTextColor(getColor(R.color.warn));
            destFree.setText("Install access is needed to create and write this folder.");
        } else if (!spaceOk) {
            destFree.setTextColor(getColor(R.color.danger));
            destFree.setText("Not enough space. Free: " + Format.bytes(free)
                    + "  |  Needed for copies: " + Format.bytes(extraNeeded)
                    + ". Turn on Move or free some storage.");
        } else if (extraNeeded == 0) {
            destFree.setTextColor(getColor(R.color.muted));
            destFree.setText("Free: " + Format.bytes(free)
                    + "  |  Move enabled - no extra OBB space needed.");
        } else {
            destFree.setTextColor(getColor(R.color.muted));
            destFree.setText("Free: " + Format.bytes(free)
                    + "  |  Needed for OBB copies: " + Format.bytes(extraNeeded));
        }

        oldObbCount = 0;
        oldObbBytes = 0;
        if (Perms.hasInstall(this)) {
            File[] existing = null;
            try {
                existing = dir.listFiles();
            } catch (Throwable ignored) {
            }
            if (existing != null) {
                Set<String> names = new HashSet<>();
                for (ObbInfo obb : obbs) {
                    if (selected.contains(obb.path)) {
                        names.add(obb.name.toLowerCase(Locale.US));
                    }
                }
                for (File file : existing) {
                    if (file.isFile()
                            && file.getName().toLowerCase(Locale.US).endsWith(".obb")
                            && !names.contains(file.getName().toLowerCase(Locale.US))) {
                        oldObbCount++;
                        oldObbBytes += file.length();
                    }
                }
            }
            if (oldObbCount > 0) {
                oldObbInfo.setVisibility(View.VISIBLE);
                oldObbInfo.setText(oldObbCount
                        + (oldObbCount == 1 ? " other OBB is" : " other OBBs are")
                        + " already in this folder (" + Format.bytes(oldObbBytes) + ").");
            } else {
                oldObbInfo.setVisibility(View.GONE);
            }
        } else {
            oldObbInfo.setVisibility(View.GONE);
        }

        deleteOldCheck.setText(oldObbCount > 0
                ? getString(R.string.delete_old) + " (" + oldObbCount + ")"
                : getString(R.string.delete_old));
    }

    private boolean allSelectedOnHeadset() {
        File external = Environment.getExternalStorageDirectory();
        for (ObbInfo obb : obbs) {
            if (!selected.contains(obb.path)) {
                continue;
            }
            if (!FileUtils.isSameVolume(this, new File(obb.path), external)) {
                return false;
            }
        }
        return true;
    }

    private void updatePermissionsUi() {
        boolean files = Perms.hasFiles(this);
        boolean install = Perms.hasInstall(this);
        if (files && install) {
            missingPermBanner.setVisibility(View.GONE);
            return;
        }
        missingPermBanner.setVisibility(View.VISIBLE);
        if (!files && !install) {
            missingPermText.setText("File access and install access are needed before installing.");
        } else if (!files) {
            missingPermText.setText("All-files access is needed to read the APK and OBB files.");
        } else {
            missingPermText.setText("Install access is needed to install the APK and write Android/obb.");
        }
        missingPermButton.setText(R.string.allow);
    }

    private void openMissingPermission() {
        if (!Perms.hasFiles(this)) {
            Perms.openFilesSettings(this);
        } else if (!Perms.hasInstall(this)) {
            Perms.openInstallSettings(this);
        } else {
            Ui.toast(this, "Permissions are already granted.");
        }
    }

    private void updateBottom() {
        if (pkg == null) {
            bottomTitle.setText("Cannot install");
            bottomSub.setText("Try another APK.");
            installButton.setText(R.string.install_game);
            installButton.setEnabled(false);
            return;
        }
        InstallState state = InstallState.get();
        if (busy) {
            bottomTitle.setText("Installing...");
            bottomSub.setText(pkg.displayName);
            installButton.setText(R.string.installing);
            installButton.setEnabled(false);
            return;
        }
        if (state.isActive() && !isSameStatePackage(state)) {
            bottomTitle.setText("Another install is running");
            bottomSub.setText("Wait for it to finish, or cancel it first.");
            installButton.setText(R.string.install_game);
            installButton.setEnabled(false);
            return;
        }
        if (isSameStatePackage(state) && state.phase == InstallState.Phase.DONE) {
            bottomTitle.setText("Installed");
            bottomSub.setText("Launch the game, or install again to repair it.");
            installButton.setText("Install again");
            installButton.setEnabled(spaceOk);
            return;
        }
        if (isSameStatePackage(state)
                && (state.phase == InstallState.Phase.FAILED
                || state.phase == InstallState.Phase.CANCELED)) {
            bottomTitle.setText("Install didn't finish");
            bottomSub.setText("Check the message above, then try again.");
            installButton.setText(R.string.retry);
            installButton.setEnabled(spaceOk);
            return;
        }

        if (pkg.isSameVersionInstalled()) {
            bottomTitle.setText("Already installed");
            bottomSub.setText("Reinstalling replaces the app and keeps its data.");
            installButton.setText(R.string.reinstall_game);
        } else if (pkg.isInstalled()) {
            bottomTitle.setText(pkg.isDowngrade() ? "Downgrade" : "Update ready");
            bottomSub.setText("Installed version " + pkg.installedVersionCode
                    + "  |  APK version " + pkg.apk.versionCode);
            installButton.setText(R.string.reinstall_game);
        } else {
            bottomTitle.setText("Ready to install");
            bottomSub.setText(apkSummary());
            installButton.setText(R.string.install_game);
        }
        installButton.setEnabled(loaded && !busy && spaceOk);
    }

    private String apkSummary() {
        int count = selectedCount();
        long size = selectedSize();
        String text = "APK " + Format.bytes(pkg.apk.size);
        if (count == 0) {
            text += "  |  APK only";
        } else {
            text += "  |  " + count + (count == 1 ? " OBB " : " OBBs ")
                    + Format.bytes(size);
        }
        return text;
    }

    private void setBusyUi() {
        boolean lock = busy;
        addObbButton.setEnabled(!lock && loaded);
        moveCheck.setEnabled(!lock);
        // The destination may be empty now, but the user still needs to be
        // able to choose this option for the OBBs they are about to install.
        deleteOldCheck.setEnabled(!lock && loaded);
        for (int i = 0; i < obbList.getChildCount(); i++) {
            obbList.getChildAt(i).setAlpha(lock ? 0.55f : 1f);
        }
        if (installButton != null) {
            installButton.setEnabled(!lock && loaded && spaceOk);
        }
    }

    private void setTopStatus(String text, int background, int color) {
        Ui.chip(topStatus, text, background, color);
    }

    private void updateTopStatusNormal() {
        if (!loaded || pkg == null) {
            setTopStatus("ERROR", R.drawable.bg_chip_bad, getColor(R.color.danger));
            return;
        }
        if (pkg.isSameVersionInstalled()) {
            setTopStatus("INSTALLED", R.drawable.bg_chip_good, getColor(R.color.accent));
        } else if (pkg.isInstalled()) {
            setTopStatus(pkg.isDowngrade() ? "DOWNGRADE" : "UPDATE",
                    pkg.isDowngrade() ? R.drawable.bg_chip_bad : R.drawable.bg_chip_warn,
                    pkg.isDowngrade() ? getColor(R.color.danger) : getColor(R.color.warn));
        } else {
            setTopStatus("READY", R.drawable.bg_chip_blue, getColor(R.color.blue));
        }
    }

    private void hideProgressAndResult() {
        progressCard.setVisibility(View.GONE);
        resultCard.setVisibility(View.GONE);
        lastPhase = InstallState.Phase.IDLE;
    }

    private void showLoadError(Throwable error) {
        loaded = false;
        busy = false;
        pkg = null;
        headerSub.setText("APK unreadable");
        gameName.setText(new File(apkPath).getName());
        gamePackage.setText("This APK could not be read.");
        versionChip.setVisibility(View.GONE);
        installedChip.setVisibility(View.GONE);
        progressCard.setVisibility(View.GONE);
        resultCard.setVisibility(View.VISIBLE);
        resultIcon.setImageResource(R.drawable.ic_warn_24);
        resultTitle.setText("Can't read this APK");
        String message = (!Perms.hasFiles(this))
                ? "Grant all-files access, then come back and open the APK again."
                : "The file may be corrupted or still downloading. Check that it is a valid Android APK.";
        if (error != null && error.getMessage() != null && !error.getMessage().trim().isEmpty()) {
            message += "\n\n" + error.getMessage();
        }
        resultMessage.setText(message);
        launchButton.setVisibility(View.GONE);
        retryObbButton.setVisibility(View.GONE);
        uninstallButton.setVisibility(View.GONE);
        cleanupButton.setVisibility(View.GONE);
        setTopStatus("ERROR", R.drawable.bg_chip_bad, getColor(R.color.danger));
        bottomTitle.setText("Cannot install");
        bottomSub.setText("Try another APK.");
        installButton.setText(R.string.install_game);
        installButton.setEnabled(false);
        setBusyUi();
        updatePermissionsUi();
    }

    private void startInstall(boolean skipApk) {
        if (busy) {
            return;
        }
        if (!loaded || pkg == null) {
            Ui.toast(this, "The APK is not ready.");
            return;
        }
        InstallState current = InstallState.get();
        if (current.isActive()) {
            if (isSameStatePackage(current)) {
                updateFromState();
            } else {
                Ui.toast(this, "Another install is already running.");
            }
            return;
        }
        if (!Perms.hasFiles(this)) {
            pendingInstall = true;
            Perms.openFilesSettings(this);
            return;
        }
        if (!Perms.hasInstall(this)) {
            pendingInstall = true;
            Perms.openInstallSettings(this);
            return;
        }
        if (!notificationRequested && Build.VERSION.SDK_INT >= 33
                && !Perms.hasNotifications(this)) {
            notificationRequested = true;
            pendingInstall = true;
            Perms.requestNotifications(this, REQ_NOTIFICATIONS);
            return;
        }

        ArrayList<String> paths;
        try {
            paths = validatedSelectedPaths();
        } catch (UserError error) {
            Ui.toast(this, error.getMessage());
            return;
        }
        if (skipApk) {
            if (pkg.installedVersionCode < 0) {
                Ui.toast(this, "Install the APK before retrying the OBB copy.");
                return;
            }
            if (paths.isEmpty()) {
                Ui.toast(this, "There are no OBB files left to copy.");
                return;
            }
        }

        pendingInstall = false;
        activeJobObbs.clear();
        activeJobObbs.addAll(paths);
        cancelClicked = false;

        ArrayList<String> retainNames = new ArrayList<>();
        for (ObbInfo obb : obbs) {
            if (selected.contains(obb.path)) {
                retainNames.add(obb.name);
            }
        }

        Intent service = new Intent(this, InstallService.class);
        service.setAction(InstallService.ACTION_START);
        service.putExtra(InstallService.EXTRA_APK, apkPath);
        service.putExtra(InstallService.EXTRA_APK_VERSION, pkg.apk.versionCode);
        service.putExtra(InstallService.EXTRA_PACKAGE, pkg.apk.packageName);
        service.putExtra(InstallService.EXTRA_LABEL, pkg.displayName);
        service.putStringArrayListExtra(InstallService.EXTRA_OBB_PATHS, paths);
        service.putStringArrayListExtra(InstallService.EXTRA_RETAIN_NAMES, retainNames);
        service.putExtra(InstallService.EXTRA_MOVE, moveCheck.isChecked());
        service.putExtra(InstallService.EXTRA_DELETE_OLD, deleteOldCheck.isChecked());
        service.putExtra(InstallService.EXTRA_SKIP_APK, skipApk);

        try {
            if (Build.VERSION.SDK_INT >= 26) {
                startForegroundService(service);
            } else {
                startService(service);
            }
        } catch (Throwable error) {
            Ui.toast(this, "Could not start the installer: " + error.getMessage());
            return;
        }

        busy = true;
        showStarting(skipApk);
        setBusyUi();
    }

    private ArrayList<String> validatedSelectedPaths() throws UserError {
        ArrayList<String> paths = new ArrayList<>();
        File dir = destDir();
        for (ObbInfo obb : obbs) {
            if (!selected.contains(obb.path)) {
                continue;
            }
            File source = new File(obb.path);
            if (source.isFile()) {
                paths.add(source.getAbsolutePath());
                continue;
            }
            File destination = new File(dir, obb.name);
            if (destination.isFile()) {
                // A previous move already placed this file. It does not need to
                // be copied again when retrying a failed OBB step.
                continue;
            }
            throw new UserError("Missing OBB file: " + obb.name + ". Add it again.");
        }
        return paths;
    }

    private void showStarting(boolean skipApk) {
        busy = true;
        progressCard.setVisibility(View.VISIBLE);
        resultCard.setVisibility(View.GONE);
        progressPhase.setText(skipApk ? "Retrying OBB copy" : "Preparing install");
        progressPercent.setText("");
        progressBar.setIndeterminate(true);
        progressDetail.setText("Opening the package installer...");
        progressSpeed.setText("");
        cancelButton.setVisibility(View.GONE);
        setTopStatus("INSTALLING", R.drawable.bg_chip_blue, getColor(R.color.blue));
        bottomTitle.setText("Installing...");
        bottomSub.setText(pkg == null ? "" : pkg.displayName);
        installButton.setText(R.string.installing);
        installButton.setEnabled(false);
        scrollTo(progressCard);
        lastPhase = InstallState.Phase.PREPARING;
    }

    private void sendCancel() {
        if (!busy || cancelClicked) {
            return;
        }
        cancelClicked = true;
        cancelButton.setEnabled(false);
        cancelButton.setText("Canceling...");
        try {
            Intent intent = new Intent(this, InstallService.class);
            intent.setAction(InstallService.ACTION_CANCEL);
            startService(intent);
        } catch (Throwable error) {
            Ui.toast(this, "Could not cancel: " + error.getMessage());
        }
    }

    private void updateFromState() {
        if (!loaded || pkg == null) {
            return;
        }
        InstallState state = InstallState.get();
        if (!isSameStatePackage(state)) {
            updateBottom();
            setBusyUi();
            return;
        }
        if (!state.jobObbPaths().isEmpty()) {
            activeJobObbs.addAll(state.jobObbPaths());
        }
        switch (state.phase) {
            case PREPARING:
            case INSTALLING_APK:
            case WAITING_USER:
            case COPYING:
                busy = true;
                showProgress(state);
                break;
            case DONE:
                busy = false;
                showSuccess(state);
                break;
            case FAILED:
            case CANCELED:
                busy = false;
                showFailure(state);
                break;
            default:
                busy = false;
                hideProgressAndResult();
                updateTopStatusNormal();
                updateBottom();
                break;
        }
        setBusyUi();
    }

    private void showProgress(InstallState state) {
        boolean first = lastPhase == InstallState.Phase.IDLE
                || lastPhase == InstallState.Phase.DONE
                || lastPhase == InstallState.Phase.FAILED
                || lastPhase == InstallState.Phase.CANCELED;
        lastPhase = state.phase;
        progressCard.setVisibility(View.VISIBLE);
        resultCard.setVisibility(View.GONE);

        String phaseText;
        String detail;
        String speedText = "";
        int percent = Format.percent(state.copiedBytes, state.totalBytes);
        boolean indeterminate = false;

        switch (state.phase) {
            case PREPARING:
                phaseText = "Preparing";
                detail = "Checking the files and free space...";
                indeterminate = true;
                percent = 0;
                break;
            case INSTALLING_APK:
                phaseText = "Installing APK";
                detail = Format.bytes(state.copiedBytes) + " of "
                        + Format.bytes(state.totalBytes)
                        + " written to the system installer.";
                break;
            case WAITING_USER:
                phaseText = "Waiting for confirmation";
                detail = "Tap Install in the Android system window. "
                        + "krxzyinstall continues automatically afterward.";
                indeterminate = true;
                percent = 0;
                speedText = "The system dialog is required for every APK install.";
                break;
            case COPYING:
                phaseText = "Placing OBB files";
                detail = "OBB " + state.fileIndex + " of " + state.fileCount
                        + ": " + state.currentFile + "\n"
                        + Format.bytes(state.copiedBytes) + " of "
                        + Format.bytes(state.totalBytes);
                if (state.speedBps > 0) {
                    speedText = Format.speed(state.speedBps)
                            + "  |  " + Format.bytes(state.copiedBytes)
                            + " / " + Format.bytes(state.totalBytes);
                }
                break;
            default:
                phaseText = "Working";
                detail = state.message == null ? "" : state.message;
                break;
        }

        progressPhase.setText(phaseText);
        if (indeterminate) {
            progressPercent.setText("");
            progressBar.setIndeterminate(true);
        } else {
            progressPercent.setText(percent + "%");
            progressBar.setIndeterminate(false);
            progressBar.setProgress(percent * 10);
        }
        progressDetail.setText(detail);
        progressSpeed.setText(speedText);

        boolean canCancel = state.phase == InstallState.Phase.PREPARING
                || state.phase == InstallState.Phase.INSTALLING_APK
                || state.phase == InstallState.Phase.COPYING;
        cancelButton.setVisibility(canCancel ? View.VISIBLE : View.GONE);
        if (canCancel && !cancelClicked) {
            cancelButton.setEnabled(true);
            cancelButton.setText(R.string.cancel);
        }

        if (state.phase == InstallState.Phase.WAITING_USER) {
            setTopStatus("ACTION NEEDED", R.drawable.bg_chip_warn, getColor(R.color.warn));
        } else {
            setTopStatus("INSTALLING", R.drawable.bg_chip_blue, getColor(R.color.blue));
        }
        bottomTitle.setText("Installing...");
        bottomSub.setText(pkg == null ? "" : pkg.displayName);
        installButton.setText(R.string.installing);
        installButton.setEnabled(false);
        if (first) {
            scrollTo(progressCard);
        }
    }

    private void showSuccess(InstallState state) {
        progressCard.setVisibility(View.GONE);
        resultCard.setVisibility(View.VISIBLE);
        resultIcon.setImageResource(R.drawable.ic_check_24);
        resultTitle.setText("Installed successfully");
        int obbCount = Math.max(selectedCount(), state.jobObbPaths().size());
        String message = (pkg == null ? "The app" : pkg.displayName) + " is ready.";
        if (obbCount > 0) {
            message += "\n" + obbCount + (obbCount == 1 ? " OBB file" : " OBB files")
                    + " placed in Android/obb/"
                    + (pkg == null ? "" : pkg.apk.packageName) + "/";
        } else {
            message += "\nNo OBB files were needed for this game.";
        }
        resultMessage.setText(message);
        setTopStatus("DONE", R.drawable.bg_chip_good, getColor(R.color.accent));
        if (pkg != null && state.installedVersion >= 0) {
            pkg.installedVersionCode = state.installedVersion;
            updateChips();
        }
        pruneMissingObbs();
        // The move/delete step changed the OBB folder; refresh the free-space
        // and "other OBBs" information shown on this screen.
        updateDestination();
        launchButton.setVisibility(hasLaunchIntent() ? View.VISIBLE : View.GONE);
        retryObbButton.setVisibility(View.GONE);
        uninstallButton.setVisibility(View.GONE);
        updateCleanupButton();
        updateBottom();
        if (lastPhase != InstallState.Phase.DONE) {
            scrollTo(resultCard);
        }
        lastPhase = InstallState.Phase.DONE;
    }

    /**
     * "Remove other OBBs" can delete rows that were already on screen. Drop
     * any entry that no longer exists as a source or at the destination, so
     * the detail screen never keeps a ghost file the user cannot install.
     */
    private void pruneMissingObbs() {
        if (pkg == null) {
            return;
        }
        boolean changed = false;
        File dir = destDir();
        for (int i = obbs.size() - 1; i >= 0; i--) {
            ObbInfo obb = obbs.get(i);
            if (!new File(obb.path).isFile() && !new File(dir, obb.name).isFile()) {
                obbs.remove(i);
                selected.remove(obb.path);
                manualPaths.remove(obb.path);
                if (obb.manual) {
                    prefs.removeManualObb(pkg.apk.packageName, obb.path);
                }
                changed = true;
            }
        }
        if (changed) {
            renderObbs();
            updateDestination();
            updateBottom();
            setBusyUi();
        }
    }

    private void showFailure(InstallState state) {        progressCard.setVisibility(View.GONE);
        resultCard.setVisibility(View.VISIBLE);
        resultIcon.setImageResource(R.drawable.ic_warn_24);
        boolean canceled = state.phase == InstallState.Phase.CANCELED;
        resultTitle.setText(canceled ? "Install canceled" : "Install failed");
        String message = state.error;
        if (message == null || message.trim().isEmpty()) {
            message = state.message;
        }
        if (message == null || message.trim().isEmpty()) {
            message = "The install did not finish. Try again.";
        }
        String errorText = state.error == null ? "" : state.error.toLowerCase(Locale.US);
        boolean apkReady = state.apkInstalled;
        if (!apkReady && pkg != null) {
            // The APK can be fully installed even when the headset reported a
            // false failure. Refresh the real state before hiding the retry.
            pkg.installedVersionCode = PackageScanner.getInstalledVersion(
                    this, pkg.apk.packageName);
            updateChips();
            if (pkg.isSameVersionInstalled() && !isUninstallWorthy(errorText)) {
                apkReady = true;
            }
        }
        if (apkReady && selectedCount() > 0) {
            retryObbButton.setVisibility(View.VISIBLE);
            message += "\n\nThe APK is installed. You can retry just the OBB copy.";
        } else {
            retryObbButton.setVisibility(View.GONE);
        }
        if (pkg != null && pkg.isInstalled() && isUninstallWorthy(errorText)) {
            uninstallButton.setVisibility(View.VISIBLE);
        } else {
            uninstallButton.setVisibility(View.GONE);
        }
        resultMessage.setText(message);
        setTopStatus(canceled ? "CANCELED" : "FAILED",
                canceled ? R.drawable.bg_chip_neutral : R.drawable.bg_chip_bad,
                canceled ? getColor(R.color.muted) : getColor(R.color.danger));
        launchButton.setVisibility(View.GONE);
        cleanupButton.setVisibility(View.GONE);
        updateBottom();
        if (lastPhase != InstallState.Phase.FAILED
                && lastPhase != InstallState.Phase.CANCELED) {
            scrollTo(resultCard);
        }
        lastPhase = state.phase;
    }

    private void launchGame() {
        if (pkg == null) {
            return;
        }
        try {
            Intent launch = resolveLaunchIntent();
            if (launch == null) {
                Ui.toast(this, "This app has no launch button. Find it in your library under Unknown Sources.");
                return;
            }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(launch);
        } catch (Throwable error) {
            Ui.toast(this, "Could not launch the game: " + error.getMessage());
        }
    }

    private void uninstallExisting() {
        if (pkg == null) {
            return;
        }
        try {
            Intent uninstall = new Intent(Intent.ACTION_DELETE,
                    Uri.parse("package:" + pkg.apk.packageName));
            startActivity(uninstall);
        } catch (Throwable error) {
            Ui.toast(this, "Could not open the uninstall screen: " + error.getMessage());
        }
    }

    private boolean hasLaunchIntent() {
        return resolveLaunchIntent() != null;
    }

    /**
     * Quest games normally expose a launcher entry, but VR-only apps can
     * declare just com.oculus.intent.category.VR. Try both before giving up.
     */
    private Intent resolveLaunchIntent() {
        if (pkg == null) {
            return null;
        }
        try {
            PackageManager pm = getPackageManager();
            Intent launch = pm.getLaunchIntentForPackage(pkg.apk.packageName);
            if (launch != null) {
                return launch;
            }
            Intent vr = new Intent(Intent.ACTION_MAIN);
            vr.addCategory("com.oculus.intent.category.VR");
            vr.setPackage(pkg.apk.packageName);
            List<ResolveInfo> matches = pm.queryIntentActivities(vr, 0);
            if (matches != null && !matches.isEmpty()
                    && matches.get(0).activityInfo != null) {
                Intent explicit = new Intent(vr);
                explicit.setClassName(matches.get(0).activityInfo.packageName,
                        matches.get(0).activityInfo.name);
                return explicit;
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private boolean isUninstallWorthy(String error) {
        if (error == null) {
            return false;
        }
        return error.contains("signature")
                || error.contains("conflict")
                || error.contains("downgrade")
                || error.contains("uninstall")
                || error.contains("incompatible");
    }

    private List<File> cleanupSources() {
        LinkedHashSet<String> paths = new LinkedHashSet<>();
        if (apkPath != null) {
            paths.add(apkPath);
        }
        InstallState state = InstallState.get();
        if (isSameStatePackage(state)) {
            paths.addAll(state.jobObbPaths());
        }
        paths.addAll(activeJobObbs);
        List<File> files = new ArrayList<>();
        for (String path : paths) {
            if (path == null) {
                continue;
            }
            File file = new File(path);
            if (file.isFile() && !isInDestination(file)) {
                files.add(file);
            }
        }
        return files;
    }

    /**
     * Clean up must only remove downloaded copies. OBBs already placed in
     * Android/obb are the installed game's files and stay put.
     */
    private boolean isInDestination(File file) {
        if (pkg == null) {
            return false;
        }
        try {
            File parent = file.getParentFile();
            return parent != null
                    && parent.getCanonicalPath().equals(destDir().getCanonicalPath());
        } catch (Throwable ignored) {
            return false;
        }
    }

    private boolean isSameCanonical(File a, File b) {
        try {
            return a.getCanonicalPath().equalsIgnoreCase(b.getCanonicalPath());
        } catch (Throwable ignored) {
            return a.getAbsolutePath().equalsIgnoreCase(b.getAbsolutePath());
        }
    }

    private void updateCleanupButton() {
        List<File> files = cleanupSources();
        if (files.isEmpty()) {
            cleanupButton.setVisibility(View.GONE);
            return;
        }
        long total = 0;
        for (File file : files) {
            total += file.length();
        }
        cleanupButton.setText(getString(R.string.clean_up) + " (" + Format.bytes(total) + ")");
        cleanupButton.setVisibility(View.VISIBLE);
    }

    private void cleanupDownloads() {
        if (busy) {
            return;
        }
        final List<File> files = cleanupSources();
        if (files.isEmpty()) {
            cleanupButton.setVisibility(View.GONE);
            Ui.toast(this, "There are no downloaded source files left to remove.");
            return;
        }
        long total = 0;
        for (File file : files) {
            total += file.length();
        }
        final long totalBytes = total;
        Ui.confirm(this, getString(R.string.cleanup_title),
                getString(R.string.cleanup_body) + "\n\nThis frees " + Format.bytes(totalBytes) + ".",
                getString(R.string.delete), () -> {
                    long freed = 0;
                    int removed = 0;
                    for (File file : files) {
                        long length = file.length();
                        if (file.delete()) {
                            freed += length;
                            removed++;
                        }
                    }
                    setResult(RESULT_OK);
                    updateCleanupButton();
                    if (removed > 0) {
                        String old = resultMessage.getText().toString();
                        resultMessage.setText(old + "\n\nRemoved " + removed
                                + (removed == 1 ? " downloaded file (" : " downloaded files (")
                                + Format.bytes(freed) + ").");
                        Ui.toast(this, "Removed " + removed + " file(s), freed "
                                + Format.bytes(freed) + ".");
                    } else {
                        Ui.toast(this, "The files could not be deleted.");
                    }
                });
    }

    private boolean isSameStatePackage(InstallState state) {
        if (pkg == null
                || state == null
                || state.packageName == null
                || !state.packageName.equals(pkg.apk.packageName)) {
            return false;
        }
        // A result for one APK file must not appear above a different APK of
        // the same package (for example after a new version is downloaded).
        if (state.apkPath != null && !state.apkPath.isEmpty() && apkPath != null) {
            try {
                return new File(state.apkPath).getCanonicalPath()
                        .equals(new File(apkPath).getCanonicalPath());
            } catch (Throwable ignored) {
                return state.apkPath.equals(apkPath);
            }
        }
        return true;
    }

    private File destDir() {
        return new File(Environment.getExternalStorageDirectory(),
                "Android/obb/" + (pkg == null ? "" : pkg.apk.packageName));
    }

    private void scrollTo(View view) {
        installScroll.post(() -> installScroll.smoothScrollTo(0, view.getTop()));
    }
}
