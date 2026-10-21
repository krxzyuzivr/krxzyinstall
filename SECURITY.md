# Security policy

## Reporting a vulnerability

Please do **not** open a public issue for a security problem. Use GitHub's
private vulnerability reporting:

<https://github.com/krxzyuzivr/krxzyinstall/security/advisories/new>

Include the affected version, headset/OS if relevant, reproduction steps, and
what an attacker could do. I will acknowledge reports as soon as I can and
coordinate a fix before public disclosure.

## Signing key

The release signing key is **not** in this repository. Only APKs signed with
the certificate below are official builds:

```
Certificate SHA-256:
d3c9d7b5f7012a996d450f6066e3053d8a7f950b10bf71803a76921041ed0ae4
```

Do not trust an APK with a different certificate. Do not ask for, post, or
commit keystores, `keystore.properties`, passwords, or personal logs.

## Scope

Useful reports include:

- Path traversal or unsafe file handling in the APK/OBB scanner.
- An OBB write that escapes `Android/obb/<package>/`.
- Package-installer result or intent handling that could install the wrong
  file.
- Permission or storage behavior that exposes other apps' data.
- A way a modified build could pass itself off as an official release.

This is a local, on-device tool: it does not run a server and does not upload
game files anywhere.
