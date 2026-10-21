package com.krxzy.krxzyinstall;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class FileBrowserActivity extends Activity {
    public static final String EXTRA_MODE = "mode";
    public static final String EXTRA_START = "start";
    public static final String EXTRA_RESULT_PATHS = "paths";
    public static final String MODE_APK = "apk";
    public static final String MODE_OBB = "obb";

    private final ExecutorService background = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Entry> entries = new ArrayList<>();
    private final LinkedHashSet<String> selected = new LinkedHashSet<>();

    private String mode = MODE_APK;
    private File currentDir;
    private FileAdapter adapter;
    private ListView listView;
    private TextView titleView;
    private TextView pathView;
    private TextView selectedInfo;
    private TextView emptyView;
    private TextView permissionText;
    private View permissionBanner;
    private View loading;
    private Button addButton;
    private ImageButton upButton;
    private LinearLayout chipRow;

    private static class Entry {
        File file;
        boolean directory;
        long size;
    }

    private class FileAdapter extends BaseAdapter {
        private final LayoutInflater inflater = LayoutInflater.from(FileBrowserActivity.this);

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = convertView;
            if (view == null) {
                view = inflater.inflate(R.layout.item_file, parent, false);
            }
            Entry entry = entries.get(position);
            ImageView icon = view.findViewById(R.id.fileIcon);
            TextView name = view.findViewById(R.id.fileName);
            TextView meta = view.findViewById(R.id.fileMeta);
            CheckBox check = view.findViewById(R.id.fileCheck);

            name.setText(entry.file.getName());
            if (entry.directory) {
                icon.setImageResource(R.drawable.ic_folder_24);
                meta.setText(R.string.folder);
                check.setVisibility(View.GONE);
            } else if (entry.file.getName().toLowerCase(Locale.US).endsWith(".apk")) {
                icon.setImageResource(R.drawable.ic_apk_24);
                meta.setText("APK  |  " + Format.bytes(entry.size));
                check.setVisibility(View.GONE);
            } else {
                icon.setImageResource(R.drawable.ic_obb_24);
                meta.setText("OBB  |  " + Format.bytes(entry.size));
                if (MODE_OBB.equals(mode)) {
                    check.setVisibility(View.VISIBLE);
                    check.setChecked(selected.contains(entry.file.getAbsolutePath()));
                } else {
                    check.setVisibility(View.GONE);
                }
            }
            return view;
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_file_browser);

        String requested = getIntent().getStringExtra(EXTRA_MODE);
        if (MODE_OBB.equals(requested)) {
            mode = MODE_OBB;
        }

        titleView = findViewById(R.id.browserTitle);
        pathView = findViewById(R.id.pathText);
        selectedInfo = findViewById(R.id.selectedInfo);
        emptyView = findViewById(R.id.emptyView);
        permissionText = findViewById(R.id.permissionText);
        permissionBanner = findViewById(R.id.permissionBanner);
        loading = findViewById(R.id.loading);
        addButton = findViewById(R.id.addButton);
        upButton = findViewById(R.id.upButton);
        chipRow = findViewById(R.id.chipRow);
        listView = findViewById(R.id.fileList);

        titleView.setText(MODE_OBB.equals(mode) ? R.string.select_obb : R.string.select_apk);

        adapter = new FileAdapter();
        listView.setAdapter(adapter);
        listView.setEmptyView(emptyView);

        findViewById(R.id.backButton).setOnClickListener(view -> finish());
        upButton.setOnClickListener(view -> navigateUp());
        addButton.setOnClickListener(view -> finishWithSelection());
        findViewById(R.id.grantButton).setOnClickListener(
                view -> Perms.openFilesSettings(this));

        listView.setOnItemClickListener((parent, view, position, id) -> {
            Entry entry = entries.get(position);
            if (entry.directory) {
                load(entry.file);
                return;
            }
            if (MODE_APK.equals(mode)) {
                finishWithPath(entry.file);
                return;
            }
            String path = entry.file.getAbsolutePath();
            if (!selected.remove(path)) {
                selected.add(path);
            }
            adapter.notifyDataSetChanged();
            updateSelection();
        });

        if (MODE_OBB.equals(mode)) {
            addButton.setVisibility(View.VISIBLE);
        } else {
            addButton.setVisibility(View.GONE);
            selectedInfo.setText("Tap an APK to continue.");
        }

        buildChips();
        File start = resolveStartDir();
        load(start);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean has = Perms.hasFiles(this);
        permissionBanner.setVisibility(has ? View.GONE : View.VISIBLE);
        if (!has) {
            entries.clear();
            adapter.notifyDataSetChanged();
            loading.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(R.string.file_access_needed);
        } else if (currentDir == null) {
            // The first load was blocked by the permission check. Open the
            // start folder as soon as access is granted.
            load(resolveStartDir());
        } else if (entries.isEmpty()) {
            load(currentDir);
        }
        updateSelection();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            load(resolveStartDir());
        }
    }

    @Override
    public void onBackPressed() {
        if (!navigateUp()) {
            super.onBackPressed();
        }
    }

    private File resolveStartDir() {
        String requested = getIntent().getStringExtra(EXTRA_START);
        if (requested != null) {
            File start = new File(requested);
            if (start.isDirectory() && start.canRead()) {
                return start;
            }
        }
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (downloads != null && downloads.isDirectory() && downloads.canRead()) {
            return downloads;
        }
        File external = Environment.getExternalStorageDirectory();
        if (external != null && external.isDirectory() && external.canRead()) {
            return external;
        }
        return new File("/");
    }

    private void buildChips() {
        chipRow.removeAllViews();
        File external = Environment.getExternalStorageDirectory();
        File downloads = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS);
        if (downloads != null) {
            addChip(getString(R.string.downloads), downloads);
        }
        if (external != null) {
            addChip(getString(R.string.headset), external);
        }

        if (Build.VERSION.SDK_INT >= 30) {
            try {
                StorageManager manager = getSystemService(StorageManager.class);
                Set<String> known = new LinkedHashSet<>();
                if (external != null) {
                    known.add(canonical(external));
                }
                if (downloads != null) {
                    known.add(canonical(downloads));
                }
                for (StorageVolume volume : manager.getStorageVolumes()) {
                    File dir = volume.getDirectory();
                    if (dir == null || !dir.isDirectory() || !dir.canRead()) {
                        continue;
                    }
                    String key = canonical(dir);
                    if (!known.add(key)) {
                        continue;
                    }
                    String label;
                    try {
                        label = volume.getDescription(this);
                    } catch (Throwable ignored) {
                        label = "USB storage";
                    }
                    if (volume.isRemovable()) {
                        label = "USB: " + label;
                    }
                    addChip(label, dir);
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private void addChip(String label, File dir) {
        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(13);
        chip.setTextColor(getColor(R.color.text));
        chip.setGravity(android.view.Gravity.CENTER);
        chip.setPadding(Ui.dp(this, 14), Ui.dp(this, 7),
                Ui.dp(this, 14), Ui.dp(this, 7));
        chip.setBackgroundResource(R.drawable.bg_chip_neutral);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginEnd(Ui.dp(this, 8));
        chip.setOnClickListener(view -> load(dir));
        chipRow.addView(chip, params);
    }

    private void load(File dir) {
        if (dir == null || !dir.isDirectory()) {
            Ui.toast(this, "This folder cannot be opened.");
            return;
        }
        if (!Perms.hasFiles(this)) {
            permissionBanner.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.VISIBLE);
            emptyView.setText(R.string.file_access_needed);
            return;
        }
        currentDir = dir;
        permissionBanner.setVisibility(View.GONE);
        pathView.setText(prettyPath(dir));
        upButton.setAlpha(dir.getParentFile() == null ? 0.4f : 1f);
        loading.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        listView.setVisibility(View.INVISIBLE);
        adapter.notifyDataSetChanged();

        background.execute(() -> {
            List<Entry> found = list(dir);
            main.post(() -> {
                if (isFinishing() || isDestroyed()) {
                    return;
                }
                if (currentDir == null || !currentDir.equals(dir)) {
                    return;
                }
                entries.clear();
                entries.addAll(found);
                adapter.notifyDataSetChanged();
                loading.setVisibility(View.GONE);
                listView.setVisibility(View.VISIBLE);
                emptyView.setText(MODE_OBB.equals(mode)
                        ? "No OBB files in this folder.\nTry Downloads or another folder."
                        : "No APK files in this folder.\nTry Downloads or another folder.");
                if (entries.isEmpty()) {
                    emptyView.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    private List<Entry> list(File dir) {
        List<Entry> found = new ArrayList<>();
        File[] children;
        try {
            children = dir.listFiles();
        } catch (Throwable ignored) {
            children = null;
        }
        if (children == null) {
            return found;
        }
        for (File file : children) {
            String name = file.getName();
            if (name.startsWith(".")) {
                continue;
            }
            if (file.isDirectory()) {
                Entry entry = new Entry();
                entry.file = file;
                entry.directory = true;
                found.add(entry);
            } else if (file.isFile()) {
                String lower = name.toLowerCase(Locale.US);
                boolean wanted = MODE_APK.equals(mode)
                        ? lower.endsWith(".apk")
                        : lower.endsWith(".obb");
                if (wanted) {
                    Entry entry = new Entry();
                    entry.file = file;
                    entry.size = file.length();
                    found.add(entry);
                }
            }
        }
        Collections.sort(found, new Comparator<Entry>() {
            @Override
            public int compare(Entry a, Entry b) {
                if (a.directory != b.directory) {
                    return a.directory ? -1 : 1;
                }
                return a.file.getName().compareToIgnoreCase(b.file.getName());
            }
        });
        return found;
    }

    private boolean navigateUp() {
        if (currentDir == null) {
            return false;
        }
        File parent = currentDir.getParentFile();
        if (parent == null || "/".equals(currentDir.getAbsolutePath())) {
            return false;
        }
        load(parent);
        return true;
    }

    private void finishWithPath(File file) {
        ArrayList<String> paths = new ArrayList<>();
        paths.add(file.getAbsolutePath());
        returnPaths(paths);
    }

    private void finishWithSelection() {
        ArrayList<String> paths = new ArrayList<>();
        for (String path : selected) {
            if (new File(path).isFile()) {
                paths.add(path);
            }
        }
        if (paths.isEmpty()) {
            Ui.toast(this, "Select at least one OBB file.");
            return;
        }
        returnPaths(paths);
    }

    private void returnPaths(ArrayList<String> paths) {
        Intent data = new Intent();
        data.putStringArrayListExtra(EXTRA_RESULT_PATHS, paths);
        setResult(RESULT_OK, data);
        finish();
    }

    private void updateSelection() {
        if (!MODE_OBB.equals(mode)) {
            return;
        }
        long total = 0;
        for (String path : selected) {
            File file = new File(path);
            if (file.isFile()) {
                total += file.length();
            }
        }
        if (selected.isEmpty()) {
            selectedInfo.setText("Select OBB files to add.");
        } else {
            selectedInfo.setText(selected.size() + " selected  |  " + Format.bytes(total));
        }
        addButton.setEnabled(!selected.isEmpty());
        addButton.setText(selected.isEmpty()
                ? getString(R.string.add_selected)
                : getString(R.string.add_selected) + " (" + selected.size() + ")");
    }

    private String prettyPath(File dir) {
        String path = dir.getAbsolutePath();
        File external = Environment.getExternalStorageDirectory();
        if (external != null) {
            String root = external.getAbsolutePath();
            if (path.equals(root)) {
                return "Headset";
            }
            if (path.startsWith(root + "/")) {
                return "Headset/" + path.substring(root.length() + 1);
            }
        }
        return path;
    }

    private String canonical(File file) {
        try {
            return file.getCanonicalPath();
        } catch (IOException ignored) {
            return file.getAbsolutePath();
        }
    }

    @Override
    protected void onDestroy() {
        background.shutdownNow();
        super.onDestroy();
    }
}
