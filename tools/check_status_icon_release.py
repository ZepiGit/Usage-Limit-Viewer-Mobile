#!/usr/bin/env python3
"""Prove the evidence entry point exists only in the merged debug manifest."""
import argparse
from pathlib import Path
import xml.etree.ElementTree as ET

ENTRY = "com.usagelimits.evidence.StatusIconEvidenceReceiver"
ANDROID_NAME = "{http://schemas.android.com/apk/res/android}name"


def verify(build, evidence):
    evidence.mkdir(parents=True, exist_ok=True)
    report = []
    for variant in ("debug", "release"):
        manifests = sorted({path for directory in ("merged_manifest", "merged_manifests")
                            for path in (build / "intermediates" / directory / variant).rglob("AndroidManifest.xml")})
        if not manifests:
            raise AssertionError(f"No merged {variant} manifest found under {build}")
        for path in manifests:
            root = ET.parse(path).getroot()
            names = [node.get(ANDROID_NAME, "") for node in root.iter()]
            if variant == "debug":
                assert ENTRY in names, f"Debug driver missing from {path}"
            else:
                assert not any("com.usagelimits.evidence" in name for name in names), f"Evidence component leaked into {path}"
            (evidence / f"{variant}-{path.parent.name}-AndroidManifest.xml").write_bytes(path.read_bytes())
            report.append(f"{variant}: {path} — evidence receiver {'present' if variant == 'debug' else 'absent'}")
    text = "\n".join(report) + "\n"
    (evidence / "release-isolation.txt").write_text(text)
    print(text, end="")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", required=True, type=Path)
    parser.add_argument("--evidence", required=True, type=Path)
    args = parser.parse_args()
    verify(args.build, args.evidence)


if __name__ == "__main__":
    main()
