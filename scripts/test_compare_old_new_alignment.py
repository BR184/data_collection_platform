import tempfile
import unittest
from pathlib import Path

from scripts.compare_old_new_alignment import compare_csv_records, write_diff_report


class CompareOldNewAlignmentTest(unittest.TestCase):
    def test_compares_by_identity_key_and_reports_missing_and_changed_rows(self):
        old_rows = [
            {"source_instance": "cc", "project_id": "325", "issue_iid": "1", "title": "old only"},
            {"source_instance": "cc", "project_id": "325", "issue_iid": "2", "title": "same key old"},
        ]
        new_rows = [
            {"source_instance": "cc", "project_id": "325", "issue_iid": "2", "title": "same key new"},
            {"source_instance": "dgm", "project_id": "325", "issue_iid": "1", "title": "new only"},
        ]

        diff = compare_csv_records(
            old_rows,
            new_rows,
            key_fields=["source_instance", "project_id", "issue_iid"],
            compare_fields=["title"],
        )

        self.assertEqual(len(diff.old_only), 1)
        self.assertEqual(diff.old_only[0]["issue_iid"], "1")
        self.assertEqual(len(diff.new_only), 1)
        self.assertEqual(diff.new_only[0]["source_instance"], "dgm")
        self.assertEqual(len(diff.changed), 1)
        self.assertEqual(diff.changed[0]["field"], "title")

    def test_writes_three_diff_files(self):
        diff = compare_csv_records(
            [{"source_instance": "cc", "project_id": "325", "issue_iid": "1", "title": "old"}],
            [{"source_instance": "cc", "project_id": "325", "issue_iid": "1", "title": "new"}],
            key_fields=["source_instance", "project_id", "issue_iid"],
            compare_fields=["title"],
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            write_diff_report(diff, Path(temp_dir), "issues")
            self.assertTrue((Path(temp_dir) / "issues_old_only.csv").exists())
            self.assertTrue((Path(temp_dir) / "issues_new_only.csv").exists())
            self.assertTrue((Path(temp_dir) / "issues_changed.csv").exists())


if __name__ == "__main__":
    unittest.main()
