"""Offline payload accounting uses exported production output verbatim."""
import unittest

from benchmarks.compact_report import summarize


class CompactReportTest(unittest.TestCase):
    def test_measures_utf8_and_tokens_without_reserializing_payloads(self):
        records = [{"id": "fixture", "full": '{"text":"é"}', "compact": '{}'}]
        result = summarize(records, lambda text: len(text), "test-character-counter")
        self.assertEqual(1, len(result["samples"]))
        self.assertEqual(13, result["samples"][0]["fullBytes"])
        self.assertEqual(12, result["samples"][0]["fullTokens"])
        self.assertEqual(2, result["samples"][0]["compactBytes"])
        self.assertEqual("test-character-counter", result["tokenizer"])

    def test_unavailable_tokenizer_reports_null_without_estimates(self):
        result = summarize([{"id": "failure", "full": '{}', "compact": '{}'}])
        self.assertEqual(1, len(result["samples"]))
        self.assertIsNone(result["tokenizer"])
        self.assertIsNone(result["samples"][0]["compactTokens"])
        self.assertEqual(0, result["samples"][0]["byteReductionPercent"])
