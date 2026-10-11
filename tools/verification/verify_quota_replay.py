#!/usr/bin/env python3
"""Verify the narrowly scoped quota replay, including expected baseline assertions."""

import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
import sys
import xml.etree.ElementTree as ET


CLASS = "com.usagelimits.core.model.QuotaScreenshotRegressionTest"
FIXTURE = Path("app/src/test/kotlin/com/usagelimits/core/model/QuotaScreenshotRegressionTest.kt")
FIXTURE_SHA256 = "6fe8ece65f3a99d0b071948f7110a99005bbd660b495d754a8e9a28c72f1ab81"
WRAPPER = Path("gradle/wrapper/gradle-wrapper.properties")
WRAPPER_SHA256 = "dfca8a2f2c16bad3529398689eaf73ecdf8e20baf979ce0dab3fc24295b229de"
XML_DIRECTORY = Path("app/build/test-results/testDebugUnitTest")
XML_FILENAME = "TEST-" + CLASS + ".xml"

# JUnit may compact String comparisons with brackets. The exact fixture is pinned
# above; these rules identify its first expected failure rather than accepting any red build.
RULES = {
    "Claude 100 37 and Fable zero retain the 25 day reset without global exhaustion": {
        "bug": "Model-specific exhaustion was summarized as global exhaustion",
        "prefix": "A model-specific zero is a restriction, not global exhaustion",
        "suffix": "expected:<LIMITED> but was:<EXHAUSTED>",
    },
    "Grok weekly eighty remains usable beside an unknown monthly value": {
        "bug": "An unknown monthly row turned measured weekly availability into Error",
        "prefix": "An unknown optional row must not replace measured availability with Error",
        "suffix": "expected:<HEALTHY> but was:<ERROR>",
    },
    "Grok current period does not turn missing null or malformed credits into zero spend": {
        "bug": "An absent current-period credit percentage was fabricated as zero spend",
        "prefix": "Missing or unreadable usage is not measured zero:",
        "suffix": "expected null, but was:<0.0>",
    },
    "Grok preserves explicit zero money and refuses invalid spend or alias fallback": {
        "bug": "Negative monetary spend was treated as a measured percentage",
        "prefix": "A present invalid/null spend must not fall back to includedUsed zero: -1",
        "suffix": "expected null, but was:<-0.1>",
    },
}
ASSERTION_TYPES = {"java.lang.AssertionError", "org.junit.ComparisonFailure"}


def verify(subject, role, expected_sha, gradle_exit_code):
    errors = []
    report = {
        "role": role,
        "subjectDirectory": str(subject),
        "expectedSha": expected_sha,
        "actualSha": None,
        "gradleExitCode": gradle_exit_code,
        "fixturePath": str(FIXTURE),
        "fixtureSha256": None,
        "expectedFixtureSha256": FIXTURE_SHA256,
        "wrapperPropertiesPath": str(WRAPPER),
        "wrapperPropertiesSha256": None,
        "expectedWrapperPropertiesSha256": WRAPPER_SHA256,
        "buildBootstrap": "Both replay jobs overlay the identical pinned fixture and the verification branch's patched Gradle 8.14.6 wrapper properties/JAR onto the selected application revision. This tests baseline application behavior with a secure bootstrap, not the historical toolchain.",
        "expectedTestClass": CLASS,
        "expectedTestNames": list(RULES),
        "xmlFiles": [],
        "xmlCases": [],
        "limitations": "Synthetic parser/snapshot replay; no provider calls or live-account verification. A failing JUnit test stops at its first assertion, so later assertions in that test are not baseline execution evidence.",
        "validationErrors": errors,
    }

    if not re.fullmatch(r"[0-9a-f]{40}", expected_sha):
        errors.append("expected-sha must be the complete lowercase 40-character commit SHA")
    try:
        result = subprocess.run(
            ["git", "rev-parse", "--verify", "HEAD"], cwd=subject,
            check=True, text=True, capture_output=True, timeout=10,
        )
        report["actualSha"] = result.stdout.strip()
        if report["actualSha"] != expected_sha:
            errors.append("Subject HEAD does not match expected-sha")
    except (OSError, subprocess.SubprocessError) as error:
        errors.append("Cannot establish subject HEAD: " + str(error))

    for relative, key, expected_hash in [
        (FIXTURE, "fixtureSha256", FIXTURE_SHA256),
        (WRAPPER, "wrapperPropertiesSha256", WRAPPER_SHA256),
    ]:
        try:
            report[key] = hashlib.sha256((subject / relative).read_bytes()).hexdigest()
            if report[key] != expected_hash:
                errors.append(str(relative) + " does not match the pinned replay input")
        except OSError as error:
            errors.append("Cannot read " + str(relative) + ": " + str(error))

    if role == "baseline" and gradle_exit_code == 0:
        errors.append("Baseline Gradle must fail with the four expected assertions")
    if role == "fixed" and gradle_exit_code != 0:
        errors.append("Fixed Gradle must exit zero")

    directory = subject / XML_DIRECTORY
    files = sorted(directory.glob("*.xml"))
    if not any(path.name == XML_FILENAME for path in files):
        errors.append("Required fixture JUnit XML is missing")
    if [path.name for path in files] != [XML_FILENAME]:
        errors.append("The test-result directory must contain only the expected fixture XML")

    for path in files:
        try:
            raw = path.read_bytes()
            suite = ET.fromstring(raw)
        except (OSError, ET.ParseError) as error:
            errors.append("Cannot read JUnit XML " + str(path) + ": " + str(error))
            continue
        report["xmlFiles"].append({
            "path": str(path.relative_to(subject)),
            "sha256": hashlib.sha256(raw).hexdigest(),
            "suiteAttributes": dict(suite.attrib),
        })
        if suite.tag != "testsuite" or suite.get("name") != CLASS:
            errors.append("Unexpected JUnit suite in " + path.name)
        for attribute, expected in {
            "tests": 4, "failures": 4 if role == "baseline" else 0,
            "errors": 0, "skipped": 0,
        }.items():
            if suite.get(attribute) != str(expected):
                errors.append(path.name + ": unexpected suite " + attribute + "=" + str(suite.get(attribute)))
        if suite.findall(".//error") or suite.findall(".//skipped"):
            errors.append(path.name + ": errors or skipped tests cannot prove the contract")

        cases = suite.findall("testcase")
        if len(cases) != 4 or len(suite.findall(".//testcase")) != 4:
            errors.append(path.name + ": expected exactly four direct test cases")
        names = [case.get("name") for case in cases]
        if len(names) != len(set(names)) or set(names) != set(RULES):
            errors.append(path.name + ": missing, duplicate or unexpected test names")
        if len(suite.findall(".//failure")) != (4 if role == "baseline" else 0):
            errors.append(path.name + ": unexpected number of failure elements")

        for case in cases:
            name = case.get("name")
            failures = case.findall("failure")
            item = {
                "name": name, "classname": case.get("classname"),
                "outcome": "failed" if failures else "passed",
                "failures": [{"type": failure.get("type"), "message": failure.get("message"),
                              "stackTrace": failure.text or ""} for failure in failures],
                "expectedBug": RULES.get(name, {}).get("bug"),
                "expectedAssertionMatched": False if role == "baseline" else None,
            }
            report["xmlCases"].append(item)
            if case.get("classname") != CLASS:
                errors.append("Unexpected test class for " + str(name))
            if case.findall("error") or case.findall("skipped"):
                item["outcome"] = "error-or-skipped"
            if role == "fixed":
                if failures:
                    errors.append("Fixed test failed: " + str(name))
                continue
            if len(failures) != 1 or name not in RULES:
                errors.append("Baseline case needs exactly one known assertion failure: " + str(name))
                continue
            failure = failures[0]
            kind = failure.get("type", "")
            message = failure.get("message", "")
            if message.startswith(kind + ": "):
                message = message[len(kind) + 2:]
            normalized = re.sub(r"\s+", " ", message.replace("[", "").replace("]", "")).strip()
            rule = RULES[name]
            expected_message = rule["prefix"] + " " + rule["suffix"]
            stack = failure.text or ""
            fixture_frame = "at " + CLASS + "." + name + "(QuotaScreenshotRegressionTest.kt:"
            matched = (kind in ASSERTION_TYPES and normalized == expected_message
                       and "at org.junit.Assert." in stack and fixture_frame in stack)
            item["expectedAssertionMatched"] = matched
            if not matched:
                errors.append("Baseline assertion is not the expected quota bug: " + str(name))

    if len(report["xmlCases"]) != 4:
        errors.append("The replay must provide exactly four cases in total")
    report["verificationResult"] = "failed" if errors else "passed"
    report["expectedOutcome"] = "four identified assertion failures" if role == "baseline" else "four passing tests"
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("subject_dir", type=Path)
    parser.add_argument("role", choices=("baseline", "fixed"))
    parser.add_argument("expected_sha")
    parser.add_argument("--gradle-exit-code", type=int, required=True)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    subject = args.subject_dir.resolve()
    report_path = args.report or (subject / "quota-replay-report.json")
    report = verify(subject, args.role, args.expected_sha, args.gradle_exit_code)
    try:
        report_path.parent.mkdir(parents=True, exist_ok=True)
        report_path.write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    except OSError as error:
        print("Cannot write replay report: " + str(error), file=sys.stderr)
        return 1
    print(json.dumps({"role": args.role, "result": report["verificationResult"],
                      "report": str(report_path), "validationErrors": report["validationErrors"]}))
    return 0 if report["verificationResult"] == "passed" else 1


if __name__ == "__main__":
    sys.exit(main())
