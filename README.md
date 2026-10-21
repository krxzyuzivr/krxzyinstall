# krxzyinstall

**Install one APK + any number of OBB files on a Meta Quest straight from the
headset.** No PC, no ADB, no root, no developer mode, no Wi-Fi debugging.

[Download the latest APK](https://github.com/krxzyuzivr/krxzyinstall/releases/latest) |
[Report a bug](https://github.com/krxzyuzivr/krxzyinstall/issues/new/choose) |
[Build from source](#build-from-source) |
[Credits](CREDITS.md) |
[License](LICENSE)

- Works on Horizon OS 2.7+ (Android 14). Minimum Android 10 (API 29).
- Normal 2D landscape panel - works on Quest 2, Quest 3, Quest 3S and Pro.
- Uses Android's own `PackageInstaller`. Android shows **one** system
  confirmation per APK; that dialog cannot be skipped by a normal app.
- Everything stays on the headset. Nothing is uploaded.
- Made by **krxzyuzivr** and **shrimpo**.

## Official builds

Only APKs signed with this certificate are official krxzyinstall builds:

```
Certificate SHA-256:
d3c9d7b5f7012a996d450f6066e3053d8a7f950b10bf71803a76921041ed0ae4
```

The 1.0.1 release APK:

```
SHA-256:
2ee48bf842be0f3f99f4d761b1d0e0a89feb02d49c98de688207d2f913a553ca
```

If either hash does not match, the build came from someone else. Forks are
welcome under the GPLv3, but they must not pretend to be official releases. See
[TRADEMARKS.md](TRADEMARKS.md).

## What's new in 1.0.1

- **Quest installs now finish even when Horizon OS hides the installer
  result.** krxzyinstall watches `PackageManager` while the system installer
  runs. If the APK lands but the result callback never arrives (or arrives as a
  false failure), it detects the installed version and continues with the OBB
  copy automatically.
- **"Retry OBB copy" recovery.** If an APK is already installed but the OBB
  step failed, the failure card can copy the OBBs again without reinstalling
  the APK.
- **"Remove other OBBs" is always selectable**, including when the destination
  folder is empty. It only runs when at least one OBB is selected, so it can
  never wipe a game's OBB folder by accident.
- Fixed a file-browser reload bug after granting file access.
- Added the **Credits** screen (krxzyuzivr and shrimpo).
- Quest games that only declare the VR intent category now get a working
  **Launch game** button.
- Case-insensitive OBB name handling, stale `.krxpart` cleanup, and a
  low-storage replacement path for large OBB updates.

## Project layout

| Path | What it is |
| --- | --- |
| `app/src/main/java/com/krxzy/krxzyinstall/` | Plain-Java Android source: scanner, installer service, activities, permissions, UI. |
| `app/src/main/res/` | Layouts, drawables, colours, and strings. |
| `scripts/check-credits.ps1` | Verifies the credits in `CREDITS.md` and the in-app Credits screen. |
| `.github/workflows/verify.yml` | CI: credit check and debug APK build. |
| `keystore.properties.example` | Example signing configuration. The real key and properties are **not** in this repository. |
| `dist/` | Local-only build output. Release APKs are published through GitHub Releases. |

There are no AndroidX, Google Play Services, or third-party runtime
dependencies. The app targets API 34 with minSdk 29.

## 1. Put krxzyinstall on the headset (once)

No PC needed. Any of these work:

- **Quest browser:** open the
  [latest release](https://github.com/krxzyuzivr/krxzyinstall/releases/latest),
  download `krxzyinstall-1.0.1-release.apk`, then open the download
  notification.
- **Files app:** copy the APK to the headset from a USB drive or an Android
  phone, then open it in **Files -> Downloads**.
- **Cloud app** (Drive/Dropbox/Discord): download the APK on the headset and
  open it.

Then:

1. Tap the APK. Android asks which app may install unknown apps - allow it for
   **Files** or the **Browser**.
2. Tap **Install** in the system confirmation.
3. Open **krxzyinstall** from **Library -> Unknown Sources** (the source filter
   is in the Library drop-down on newer Horizon OS versions).

This one-time bootstrap is the only part Android forces through another app.
After this, krxzyinstall installs everything else by itself.

## 2. Prepare the game files

Put the APK and its OBB files anywhere the headset can read: `Downloads`,
subfolders, or a USB drive. krxzyinstall scans Downloads, internal storage and
removable volumes automatically.

The example BONELAB package looks like this:

```
BONELAB v2974+1.2974.57485 -VRP/
  com.StressLevelZero.BONELAB.apk                      74.8 MB
  com.StressLevelZero.BONELAB/
    main.2974.com.StressLevelZero.BONELAB.obb           3.38 GB
    patch.2974.com.StressLevelZero.BONELAB.obb          3.38 GB
  release.manifest                                    (optional)
```

- The APK can be named anything; its internal package name is what matters.
- OBBs are matched by their standard names
  (`main.<version>.<package>.obb`, `patch.<version>.<package>.obb`).
- OBBs may sit next to the APK, in a `com.<package>` folder, or elsewhere in
  Downloads/USB. Anything unmatched can be added with **Add OBBs**.
- `release.manifest` is optional and only improves the displayed name.

## 3. Install the game

1. Open **krxzyinstall**. Grant the two permissions:
   - **File access** -> "Allow access to manage all files".
   - **Install access** -> allow "Install unknown apps" for krxzyinstall.
2. The app scans and shows game cards. Tap the card.
3. Check the APK and OBBs:
   - Green checks = selected.
   - "OBB version does not match the APK" means the file belongs to a
     different game version. Use matching OBBs or let a game update replace
     them.
   - The destination is always
     `/storage/emulated/0/Android/obb/<package.name>/`.
4. Tap **Install game**, then **Install** in the Android dialog.
5. Keep krxzyinstall open until it finishes. A foreground notification keeps
   the job alive while the headset is in use.
6. Tap **Launch game** on the result card, or launch the game from
   **Unknown Sources**.

On some Horizon OS builds the system installer never sends a result back to
the app, even though the APK installs correctly. Version 1.0.1 detects that
case automatically and copies the OBBs anyway. The progress card may jump
from "Waiting for confirmation" straight to "Placing OBB files".

### Options

- **Move files into place** (recommended, on by default): renames OBBs into
  `Android/obb` when they are on the same storage. No extra space needed.
  If a rename is blocked, krxzyinstall copies to a `.krxpart` temp file,
  verifies the length, then deletes the source.
- **Remove other OBBs already in the game folder** (off by default): deletes
  old-version OBBs only after all new files are in place. Useful when a game
  update leaves several GB of stale OBBs. It is selectable even when the folder
  is empty, and it only deletes anything when at least one OBB is selected.
- **Clean up downloads** (after success): removes the source APK and any OBBs
  that were *copied* (not moved). It never touches `Android/obb` and never
  deletes the installed game.

Only one APK is installed per package. If two versions of the same game are
found, they appear as separate cards (`v1.0 (1)`, `v2.0 (2)`), each with its
own version-matched OBBs.

## Troubleshooting

**The APK installed but the app said it failed / OBBs were not copied**
Install version 1.0.1 or newer. It detects the installed package even when the
headset swallows the installer result. If the APK is already installed, the
failure card shows **Retry OBB copy**, which finishes the job without touching
the APK.

**Install access stays "Needed"**
Settings -> Apps -> krxzyinstall -> Install unknown apps. If that page is
missing, use Settings -> Special app access -> Install unknown apps.

**File access stays "Needed"**
Settings -> Special app access -> All files access -> krxzyinstall. A Horizon OS
update can reset this toggle; the card will turn green again when it is on.

**"Install failed - conflicts with the installed app (different signature)"**
The APK was signed with a different key than the installed copy. Tap
**Uninstall existing app** (this erases the game's data), then install again.
Use the matching-signed APK whenever possible.

**"OBB version does not match the APK"**
The OBB version number differs from the APK's. Download the matching OBBs, or
enable **Remove other OBBs** so the stale files are cleaned up after the new
ones are placed.

**"Not enough free space"**
Free storage, or keep **Move** enabled and keep the OBBs on the same internal
storage as `Android/obb`. A copy needs roughly the OBB size again; a move needs
almost nothing. When replacing an existing OBB with the same name and there is
only room for one copy, krxzyinstall removes the old file first; the source is
kept until the copy succeeds.

**The Android confirm dialog did not appear**
Allow notifications for krxzyinstall. The foreground notification has a
**Confirm the install** action. If the launch was blocked while the app was in
the background, tap the notification to bring the dialog up.

**Everything worked but the game does not load its data**
Check that the OBBs ended up in
`Android/obb/<package.name>/` with exactly the names the game expects. Use
**Add OBBs** in the detail screen to select them manually, and keep
**Move** on.

## What it does not do

- It cannot skip Android's per-APK install confirmation. That would require
  device-owner, system, or root privileges.
- It cannot install itself silently. The first copy is installed through
  Quest Files/browser, once.
- It cannot install a differently-signed APK over an existing app.
- If the krxzyinstall process is force-killed mid-job, the OBB copy stops.
  The foreground service is there to prevent that; keep the headset awake and
  the app open for very large packages.

## Build from source

### Requirements

- JDK 17 or newer (JDK 21 is used for releases).
- Android SDK platform 34 and build-tools 34.0.0.
- The Gradle wrapper is included, so you do not need Gradle installed.

### Debug APK

```bash
git clone https://github.com/krxzyuzivr/krxzyinstall.git
cd krxzyinstall
./gradlew assembleDebug
```

On Windows:

```bat
gradlew.bat assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/`.

### Signed release

The official signing key is **not** in this repository. To build your own
signed release, create a key and a local `keystore.properties`:

```bash
cp keystore.properties.example keystore.properties
# edit keystore.properties with your own key path and passwords
./gradlew assembleRelease
```

`keystore.properties`, `keystore/`, `*.jks`, and `local.properties` are all
git-ignored. Never commit them. The release APK is written to
`app/build/outputs/apk/release/`. If `keystore.properties` is absent, the
release build is unsigned: use a debug build, or sign the APK with your own
key.

Verified attributes: package `com.krxzy.krxzyinstall`, versionCode `2`,
versionName `1.0.1`, minSdk `29`, targetSdk/compileSdk `34`, APK Signature
Scheme v2 valid.

## Tested

**Confirmed end-to-end on Meta Quest 3S, Horizon OS 2.7 (Android 14).**
The APK installed, the missing installer result was detected, and the OBB files
were placed in `Android/obb` correctly.

Android 14 (API 34) emulator, 2560x1800 landscape:

- Automatic scan/grouping, release.manifest names, OBB matching by package and
  version, separate cards for multiple versions, USB/removable volume paths.
- Permission setup states (Needed <-> Granted), deep links to all-files and
  unknown-apps settings.
- `PackageInstaller` session, mutable explicit result `PendingIntent`, system
  confirm window, foreground progress notification, done notification.
- APK install, OBB move to `Android/obb`, reinstall/update with OBBs preserved,
  launch intent, cleanup of source files only.
- **Missing installer result:** installed the APK out-of-band while the system
  dialog was still open; krxzyinstall detected version `1` via polling and
  copied both OBBs automatically.
- **False-failure recovery:** canceled an update while the same version was
  already installed; the failure card offered **Retry OBB copy**, which placed
  the OBBs without reinstalling the APK.
- **Empty-folder safety:** with zero OBBs selected and "Remove other OBBs"
  checked, an install preserved the two existing OBBs in `Android/obb`.
- Signature-conflict failure card, system uninstall intent, manual OBB
  add/remove, installed-OBB display after a move, Credits dialog.

Quest 2, Quest 3 and Quest Pro use the same Horizon OS installer and storage
APIs, but only the Quest 3S was available for hardware confirmation. The
Credits screen, the VR-only launch fallback and the 1.0.1 recovery paths were
also exercised on the Android 14 emulator.

## Contributing

Issues and pull requests are welcome. Read [CONTRIBUTING.md](CONTRIBUTING.md)
first. For security problems, use [SECURITY.md](SECURITY.md) instead of a
public issue.

Good bug reports include the krxzyinstall version, Quest/Horizon version, the
failed stage, the on-screen error, and whether the APK installed but the OBB
copy did not.

## Credits and license

krxzyinstall is open source under the **GNU General Public License v3.0**. You
can read it, fork it, change it, and ship your own builds.

**The credits are not optional.** Every distributed build must keep the in-app
Credits screen and [CREDITS.md](CREDITS.md) intact, with **krxzyuzivr** first
and **shrimpo** second. That is an additional term under GPLv3 section 7 -
removing, hiding, renaming, or reordering the original credits is a license
violation. See [NOTICE](NOTICE) and [TRADEMARKS.md](TRADEMARKS.md).

The repository runs `scripts/check-credits.ps1` in CI and requires review of the
credit/license files. This protects the upstream repository; the license terms
apply to redistributed builds and forks as well. Open source does not mean the
credits can be stripped.

## Disclaimer

krxzyinstall is an independent community tool. It is not affiliated with or
endorsed by Meta. Meta Quest and Horizon OS are trademarks of Meta Platforms,
Inc. Only install APKs and OBBs you have the right to use.
