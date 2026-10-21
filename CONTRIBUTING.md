# Contributing

Thanks for wanting to help. krxzyinstall is a small, dependency-free Android
app, so focused fixes and real-device testing are the most useful things you
can bring.

## Before you start

- Check existing [issues](https://github.com/krxzyuzivr/krxzyinstall/issues) and
  pull requests first.
- For a large change, open an issue and describe it before writing a lot of
  code.
- Keep changes focused. Avoid adding AndroidX or third-party libraries unless
  there is a strong reason; the app is intentionally plain Java + framework
  APIs.
- Never commit `keystore.properties`, a `.jks`, `local.properties`, `dist/`,
  tokens, personal logs, or game files.
- Keep the credits intact. See [NOTICE](NOTICE) and [CREDITS.md](CREDITS.md).

## Reporting bugs

Use the [Bug report](https://github.com/krxzyuzivr/krxzyinstall/issues/new/choose)
form and include:

- krxzyinstall version (for example `1.0.1`, versionCode `2`)
- Quest model and Horizon OS version
- What stage failed: permissions, scanning, APK install, OBB copy, launch
- The game/package name and the OBB filenames
- The exact on-screen error, plus a screenshot if possible
- Whether the APK itself installed but the OBBs did not
- `adb logcat -s krxzyinstall` output if you have a PC; otherwise a clear
  description is fine

Remove account names, IP addresses, and other personal data before posting.
For security problems, use [SECURITY.md](SECURITY.md) instead of a public issue.

## Development workflow

1. Fork the repository and create a branch:
   ```bash
   git checkout -b fix/short-description
   ```
2. Make the change and build a debug APK:
   ```bash
   ./gradlew assembleDebug
   ```
   On Windows:
   ```bat
   gradlew.bat assembleDebug
   ```
3. Test on a Quest or the Android 14 emulator. For installer/OBB changes,
   test at least:
   - a fresh APK + two OBBs,
   - updating the same package with OBBs already in `Android/obb`,
   - canceling the system install dialog,
   - a package with zero OBBs.
4. Run the credit check:
   ```powershell
   powershell -ExecutionPolicy Bypass -File scripts\check-credits.ps1
   ```
5. Open a pull request using the template. Say what changed, why, and how you
   tested it.

## What we look for

- Small, readable commits.
- No new secrets, hard-coded local paths, or unrelated refactors.
- Matches the structure and style of the surrounding Java/XML.
- Credits and license notices untouched.
- A note about the headset/emulator and Android version used for testing.
- For OBB changes: confirm the destination path stays inside
  `Android/obb/<package>/` and source files are only deleted after a verified
  copy.

## Credits and license

By contributing, you agree that your contribution is licensed under the
project's GPL-3.0 license and the additional credit-preservation terms in
[NOTICE](NOTICE). New contributors may be added below the original credits;
the two original entries must stay first and second.
