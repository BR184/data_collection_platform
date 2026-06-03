import argparse
import unittest

from scripts.compare_old_platform_integration_data_to_new_facts import (
    build_report,
    compare,
    old_legal,
)


class CompareOldPlatformIntegrationDataToNewFactsTest(unittest.TestCase):
    def test_compares_by_business_identity_and_reports_missing_extra_and_changed_rows(self):
        old_rows = [
            base_row(issueIid=1, moduleName="A", functionName="f1", executeCase=10, passCase=8),
            base_row(issueIid=2, moduleName="A", functionName="f2", title="old"),
        ]
        new_rows = [
            base_row(issueIid=2, moduleName="A", functionName="f2", title="new"),
            base_row(issueIid=3, moduleName="B", functionName="f3"),
        ]

        report = compare(old_rows, new_rows)

        self.assertEqual(report["missingInNew"][0]["issueIid"], "1")
        self.assertEqual(report["extraInNew"][0]["issueIid"], "3")
        self.assertEqual(report["changed"][0]["issueIid"], "2")
        self.assertEqual(report["changed"][0]["diffs"][0]["field"], "title")

    def test_marks_fact_rows_hidden_by_page_module_filter(self):
        args = argparse.Namespace(
            mysql_container="mysql",
            mysql_db="old",
            platform_container="pg",
            platform_db="new",
            source_instance="cc",
            testing_phase="R1",
        )
        old_rows = [base_row(issueIid=1, moduleName="", functionName="f1")]
        new_all = [base_row(issueIid=1, moduleName="", functionName="f1")]
        new_visible = []

        report = build_report(args, old_rows, new_all, new_visible)

        self.assertTrue(report["factComparison"]["passed"])
        self.assertFalse(report["pageVisibleComparison"]["passed"])
        self.assertEqual(report["counts"]["newHiddenAfterSync"], 1)
        self.assertEqual(report["hiddenAfterSync"][0]["issueIid"], "1")

    def test_old_legal_matches_old_platform_rule(self):
        self.assertTrue(old_legal(base_row(executeCase=10, passCase=8, notPassCaseNow=2)))
        self.assertFalse(old_legal(base_row(executeCase=10, passCase=8, notPassCaseNow=1)))
        self.assertFalse(old_legal(base_row(executeCase=-1, passCase=0, notPassCaseNow=0)))


def base_row(**overrides):
    row = {
        "sourceInstance": "cc",
        "testingPhase": "R1",
        "issueIid": 1,
        "moduleName": "A",
        "functionName": "f1",
        "title": "same",
        "executor": "owner",
        "executeCase": 10,
        "passCase": 8,
        "notPassCase": 2,
        "notPassCaseNow": 2,
        "problemCase": 0,
        "exceptionCount": 0,
        "passRate": 80,
        "legal": True,
    }
    row.update(overrides)
    return row


if __name__ == "__main__":
    unittest.main()
