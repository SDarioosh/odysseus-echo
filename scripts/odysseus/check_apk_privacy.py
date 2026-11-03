#!/usr/bin/env python3
"""Check a built APK for Google telemetry and cloud endpoints (Odysseus fork).

Fails (exit 1) if the APK contains Firebase / Crashlytics / ML Kit / Play services /
Google data transport classes, ad-id permissions, or known telemetry hostnames.

Usage: check_apk_privacy.py path/to/app.apk
"""
import re
import struct
import sys
import zipfile


def _uleb(data, off):
    result = shift = 0
    while True:
        b = data[off]
        off += 1
        result |= (b & 0x7F) << shift
        if b < 0x80:
            return result, off
        shift += 7


def defined_classes(dex: bytes):
    """Names of classes *defined* in a dex file (not merely referenced)."""
    string_ids_size, string_ids_off, type_ids_size, type_ids_off = struct.unpack_from("<4I", dex, 0x38)
    class_defs_size, class_defs_off = struct.unpack_from("<2I", dex, 0x60)

    def string(i):
        (data_off,) = struct.unpack_from("<I", dex, string_ids_off + 4 * i)
        _, start = _uleb(dex, data_off)
        end = dex.index(b"\0", start)
        return dex[start:end]

    for k in range(class_defs_size):
        (type_idx,) = struct.unpack_from("<I", dex, class_defs_off + 32 * k)
        (desc_idx,) = struct.unpack_from("<I", dex, type_ids_off + 4 * type_idx)
        yield string(desc_idx)

# Packages whose classes, if *defined* in the APK, mean Google telemetry/services are linked in.
# (Bare references are allowed: e.g. LiteRT references optional Play AI-pack types that are
# not shipped, so they can never load.)
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
                for cls in defined_classes(data):
                    for c in BANNED_CLASSES:
                        if cls.startswith(c):
                            problems.append(f"{n}: defines {c.decode()}*")
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
