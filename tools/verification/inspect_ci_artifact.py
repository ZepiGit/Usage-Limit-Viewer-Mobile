#!/usr/bin/env python3
"""Read pinned CI evidence; never extract files, build, run a device, or upload."""
import argparse
import base64
from collections import Counter
import hashlib
import io
import json
import os
from pathlib import PurePosixPath
import re
import stat
import struct
import sys
import urllib.error
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
import zipfile

REPOSITORY = "ZepiGit/Usage-Limit-Viewer-Mobile"
API = "https://api.github.com/repos/" + REPOSITORY
MIB = 1024 * 1024
OUTPUT_BYTES = 0


def emit(kind, **value):
    global OUTPUT_BYTES
    line = "ARTIFACT_RECORD " + json.dumps(
        {"kind": kind, **value}, ensure_ascii=True, separators=(",", ":"))
    OUTPUT_BYTES += len(line) + 1
    assert OUTPUT_BYTES <= 16 * MIB, "Output budget exceeded"
    print(line, flush=True)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args):
        return None


def download(args):
    client = urllib.request.build_opener(NoRedirect)

    def request(path):
        return urllib.request.Request(API + path, headers={
            "Authorization": "Bearer " + os.environ["GITHUB_TOKEN"],
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        })

    with client.open(request(f"/actions/artifacts/{args.artifact_id}"), timeout=30) as response:
        metadata = json.loads(response.read(MIB))
    assert metadata["id"] == args.artifact_id and not metadata["expired"], "Artifact identity/expiry mismatch"
    assert metadata["name"] == args.name, "Artifact name mismatch"
    assert metadata["workflow_run"]["id"] == args.run_id, "Artifact run mismatch"
    assert metadata["workflow_run"]["head_sha"] == args.head_sha, "Artifact head mismatch"
    assert metadata["digest"] == "sha256:" + args.digest, "Artifact digest metadata mismatch"
    assert 0 < metadata["size_in_bytes"] <= 512 * MIB, "Archive exceeds 512 MiB"
    try:
        response = client.open(request(f"/actions/artifacts/{args.artifact_id}/zip"), timeout=60)
    except urllib.error.HTTPError as error:
        assert error.code == 302, "Unexpected archive response"
        location = error.headers["Location"]
        assert urllib.parse.urlsplit(location).scheme == "https", "Non-HTTPS archive redirect"
        # A separate request never forwards the GitHub Authorization header.
        response = urllib.request.urlopen(location, timeout=60)
    with response:
        archive = response.read(metadata["size_in_bytes"] + 1)
    assert len(archive) == metadata["size_in_bytes"], "Archive size mismatch"
    assert hashlib.sha256(archive).hexdigest() == args.digest, "Archive bytes digest mismatch"
    emit("artifact", id=args.artifact_id, name=args.name, runId=args.run_id,
         headSha=args.head_sha, expectedCheckoutSha=args.checkout_sha,
         bytes=len(archive), sha256=args.digest)
    return archive


def entries(zipped):
    members = zipped.infolist()
    assert 0 < len(members) <= 12000, "ZIP entry budget exceeded"
    assert sum(item.file_size for item in members) <= 2048 * MIB, "Expanded ZIP budget exceeded"
    result = {}
    seen = set()
    for item in members:
        path = PurePosixPath(item.filename)
        assert not path.is_absolute() and ".." not in path.parts, "Unsafe ZIP path"
        assert not re.search(r"[\\:\x00-\x1f]", item.filename), "Unsafe ZIP path characters"
        assert path.as_posix() == item.filename.rstrip("/") and len(item.filename) <= 512, "Noncanonical ZIP path"
        assert item.filename not in seen, "Duplicate ZIP path"
        seen.add(item.filename)
        assert not stat.S_ISLNK(item.external_attr >> 16), "ZIP symlink rejected"
        assert not item.flag_bits & 1, "Encrypted ZIP entry rejected"
        assert item.file_size <= 128 * MIB, "ZIP member budget exceeded"
        if not item.is_dir():
            result[item.filename] = item
    return result


def lookup(files, name, required=True):
    found = [item for path, item in files.items() if PurePosixPath(path).name == name]
    assert len(found) <= 1, "Ambiguous selected basename"
    assert found or not required, "Required report missing"
    return found[0] if found else None


def read(zipped, entry, limit=8 * MIB):
    assert entry.file_size <= limit, "Selected report budget exceeded"
    return zipped.read(entry)  # Also verifies the selected member's CRC.


def xml(zipped, entry):
    data = read(zipped, entry)
    assert b"<!DOCTYPE" not in data.upper() and b"<!ENTITY" not in data.upper(), "XML entities rejected"
    return ET.fromstring(data)


def unit(zipped, files):
    suites = [item for path, item in sorted(files.items())
              if re.fullmatch(r"TEST-.*\.xml", PurePosixPath(path).name)]
    assert suites, "JUnit reports missing"
    totals = Counter(tests=0, failures=0, errors=0, skipped=0)
    for entry in suites:
        root = xml(zipped, entry)
        assert root.tag == "testsuite", "Unexpected JUnit root"
        counts = {key: int(root.get(key, "0")) for key in totals}
        assert all(value >= 0 for value in counts.values()), "Negative JUnit count"
        assert counts["tests"] == len(root.findall("testcase")), "JUnit test count mismatch"
        totals.update(counts)
        for case in root.findall("testcase"):
            for child in case:
                if child.tag in {"failure", "error"}:
                    # No system-out, provider fixtures, failure payloads, or stack dumps.
                    emit("junit-failure", path=entry.filename, suite=root.get("name"),
                         test=case.get("name"), className=case.get("classname"),
                         failureType=child.get("type"), kindOfFailure=child.tag)
    emit("junit-totals", suites=len(suites), **totals)
    lint = lookup(files, "lint-results-debug.xml")
    root = xml(zipped, lint)
    assert root.tag == "issues", "Unexpected lint root"
    counts = Counter(issue.get("severity", "UNKNOWN") for issue in root.findall("issue"))
    emit("lint-severity", path=lint.filename, counts=dict(sorted(counts.items())))
    for path in sorted(files):
        if path.endswith(".png") and "widget-renderings" in PurePosixPath(path).parts:
            emit("png-path", path=path, bytes=files[path].file_size)


def apk(zipped, files):
    for name in ("apk-metadata.txt", "apk-previous-metadata.txt"):
        entry = lookup(files, name, required=name == "apk-metadata.txt")
        if entry is None:
            emit("missing", name=name)
            continue
        text = read(zipped, entry).decode("utf-8")
        match = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", text, re.M)
        assert match, "Unrecognized APK metadata"
        emit("apk-metadata", path=entry.filename, package=match[1],
             versionCode=int(match[2]), versionName=match[3])
    entry = lookup(files, "apk-signature.txt")
    text = read(zipped, entry).decode("utf-8")
    certificates = sorted(set(re.findall(
        r"^(?:Signer #\d+|V\d+(?:\.\d+)* Signer:) certificate SHA-256 digest: ([0-9a-fA-F]{64})$",
        text, re.M)))
    assert certificates, "No signing certificate SHA-256"
    schemes = re.findall(r"^Verified using (.+): (true|false)$", text, re.M)
    emit("apk-signature", certificateSha256=[value.lower() for value in certificates], schemes=schemes)
    entry = lookup(files, "apk-sha256.txt")
    match = re.fullmatch(r"([0-9a-fA-F]{64})\s+\*?[^\r\n]+\s*", read(zipped, entry).decode("utf-8"))
    assert match, "Unrecognized APK checksum report"
    emit("apk-sha256", sha256=match[1].lower())
    entry = lookup(files, "apk-upgrade.txt")
    text = read(zipped, entry).decode("utf-8").strip()
    continuity = "Package matches, versionCode increased, and signing certificate matches v0.2.0."
    unsigned = "Verification build only; release signing is unavailable."
    assert text.endswith(continuity) or text == unsigned, "Unknown upgrade verification report"
    emit("apk-upgrade", signingContinuityReported=text.endswith(continuity), verificationOnly=text == unsigned)
    entry = lookup(files, "apk-runtime-dependencies.txt")
    text = read(zipped, entry).decode("utf-8")
    assert "releaseRuntimeClasspath" in text, "Wrong dependency configuration"
    dependencies = set()
    pattern = re.compile(r"(androidx\.(?:work|datastore):[A-Za-z0-9_.-]+):([A-Za-z0-9_.+-]+)(?: -> ([A-Za-z0-9_.+-]+))?")
    for line in text.splitlines():
        match = pattern.search(line)
        if match:
            assert "FAILED" not in line, "Dependency resolution failed"
            dependencies.add((match[1], match[3] or match[2]))
    assert any(key.startswith("androidx.work:") for key, _ in dependencies), "WorkManager resolution missing"
    assert any(key.startswith("androidx.datastore:") for key, _ in dependencies), "DataStore resolution missing"
    emit("resolved-dependencies", values=[{"module": key, "version": value} for key, value in sorted(dependencies)])


def runtime(zipped, files, args):
    phases = ("current", "move", "resize", "night-yes", "night-no",
              "process-loss", "launcher-restart", "reboot", "offline", "online")
    names = ("environment.json", "result.json", "widgets-placement.json",
             "widgets-move-resize.json", "widgets-process-loss.json",
             "widgets-launcher-restart.json", "widgets-reboot.json", "widgets-network.json",
             *(phase + "-widgets-verified.json" for phase in phases))
    assert args.checkout_sha, "Runtime requires the separate checkout SHA"
    for name in names:
        entry = lookup(files, name, required=name == "environment.json")
        if entry is None:
            emit("missing", name=name)
            continue
        data = read(zipped, entry, 128 * 1024)
        value = json.loads(data)
        if name in {"environment.json", "result.json"}:
            assert value["commit"] == args.checkout_sha, "Runtime checkout SHA mismatch"
        if name == "environment.json":
            assert str(value["runId"]) == str(args.run_id), "Runtime environment run mismatch"
        emit("runtime-json", path=entry.filename, sha256=hashlib.sha256(data).hexdigest(), value=value)
    for name in ("previous-package.txt", "current-package.txt"):
        entry = lookup(files, name, required=False)
        if entry is None:
            emit("missing", name=name)
            continue
        text = read(zipped, entry).decode("utf-8")
        fields = {}
        for key, pattern in {
            "versionCode": r"versionCode=(\d+)", "versionName": r"versionName=([^\r\n]+)",
            "appId": r"\bappId=(\d+)", "firstInstallTime": r"firstInstallTime=([^\r\n]+)",
        }.items():
            match = re.search(pattern, text)
            assert match, "Package field missing"
            fields[key] = match[1].strip()
        emit("package-fields", path=entry.filename, value=fields)
    selected = re.compile(
        r"(?:.*-widget-\d+-content-\d+|"
        r".*-config-verified-(?:Navy-background|White-background|Auto|All-accounts)|"
        r"move-(?:resize-before|after|target-original|focus-\d+)|resize-(?:increase|decrease)-(?:width|height)-after)"
        r"\.(?:png|xml)$")
    for path, entry in sorted(files.items()):
        if selected.fullmatch(PurePosixPath(path).name):
            emit("runtime-path", path=path, bytes=entry.file_size)
            if "-config-verified-" in path and path.endswith(".xml"):
                root = xml(zipped, entry)
                labels = {"Navy background", "White background", "Auto", "All accounts", "Opacity · 100 %"}
                controls = [{key: node.get(key) for key in (
                    "text", "content-desc", "checked", "selected", "enabled", "bounds")}
                    for node in root.iter("node") if node.get("package") == "com.usagelimits"
                    and (node.get("text") in labels or node.get("content-desc") in labels)]
                emit("config-controls", path=path, controls=controls)


    focus_names = ["move-target-original.xml", *(f"move-focus-{index}.xml" for index in range(4))]
    for name in focus_names:
        entry = lookup(files, name, required=False)
        if entry is None:
            emit("missing", name=name)
            continue
        root = xml(zipped, entry)
        keys = ("class", "resource-id", "text", "content-desc", "bounds",
                "package", "focusable", "focused", "enabled")
        describe = lambda node: {key: node.get(key) for key in keys}
        focused = [describe(node) for node in root.iter("node") if node.get("focused") == "true"]
        parents = {child: parent for parent in root.iter() for child in parent}
        chains = []
        for node in root.iter("node"):
            if node.get("resource-id", "").endswith(":id/label") and node.get("text", "").startswith("Move to row "):
                chain = []
                while node is not None:
                    chain.append(describe(node))
                    node = parents.get(node)
                chains.append(chain)
        emit("move-focus-xml", path=entry.filename, focusedNodes=focused, labelAncestorChains=chains)


def pngs(zipped, files, paths, mode):
    assert len(paths) <= 2 and len(set(paths)) == len(paths), "Select zero to two distinct PNG paths"
    for path in paths:
        assert path in files and path.endswith(".png"), "Requested PNG is absent"
        assert not path.endswith("-before-dump.png"), "Transition PNG is not a settled image"
        if mode == "unit":
            assert "widget-renderings" in PurePosixPath(path).parts, "Not a widget rendering"
        else:
            assert mode == "runtime", "PNG output is unavailable in APK mode"
            assert re.search(r"(?:-content-\d+|move-(?:resize-before|after|target-original|focus-\d+)|resize-(?:increase|decrease)-(?:width|height)-after)\.png$", path), "Not a widget content/geometry PNG"
        data = read(zipped, files[path], 4 * MIB)
        assert data[:16] == b"\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR", "Invalid PNG header"
        width, height = struct.unpack(">II", data[16:24])
        assert 0 < width <= 8192 and 0 < height <= 8192, "Unexpected PNG dimensions"
        hosts = []
        if mode == "runtime":
            xml_path = path[:-4] + ".xml"
            assert xml_path in files, "Paired PNG XML missing"
            root = xml(zipped, files[xml_path])
            hosts = [{"label": node.get("content-desc"), "bounds": node.get("bounds")}
                     for node in root.iter("node") if node.get("class", "").endswith("AppWidgetHostView")]
            if re.search(r"/?move-(?:target-original|focus-\d+)\.png$", path):
                assert any(node.get("resource-id", "").endswith(":id/label")
                           and node.get("text", "").startswith("Move to row ")
                           for node in root.iter("node")), "No move label in paired XML"
            else:
                assert hosts, "No widget host in paired XML"
        encoded = base64.b64encode(data).decode("ascii")
        count = (len(encoded) + 4095) // 4096
        emit("png-begin", path=path, bytes=len(data), sha256=hashlib.sha256(data).hexdigest(),
             width=width, height=height, hosts=hosts, encoding="base64-original-png", chunks=count)
        for index in range(count):
            emit("png-chunk", path=path, index=index, data=encoded[index * 4096:(index + 1) * 4096])
        emit("png-end", path=path, chunks=count)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--mode", choices=("unit", "apk", "runtime"), required=True)
    parser.add_argument("--artifact-id", type=int, required=True)
    parser.add_argument("--name", required=True)
    parser.add_argument("--digest", required=True)
    parser.add_argument("--run-id", type=int, required=True)
    parser.add_argument("--head-sha", required=True)
    parser.add_argument("--checkout-sha")
    parser.add_argument("--png", action="append", default=[])
    args = parser.parse_args()
    assert re.fullmatch(r"[0-9a-f]{64}", args.digest), "Invalid digest argument"
    assert re.fullmatch(r"[0-9a-f]{40}", args.head_sha), "Invalid head SHA argument"
    assert args.checkout_sha is None or re.fullmatch(r"[0-9a-f]{40}", args.checkout_sha), "Invalid checkout SHA argument"
    archive = download(args)
    with zipfile.ZipFile(io.BytesIO(archive)) as zipped:
        files = entries(zipped)
        emit("zip-verified", files=len(files), expandedBytes=sum(item.file_size for item in files.values()))
        if args.mode == "unit":
            unit(zipped, files)
        elif args.mode == "apk":
            apk(zipped, files)
        else:
            runtime(zipped, files, args)
        pngs(zipped, files, args.png, args.mode)
    emit("complete", meaning="Evidence extraction only; source CI conclusions and missing runtime phases are unchanged.")


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        # Do not print exceptions containing signed download URLs, headers, or raw reports.
        message = str(error) if isinstance(error, AssertionError) else type(error).__name__
        print("ARTIFACT_READER_FAILED " + json.dumps(message), file=sys.stderr)
        raise SystemExit(1)
