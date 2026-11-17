#!/usr/bin/env python3
"""Install verified JDK/Gradle and Android tools on Apple Silicon or Linux x86_64."""
import concurrent.futures
import hashlib
import json
import os
import platform
from pathlib import Path
import shutil
import tarfile
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
TOOLS = Path(os.environ.get("FLEET_TOOLS_DIR", str(ROOT / ".tools")))
LINUX = platform.system() == "Linux"
SDK = Path(os.environ.get("ANDROID_HOME", str(Path.home() / ("Android/Sdk" if LINUX else "Library/Android/sdk"))))
PINNED = json.loads((ROOT / "remote/toolchain.json").read_text()) if (ROOT / "remote/toolchain.json").exists() else {}
HEADERS = {"User-Agent": "fleet-android-build"}


def read(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=60) as r:
        return r.read()


def install(url, expected, algorithm, destination, archive_name):
    if destination.exists():
        return
    TOOLS.mkdir(parents=True, exist_ok=True)
    archive = TOOLS / archive_name
    print("Downloading", archive_name, flush=True)
    with urllib.request.urlopen(urllib.request.Request(url, headers=HEADERS), timeout=90) as r, archive.open("wb") as f:
        shutil.copyfileobj(r, f)
    actual = hashlib.file_digest(archive.open("rb"), algorithm).hexdigest()
    if actual != expected:
        raise RuntimeError(f"Checksum mismatch for {archive_name}")
    stage = TOOLS / (archive_name + ".extract")
    stage.mkdir(exist_ok=True)
    if archive_name.endswith(".zip"):
        with zipfile.ZipFile(archive) as z:
            for member in z.infolist():
                if not (stage / member.filename).resolve().is_relative_to(stage.resolve()):
                    raise RuntimeError("Invalid archive path")
                z.extract(member, stage)
                mode = member.external_attr >> 16
                if mode:
                    (stage / member.filename).chmod(mode & 0o777)
    else:
        with tarfile.open(archive) as t:
            t.extractall(stage, filter="data")
    contents = list(stage.iterdir())
    if len(contents) != 1:
        raise RuntimeError("Unexpected archive layout")
    destination.parent.mkdir(parents=True, exist_ok=True)
    shutil.move(str(contents[0]), destination)
    stage.rmdir()
    archive.unlink()
    print("Installed", destination.name, flush=True)


def jdk():
    if LINUX:
        if platform.machine() != "x86_64": raise RuntimeError("Remote toolchain requires Linux x86_64")
        item = PINNED["linuxJdk"]
        install(item["url"], item["sha256"], "sha256", TOOLS / "jdk", "jdk.tar.gz")
        return
    r = json.loads(read("https://api.github.com/repos/adoptium/temurin21-binaries/releases/latest"))
    a = next(x for x in r["assets"] if x["name"].startswith("OpenJDK21U-jdk_aarch64_mac_hotspot_") and x["name"].endswith(".tar.gz"))
    checksum = read(a["browser_download_url"] + ".sha256.txt").decode().split()[0]
    install(a["browser_download_url"], checksum, "sha256", TOOLS / "jdk", "jdk.tar.gz")


def gradle():
    url = "https://services.gradle.org/distributions/gradle-8.13-bin.zip"
    checksum = PINNED.get("gradleSha256") or read(url + ".sha256").decode().strip()
    install(url, checksum, "sha256", TOOLS / "gradle", "gradle.zip")


def sdk():
    if LINUX:
        item = PINNED["linuxCommandLineTools"]
        install(item["url"], item["sha1"], "sha1", SDK / "cmdline-tools/latest", "commandline-tools.zip")
        return
    root = ET.fromstring(read("https://dl.google.com/android/repository/repository2-3.xml"))
    package = next(x for x in root if x.attrib.get("path") == "cmdline-tools;latest")
    archive = next(x for x in package.findall("archives/archive") if x.findtext("host-os") == "macosx" and x.findtext("host-arch") == "aarch64")
    install("https://dl.google.com/android/repository/" + archive.findtext("complete/url"), archive.findtext("complete/checksum"), "sha1", SDK / "cmdline-tools/latest", "commandline-tools.zip")


if __name__ == "__main__":
    with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:
        for future in [pool.submit(f) for f in [jdk, gradle, sdk]]:
            future.result()
    print("Toolchain ready. Run scripts/android-env.sh to print environment paths.")
