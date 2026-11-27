#!/usr/bin/env python3
"""Generate a new Android release signing identity locally. Never commit its output."""
import base64
import getpass
import hashlib
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile

ALIAS = "meowmeow-release"
REPO_ROOT = Path(__file__).resolve().parent.parent
OUT = Path.home() / "MeowMeowFileManager-signing-new"


def run(args, *, env=None, input_text=None):
    return subprocess.run(
        args,
        env=env,
        input=input_text,
        text=True,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def prompt_twice(label, minimum):
    first = getpass.getpass(f"{label} (at least {minimum} characters): ")
    if len(first) < minimum:
        raise ValueError(f"{label} is too short")
    if first != getpass.getpass(f"Repeat {label}: "):
        raise ValueError(f"{label} confirmation does not match")
    return first


def main():
    if sys.version_info < (3, 9):
        raise RuntimeError("Python 3.9 or later is required")
    for cmd in ("keytool", "gpg"):
        try:
            run([cmd, "--version"])
        except (OSError, subprocess.CalledProcessError) as exc:
            raise RuntimeError(f"{cmd} must be installed and on PATH") from exc

    # Keep credentials outside the repository and reject accidental overwrites.
    if OUT.is_relative_to(REPO_ROOT) or OUT.exists():
        raise RuntimeError(f"Output must be new and outside the repository: {OUT}")
    print("This creates a NEW signing identity. Existing signed APKs will not update in place.")
    print("Passwords are entered locally and are never written to repository files.")
    store_password = prompt_twice("Keystore password", 16)
    backup_passphrase = prompt_twice("Encrypted-backup passphrase", 20)
    if store_password == backup_passphrase:
        raise ValueError("Use distinct keystore and backup passwords")

    OUT.mkdir(mode=0o700, parents=False, exist_ok=False)
    os.chmod(OUT, 0o700)
    encrypted = OUT / "release-keystore.p12.gpg"
    encoded = OUT / "encrypted-keystore-base64.txt"
    metadata = OUT / "verification.txt"
    env = os.environ.copy()
    env["MM_STORE_PASSWORD"] = store_password

    with tempfile.TemporaryDirectory(prefix="mm-signing-") as temporary:
        tmp = Path(temporary)
        keystore = tmp / "release-keystore.p12"
        restored = tmp / "restored-keystore.p12"
        run([
            "keytool", "-genkeypair", "-alias", ALIAS,
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-storetype", "PKCS12", "-keystore", str(keystore),
            "-storepass:env", "MM_STORE_PASSWORD",
            "-keypass:env", "MM_STORE_PASSWORD",
            "-dname", "CN=MeowMeow File Manager Release, O=MeowMeow",
            "-noprompt",
        ], env=env)
        run([
            "gpg", "--batch", "--yes", "--pinentry-mode", "loopback",
            "--passphrase-fd", "0", "--symmetric", "--cipher-algo", "AES256",
            "--output", str(encrypted), str(keystore),
        ], input_text=backup_passphrase + "\n")
        run([
            "gpg", "--batch", "--yes", "--pinentry-mode", "loopback",
            "--passphrase-fd", "0", "--decrypt",
            "--output", str(restored), str(encrypted),
        ], input_text=backup_passphrase + "\n")
        if keystore.read_bytes() != restored.read_bytes():
            raise RuntimeError("Keystore restore verification failed")

        keytool = run([
            "keytool", "-list", "-v", "-alias", ALIAS,
            "-storetype", "PKCS12", "-keystore", str(restored),
            "-storepass:env", "MM_STORE_PASSWORD",
        ], env=env)
        match = re.search(r"SHA256:\s*((?:[0-9A-Fa-f]{2}:){31}[0-9A-Fa-f]{2})", keytool.stdout)
        if not match:
            raise RuntimeError("Could not read the new public certificate fingerprint")
        cert_sha256 = match.group(1).replace(":", "").lower()

    backup_sha256 = hashlib.sha256(encrypted.read_bytes()).hexdigest()
    encoded.write_text(base64.b64encode(encrypted.read_bytes()).decode("ascii") + "\n", encoding="ascii")
    metadata.write_text(
        f"SIGNER_CERT_SHA256={cert_sha256}\n"
        f"ENCRYPTED_BACKUP_SHA256={backup_sha256}\n",
        encoding="ascii",
    )
    for path in (encrypted, encoded, metadata):
        os.chmod(path, 0o600)

    print(f"\nLocal signing materials created in: {OUT}")
    print("Keep the encrypted backup and both passwords securely outside GitHub.")
    print("Set GitHub Actions secrets:")
    print("  SIGNING_STORE_PASSWORD = the new keystore password")
    print("  SIGNING_BACKUP_PASSPHRASE = the new backup passphrase")
    print("  SIGNING_ENCRYPTED_KEYSTORE_BASE64 = contents of encrypted-keystore-base64.txt")
    print("Update the two public SHA-256 pins in .github/workflows/auto-release.yml")
    print(f"  SIGNER_CERT_SHA256={cert_sha256}")
    print(f"  ENCRYPTED_BACKUP_SHA256={backup_sha256}")
    print("Do not publish the repository until old Git history has been cleaned.")


if __name__ == "__main__":
    try:
        main()
    except (RuntimeError, ValueError, subprocess.CalledProcessError, OSError) as error:
        print(f"Error: {error}", file=sys.stderr)
        sys.exit(1)
