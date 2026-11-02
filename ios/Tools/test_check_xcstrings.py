import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest

spec = importlib.util.spec_from_file_location("check_xcstrings", Path(__file__).with_name("check-xcstrings.py"))
check = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check)


def unit(value, state="translated"):
    return {"stringUnit": {"state": state, "value": value}}


def plural(one, other):
    return {"variations": {"plural": {"one": unit(one), "other": unit(other)}}}


def catalog(strings, source="en"):
    return {"sourceLanguage": source, "strings": strings, "version": "1.0"}


GOOD = catalog({
    "Settings": {"localizations": {"de": unit("Einstellungen")}},
    "Reset in %@": {"localizations": {"de": unit("Reset in %@")}},
    "%@%%": {"localizations": {"de": unit("%@ %%")}},
    "%@ renews %@": {"localizations": {"de": unit("%2$@ kommt nach %1$@")}},
    "%lld accounts": {"localizations": {
        "en": plural("%lld account", "%lld accounts"),
        "de": plural("%lld Konto", "%lld Konten"),
    }},
})

SWIFT = '''
import SwiftUI
struct V: View {
    var body: some View {
        // Text("Not a key, it is a comment")
        Text("Settings").navigationTitle("Settings")
        Text("Reset in \\(countdown)")
        Text(verbatim: "Data, not looked up")
        let figure = String(localized: "\\(token)%")
        let pair = String(localized: "\\(a) renews \\(b)")
        Text("\\(accounts.count) accounts")
        let url = "https://example.com/not-a-comment"
    }
}
'''


class CheckXcstringsTests(unittest.TestCase):
    def run_check(self, strings_catalog, swift=SWIFT, extra=None, exclude_widget=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Widget").mkdir()
            (root / "Screen.swift").write_text(swift)
            if exclude_widget:
                (root / "Widget" / "W.swift").write_text('Text("Only the widget uses this")')
            (root / "Catalog.xcstrings").write_text(json.dumps(strings_catalog))
            argv = ["--catalog", str(root / "Catalog.xcstrings"), str(root)]
            if exclude_widget:
                argv += ["--exclude", str(root / "Widget")]
            argv += extra or []
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                status = check.main(argv)
            return status, out.getvalue()

    def test_a_complete_catalog_passes(self):
        status, out = self.run_check(GOOD)
        self.assertEqual(status, 0, out)
        self.assertIn("0 error(s)", out)

    def test_an_interpolation_matches_a_placeholder_of_either_kind(self):
        # \(accounts.count) is an Int, so the catalog says %lld; \(countdown) is a String, %@.
        status, out = self.run_check(GOOD)
        self.assertEqual(status, 0, out)

    def test_a_literal_percent_is_doubled_in_the_key(self):
        broken = json.loads(json.dumps(GOOD))
        broken["strings"]["%@%"] = broken["strings"].pop("%@%%")
        status, out = self.run_check(broken)
        self.assertEqual(status, 1)
        self.assertIn("missing key", out)

    def test_a_literal_the_catalog_lacks_is_reported(self):
        status, out = self.run_check(GOOD, swift=SWIFT + '\nText("Brand new sentence")')
        self.assertEqual(status, 1)
        self.assertIn("missing key 'Brand new sentence'", out)

    def test_a_key_nothing_uses_is_reported(self):
        stale = json.loads(json.dumps(GOOD))
        stale["strings"]["Stale"] = {"localizations": {"de": unit("Veraltet")}}
        status, out = self.run_check(stale)
        self.assertEqual(status, 1)
        self.assertIn("unused key 'Stale'", out)

    def test_unused_keys_can_be_downgraded_to_warnings(self):
        stale = json.loads(json.dumps(GOOD))
        stale["strings"]["Stale"] = {"localizations": {"de": unit("Veraltet")}}
        status, out = self.run_check(stale, extra=["--warn-unused"])
        self.assertEqual(status, 0, out)
        self.assertIn("WARN", out)

    def test_a_missing_german_value_is_reported(self):
        broken = json.loads(json.dumps(GOOD))
        del broken["strings"]["Settings"]["localizations"]["de"]
        status, out = self.run_check(broken)
        self.assertEqual(status, 1)
        self.assertIn("no 'de' localization", out)

    def test_an_untranslated_state_is_reported(self):
        broken = json.loads(json.dumps(GOOD))
        broken["strings"]["Settings"]["localizations"]["de"] = unit("Einstellungen", "new")
        status, out = self.run_check(broken)
        self.assertEqual(status, 1)
        self.assertIn("expected 'translated'", out)

    def test_a_plural_needs_one_and_other_in_german(self):
        broken = json.loads(json.dumps(GOOD))
        del broken["strings"]["%lld accounts"]["localizations"]["de"]["variations"]["plural"]["one"]
        status, out = self.run_check(broken)
        self.assertEqual(status, 1)
        self.assertIn("no 'one'", out)

    def test_a_placeholder_mismatch_is_reported(self):
        broken = json.loads(json.dumps(GOOD))
        broken["strings"]["Reset in %@"]["localizations"]["de"] = unit("Reset in")
        status, out = self.run_check(broken)
        self.assertEqual(status, 1)
        self.assertIn("[c]", out)

    def test_a_wrong_placeholder_kind_is_reported(self):
        broken = json.loads(json.dumps(GOOD))
        broken["strings"]["%lld accounts"]["localizations"]["de"] = plural("%@ Konto", "%@ Konten")
        status, out = self.run_check(broken)
        self.assertEqual(status, 1)
        self.assertIn("[c]", out)

    def test_positional_placeholders_may_reorder(self):
        status, out = self.run_check(GOOD)
        self.assertEqual(status, 0, out)

    def test_a_wrong_source_language_is_reported(self):
        status, out = self.run_check(catalog(GOOD["strings"], source="de"))
        self.assertEqual(status, 1)
        self.assertIn("[a]", out)

    def test_a_catalog_that_is_not_json_is_reported(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Catalog.xcstrings").write_text("{ not json")
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                status = check.main(["--catalog", str(root / "Catalog.xcstrings"), str(root)])
            self.assertEqual(status, 1)
            self.assertIn("[a]", out.getvalue())

    def test_a_concatenated_literal_is_reported(self):
        status, out = self.run_check(GOOD, swift=SWIFT + '\nText("Part one " + "part two")')
        self.assertEqual(status, 1)
        self.assertIn("concatenated", out)

    def test_excluded_paths_are_not_scanned(self):
        status, out = self.run_check(GOOD, exclude_widget=True)
        self.assertEqual(status, 0, out)

    def test_the_widget_literal_is_missing_when_it_is_scanned(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "Screen.swift").write_text(SWIFT)
            (root / "W.swift").write_text('Text("Only the widget uses this")')
            (root / "Catalog.xcstrings").write_text(json.dumps(GOOD))
            out = io.StringIO()
            with contextlib.redirect_stdout(out):
                status = check.main(["--catalog", str(root / "Catalog.xcstrings"), str(root)])
            self.assertEqual(status, 1)
            self.assertIn("Only the widget uses this", out.getvalue())

    def test_extra_call_names_are_checked(self):
        status, out = self.run_check(GOOD, swift=SWIFT + '\nMyRow("Extra")', extra=["--call", "MyRow"])
        self.assertEqual(status, 1)
        self.assertIn("missing key 'Extra'", out)

    def test_an_escaped_quote_and_nested_interpolation_are_read(self):
        parts, end = check.parse_literal('"say \\"hi\\" \\(f("x")) now"', 0)
        self.assertEqual(check.key_of(parts), 'say "hi" %@ now')

    def test_help_exits_cleanly(self):
        with contextlib.redirect_stdout(io.StringIO()):
            with self.assertRaises(SystemExit) as raised:
                check.main(["--help"])
        self.assertEqual(raised.exception.code, 0)


if __name__ == "__main__":
    unittest.main()
