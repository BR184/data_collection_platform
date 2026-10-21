"""基准报告必须明确记录失败/混版，不能把失败延迟当成读取性能。"""
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

import benchmark_published_reads as benchmark


class PublishedReadBenchmarkTest(unittest.TestCase):
    def test_report_records_mixed_version_and_excludes_cookie_and_business_payload(self):
        metadata = {"readContextId": "opaque", "sourceVersion": "complete", "businessDate": "2026-10-14"}
        def execute(_base, path, _cookie, **_kwargs):
            if path == "/api/read-views":
                return 201, json.dumps({"success": True, "data": metadata}).encode(), {}, 2
            if path.endswith("opaque"):
                return 204, b"", {}, 1
            return 200, json.dumps({"success": True, "data": {"private": "business-secret"},
                "readView": {**metadata, "sourceVersion": "unrelated"}}).encode(), {"Content-Type": "application/json"}, 1
        workspace = Path(__file__).resolve().parents[4]
        temporary = workspace / ".tmp"
        temporary.mkdir(exist_ok=True)
        with tempfile.TemporaryDirectory(dir=temporary) as directory:
            output = Path(directory) / "report.json"
            arguments = ["benchmark", "--base-url", "http://127.0.0.1:18080", "--page", "page",
                "--operation", "main=/api/main", "--samples", "1", "--warmup", "0", "--scenario", "failed", "--output", str(output)]
            with patch("sys.argv", arguments), patch.dict("os.environ", {"READ_BENCH_COOKIE": "cookie-secret"}), patch.object(benchmark, "execute", execute):
                with self.assertRaises(SystemExit):
                    benchmark.main()
            text = output.read_text(encoding="utf-8")
            report = json.loads(text)
            self.assertEqual(report["operations"]["main"]["successful_samples"], 0)
            self.assertEqual(len(report["failures"]), 1)
            self.assertNotIn("cookie-secret", text)
            self.assertNotIn("business-secret", text)

    def test_percentile_uses_nearest_rank_and_empty_samples_have_no_latency(self):
        self.assertEqual(benchmark.summarize([]), {})
        self.assertEqual(benchmark.summarize(list(range(1, 51)))["p95_ms"], 48)


if __name__ == "__main__":
    unittest.main()
