import json
import pathlib
import subprocess
import sys
import tempfile
import unittest

VERIFY = pathlib.Path(__file__).with_name("verify_probe.py").resolve()


class VerifyProbeTest(unittest.TestCase):
    def verify(self, text="Invoice 4729", **overrides):
        with tempfile.TemporaryDirectory() as directory:
            root = pathlib.Path(directory)
            (root / "fixtures").mkdir()
            (root / "results").mkdir()
            (root / "fixtures/expected.json").write_text(json.dumps({"receipt": ["Invoice 4729"]}))
            (root / "results/receipt.txt").write_text(text)
            metrics = {"truncated": False, "generated_tokens": 5,
                       "peak_rss_kib": 800000, "elapsed_seconds": 10}
            metrics.update(overrides)
            (root / "results/receipt.json").write_text(json.dumps(metrics))
            process = subprocess.run([sys.executable, str(VERIFY), "results"],
                                     cwd=root, capture_output=True, text=True)
            return process.returncode, (root / "results/verified.json").exists()

    def test_valid_generation(self):
        self.assertEqual(self.verify(text="**INVOICE   4729**"), (0, True))

    def test_wrong_text_is_not_a_pass(self):
        self.assertNotEqual(self.verify(text="Invoice 8264")[0], 0)

    def test_truncation_is_not_a_pass(self):
        self.assertNotEqual(self.verify(truncated=True)[0], 0)

    def test_zero_tokens_is_not_a_pass(self):
        self.assertNotEqual(self.verify(generated_tokens=0)[0], 0)

    def test_memory_threshold(self):
        self.assertEqual(self.verify(peak_rss_kib=int(3.5 * 1024 * 1024) - 1), (0, True))
        self.assertNotEqual(self.verify(peak_rss_kib=int(3.5 * 1024 * 1024))[0], 0)
        self.assertNotEqual(self.verify(peak_rss_kib=0)[0], 0)


if __name__ == "__main__":
    unittest.main()
