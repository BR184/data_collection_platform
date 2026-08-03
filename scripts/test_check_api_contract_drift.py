#!/usr/bin/env python3
"""Tests for frontend/backend API contract path matching."""

from __future__ import annotations

import unittest

import check_api_contract_drift


class ApiContractDriftTest(unittest.TestCase):
    def test_query_suffix_template_is_not_treated_as_path_segment(self) -> None:
        self.assertEqual(
            "/api/database-browser/tables",
            check_api_contract_drift.normalize_frontend_path(
                "/api/database-browser/tables${suffix}"
            ),
        )
        self.assertEqual(
            "/api/quality-board/rd/overview",
            check_api_contract_drift.normalize_frontend_path(
                "/api/quality-board/rd/overview${queryString ? "
            ),
        )

    def test_path_parameter_is_preserved_as_wildcard_before_query_suffix(self) -> None:
        self.assertEqual(
            "/api/issue-scopes/catalogs/{}/groups",
            check_api_contract_drift.normalize_frontend_path(
                "/api/issue-scopes/catalogs/${catalogId}/groups${queryString(params)}"
            ),
        )

    def test_backend_path_parameter_accepts_frontend_concrete_value(self) -> None:
        self.assertTrue(
            check_api_contract_drift.backend_path_matches(
                "/api/statistic-boards/{}/issues/export",
                "/api/statistic-boards/system-test-defect-summary/issues/export",
            )
        )

    def test_frontend_wildcard_does_not_match_backend_concrete_segment(self) -> None:
        self.assertFalse(
            check_api_contract_drift.backend_path_matches(
                "/api/label-groups/fixed/expand",
                "/api/label-groups/{}/expand",
            )
        )


if __name__ == "__main__":
    unittest.main()
