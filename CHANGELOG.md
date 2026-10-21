# Changelog

## 1.0.1 - 2026-10-20

- Detect a successful APK install even when Horizon OS never delivers the
  PackageInstaller result, or reports a false failure.
- Continue to the OBB copy automatically after that detection.
- Add **Retry OBB copy** recovery when the APK is already installed but the OBB
  step failed.
- Make **Remove other OBBs already in the game folder** selectable even when
  the folder is empty, with a guard that prevents deleting anything when no OBB
  is selected.
- Refresh destination/OBB information after a successful install and remove
  stale rows after old OBBs are deleted.
- Fix the file browser not loading after file access is granted.
- Add the Credits screen (krxzyuzivr and shrimpo).
- Add a VR-only launch fallback for Quest apps without a standard launcher
  entry.
- Handle OBB names case-insensitively, clean up stale `.krxpart` files, and
  reuse storage when replacing large OBBs.

## 1.0.0

- Initial release: scan APKs/OBBs, group by package and version, install one
  APK per package, move or copy OBBs into `Android/obb`, and launch the game.
