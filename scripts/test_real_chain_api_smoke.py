#!/usr/bin/env python3
"""Tests for the read-only real-chain API smoke runner."""

from __future__ import annotations

import argparse
import contextlib
import io
import json
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import real_chain_api_smoke


class RealChainApiSmokeTest(unittest.TestCase):
    def test_endpoint_timeout_records_failure_and_continues_remaining_checks(self) -> None:
        with tempfile.TemporaryDirectory() as temporary_directory:
            output_dir = Path(temporary_directory)
            args = argparse.Namespace(
                base_url="http://localhost:18181",
                username="admin",
                password="password",
                output_dir=str(output_dir),
            )
            auth_current_calls = 0

            def request_json(
                opener: object,
                base_url: str,
                path: str,
                method: str = "GET",
                body: object | None = None,
                headers: dict[str, str] | None = None,
            ) -> tuple[int, object, str]:
                nonlocal auth_current_calls
                del opener, base_url, method, body, headers
                if path == real_chain_api_smoke.READ_ONLY_ENDPOINTS[0][1]:
                    auth_current_calls += 1
                    if auth_current_calls == 2:
                        raise TimeoutError("timed out")
                return 200, {"success": True, "data": {}}, '{"success":true,"data":{}}'

            with (
                patch.object(real_chain_api_smoke, "parse_args", return_value=args),
                patch.object(real_chain_api_smoke, "request_json", side_effect=request_json),
                contextlib.redirect_stdout(io.StringIO()),
                contextlib.redirect_stderr(io.StringIO()),
            ):
                exit_code = real_chain_api_smoke.main()

            report = json.loads((output_dir / "report.json").read_text(encoding="utf-8"))
            self.assertEqual(1, exit_code)
            self.assertEqual(len(real_chain_api_smoke.READ_ONLY_ENDPOINTS), report["endpointCount"])
            self.assertEqual(len(real_chain_api_smoke.READ_ONLY_ENDPOINTS), len(report["results"]))
            self.assertEqual(1, report["failedCount"])
            self.assertEqual("auth-current", report["results"][0]["name"])
            self.assertEqual("timed out", report["results"][0]["error"])
            self.assertTrue(all(result["ok"] for result in report["results"][1:]))


if __name__ == "__main__":
    unittest.main()
