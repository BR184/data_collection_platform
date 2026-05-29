from __future__ import annotations

import unittest

import check_flyway_destructive_migrations as script


class CheckFlywayDestructiveMigrationsTest(unittest.TestCase):
    def test_should_detect_destructive_drop_table_sql(self) -> None:
        text = "drop table if exists gitlab_sync_jobs cascade;"

        self.assertTrue(script.contains_destructive_sql(text))

    def test_should_detect_destructive_rename_column_sql(self) -> None:
        text = "alter table gitlab_sync_configs rename column webhook_secret to system_hook_secret;"

        self.assertTrue(script.contains_destructive_sql(text))

    def test_should_require_review_markers_for_destructive_migration(self) -> None:
        text = "-- note only\nalter table demo drop column if exists legacy_field;"

        self.assertEqual(
            script.missing_markers(text),
            [
                "-- destructive-migration-reviewed:",
                "-- destructive-migration-recovery:",
            ],
        )

    def test_should_accept_destructive_migration_with_review_markers(self) -> None:
        text = """
        -- destructive-migration-reviewed: approved by qa on 2026-05-29
        -- destructive-migration-recovery: rename to *_legacy first and keep one release
        drop table if exists gitlab_sync_logs cascade;
        """

        self.assertEqual(script.missing_markers(text), [])


if __name__ == "__main__":
    unittest.main()
