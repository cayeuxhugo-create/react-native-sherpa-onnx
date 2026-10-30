"""Read-only APK audit fixtures with real PowerShell; no Android device needed."""
import json
import os
from pathlib import Path
import subprocess
import tempfile
import zipfile


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / "diagnostiquer-taille-apk.ps1"
PWSH = os.environ.get("PWSH", "pwsh")


def apk(path, entries):
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED) as archive:
        for name, payload in entries.items():
            archive.writestr(name, payload, compress_type=(zipfile.ZIP_STORED if name.startswith("lib/") else zipfile.ZIP_DEFLATED))


with tempfile.TemporaryDirectory(prefix="guardian-apk-audit-") as temporary:
    root = Path(temporary)
    tools = root / "tools"
    tools.mkdir()
    aapt = tools / ("aapt.cmd" if os.name == "nt" else "aapt")
    if os.name == "nt":
        # Linux CI exercises the actual native stdout/stderr path. The Windows
        # fixture can still exercise grouping with an unavailable detector.
        aapt.write_text("@exit /b 1\n")
    else:
        aapt.write_text("#!/usr/bin/env python3\nimport os,sys\n"
                        "mode=os.environ.get('AUDIT_FIXTURE_AAPT','unknown')\n"
                        "if mode=='unknown': sys.exit(2)\n"
                        "print(\"package: name='example.fixture' versionCode='1'\")\n"
                        "if mode=='debug': print('application-debuggable')\n")
        aapt.chmod(0o755)
    env = os.environ.copy()
    env.update(PATH=str(tools) + os.pathsep + env["PATH"],
               ANDROID_SDK_ROOT="", ANDROID_HOME="", LOCALAPPDATA="",
               XDG_CACHE_HOME=str(root / "cache"), XDG_CONFIG_HOME=str(root / "config"),
               XDG_DATA_HOME=str(root / "data"))
    current = root / "new APK with spaces.apk"
    previous = root / "old APK.apk"
    entries = {
        "AndroidManifest.xml": b"<manifest/>",
        "classes.dex": bytes(range(256)) * 10,
        "classes2.dex": b"second dex" * 200,
        "assets/model.onnx": bytes(range(128)) * 100,
        "assets/Model.onnx": b"distinct case-sensitive asset" * 50,
        "assets/folder/info.txt": b"fixture asset",
        "resources.arsc": b"resource table" * 10,
        "res/drawable/icon.png": b"fixture icon" * 30,
        "META-INF/CERT.RSA": b"fixture signing entry",
    }
    for index, abi in enumerate(["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]):
        entries[f"lib/{abi}/libfixture.so"] = bytes(range(200)) * (index + 10)
    for index in range(24):
        entries[f"res/raw/fixture{index}.bin"] = bytes(range(128)) * (index + 1)
    apk(current, entries)
    old_entries = {k: v for k, v in entries.items()
                   if "x86" not in k and k not in ["classes2.dex", "assets/Model.onnx"]}
    old_entries["assets/removed.onnx"] = b"old fixture" * 100
    apk(previous, old_entries)
    originals = {p: p.read_bytes() for p in [current, previous]}
    original_times = {p: p.stat().st_mtime_ns for p in originals}

    def run(name, path=current, old=None, mode="unknown", success=True):
        report = root / ("report-" + name)
        argv = [PWSH, "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
                "-File", str(SCRIPT), "-ApkPath", str(path),
                "-ReportDirectory", str(report)]
        if old is not None:
            argv += ["-PreviousApkPath", str(old)]
        result = subprocess.run(argv, capture_output=True, text=True,
                                env=dict(env, AUDIT_FIXTURE_AAPT=mode))
        assert (result.returncode == 0) == success, result.stdout + result.stderr
        for p, data in originals.items():
            assert p.read_bytes() == data and p.stat().st_mtime_ns == original_times[p]
        if not success:
            assert not report.exists(), "Invalid inputs must not leave audit reports"
            return
        files = list(report.glob("*.json"))
        assert len(files) == 1 and len(list(report.glob("*.txt"))) == 1
        return json.loads(files[0].read_text(encoding="utf-8-sig"))

    result = run("sizes")
    data = result["current"]
    assert data["fileBytes"] == current.stat().st_size
    assert data["debuggable"]["value"] is None
    assert sorted(a["name"] for a in data["abis"]) == ["arm64-v8a", "armeabi-v7a", "x86", "x86_64"]
    with zipfile.ZipFile(current) as archive:
        expected = {i.filename: (i.compress_size, i.file_size) for i in archive.infolist()}
    assert data["entryCount"] == len(expected)
    assert data["entriesCompressedBytes"] == sum(x[0] for x in expected.values())
    assert data["entriesUncompressedBytes"] == sum(x[1] for x in expected.values())
    assert data["zipAndSigningOverheadBytes"] + data["entriesCompressedBytes"] == data["fileBytes"]
    for category in data["categories"]:
        matching = [e for e in data["entries"] if e["category"] == category["name"]]
        assert category["compressedBytes"] == sum(expected[e["name"]][0] for e in matching)
        assert category["uncompressedBytes"] == sum(expected[e["name"]][1] for e in matching)
    assert [e["compressedBytes"] for e in data["largestEntries"]] == sorted(
        [e[0] for e in expected.values()], reverse=True)[:20]
    print("PASS APK sizes: four ABIs, compressed/uncompressed totals, categories, overhead and ownership")

    compared = run("comparison", old=previous)["comparison"]
    assert compared["fileBytesDelta"] == current.stat().st_size - previous.stat().st_size
    changes = {e["name"]: e for e in compared["entryChanges"]}
    assert changes["assets/removed.onnx"]["status"] == "removed"
    assert changes["classes2.dex"]["status"] == "added"
    assert changes["assets/Model.onnx"]["status"] == "added"
    assert "assets/model.onnx" not in changes
    abi_changes = {a["name"]: a for a in compared["abiChanges"]}
    assert abi_changes["arm64-v8a"]["compressedBytesDelta"] == 0
    assert abi_changes["x86"]["compressedBytesDelta"] == expected["lib/x86/libfixture.so"][0]
    print("PASS APK comparison: exact file delta, added/removed entries and per-ABI changes")

    if os.name != "nt":
        assert run("debug", mode="debug")["current"]["debuggable"]["value"] is True
        assert run("release", mode="release")["current"]["debuggable"]["value"] is False
        print("PASS debug detection: aapt metadata controls true/false; failures remain unknown")

    invalid = root / "not-an-apk.zip"
    apk(invalid, {"readme.txt": b"fixture"})
    run("missing-manifest", path=invalid, success=False)
    run("missing-file", path=root / "missing.apk", success=False)
    run("invalid-previous", old=invalid, success=False)
    broken = root / "broken.apk"
    broken.write_bytes(b"not ZIP bytes")
    run("broken-zip", path=broken, success=False)
    print("PASS invalid inputs: missing file/manifest, broken ZIP and invalid comparison rejected without mutation")
