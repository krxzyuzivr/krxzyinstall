package com.krxzy.krxzyinstall;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class Prefs {
    private static final String NAME = "krxzyinstall";
    private static final String KEY_APKS = "manual_apks";
    private static final String OBB_PREFIX = "manual_obb_";

    private final SharedPreferences prefs;

    public Prefs(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public Set<String> manualApks() {
        return new LinkedHashSet<>(prefs.getStringSet(KEY_APKS, Collections.emptySet()));
    }

    public void addManualApk(String path) {
        Set<String> set = manualApks();
        set.add(path);
        prefs.edit().putStringSet(KEY_APKS, set).apply();
    }

    public void removeManualApk(String path) {
        Set<String> set = manualApks();
        set.remove(path);
        prefs.edit().putStringSet(KEY_APKS, set).apply();
    }

    public Set<String> manualObbs(String packageName) {
        return new LinkedHashSet<>(
                prefs.getStringSet(OBB_PREFIX + packageName, Collections.emptySet()));
    }

    public void addManualObb(String packageName, String path) {
        Set<String> set = manualObbs(packageName);
        set.add(path);
        prefs.edit().putStringSet(OBB_PREFIX + packageName, set).apply();
    }

    public void removeManualObb(String packageName, String path) {
        Set<String> set = manualObbs(packageName);
        set.remove(path);
        prefs.edit().putStringSet(OBB_PREFIX + packageName, set).apply();
    }
}
