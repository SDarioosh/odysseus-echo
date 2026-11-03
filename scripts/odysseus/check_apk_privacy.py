#!/usr/bin/env python3
"""Check a built APK for Google telemetry and cloud endpoints (Odysseus fork).

Fails (exit 1) if the APK contains Firebase / Crashlytics / ML Kit / Play services /
Google data transport classes, ad-id permissions, or known telemetry hostnames.

Usage: check_apk_privacy.py path/to/app.apk
"""
import re
import sys
import zipfile

# Class-name prefixes (as they appear in dex string tables) that mean telemetry is linked in.
BANNED_CLASSES = [
    b"Lcom/google/firebase/",
    b"Lcom/google/mlkit/",
    b"Lcom/google/android/gms/",
    b"Lcom/google/android/datatransport/",
    b"Lcom/google/android/play/core/",
]
# Hostnames that receive telemetry.
BANNED_HOSTS = [
    b"app-measurement.com",
    b"firebaseinstallations.googleapis.com",
    b"firebaselogging",
    b"crashlyticsreports-pa.googleapis.com",
    b"settings.crashlytics.com",
    b"firebase-settings.crashlytics.com",
    b"firebaseremoteconfig.googleapis.com",
    b"play.googleapis.com/log",
    b"clearcut",
]
# Permissions declared by ad/attribution libraries (AXML stores strings as UTF-16).
BANNED_PERMISSIONS = [
    "com.google.android.gms.permission.AD_ID",
    "android.permission.ACCESS_ADSERVICES_AD_ID",
    "android.permission.ACCESS_ADSERVICES_ATTRIBUTION",
    "com.google.android.finsky.permission.BIND_GET_INSTALL_REFERRER_SERVICE",
]


def main(path: str) -> int:
    problems = []
    with zipfile.ZipFile(path) as z:
        names = z.namelist()
        for n in names:
            if re.search(r"firebase|crashlytics|mlkit|face_detector", n, re.I):
                problems.append(f"file: {n}")
        for n in names:
            if n.endswith(".dex"):
                data = z.read(n)
                for c in BANNED_CLASSES:
                    if c in data:
                        problems.append(f"{n}: class prefix {c.decode()}")
                for h in BANNED_HOSTS:
                    if h in data:
                        problems.append(f"{n}: host {h.decode()}")
            elif n.startswith("lib/") and n.endswith(".so"):
                data = z.read(n)
                for h in BANNED_HOSTS:
                    if h in data:
                        problems.append(f"{n}: host {h.decode()}")
        manifest = z.read("AndroidManifest.xml").decode("utf-16-le", "ignore")
        for p in BANNED_PERMISSIONS:
            if p in manifest:
                problems.append(f"manifest: permission {p}")
        abis = sorted({n.split("/")[1] for n in names if n.startswith("lib/")})
    print(f"{path}: ABIs {abis}")
    if problems:
        print("PRIVACY CHECK FAILED:")
        for p in sorted(set(problems)):
            print("  " + p)
        return 1
    print("privacy check passed: no Firebase/ML Kit/Play services/telemetry hosts found")
    return 0


if __name__ == "__main__":
    if len(sys.argv) != 2:
        print(__doc__)
        sys.exit(2)
    sys.exit(main(sys.argv[1]))
