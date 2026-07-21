import importlib.util
import sys
import unittest
from pathlib import Path


SCRIPT_PATH = Path(__file__).with_name("package_intranet_offline.py")
SPEC = importlib.util.spec_from_file_location("package_intranet_offline", SCRIPT_PATH)
MODULE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
sys.modules[SPEC.name] = MODULE
SPEC.loader.exec_module(MODULE)


class IntranetLdapPackagingTest(unittest.TestCase):
    def build_context(self):
        return MODULE.BuildContext(
            mode="fresh-empty",
            release_label="test",
            deploy_root=Path("deploy"),
            package_name="package",
            package_dir=Path("deploy/package"),
            archive_path=Path("deploy/package.tar.gz"),
            date_stamp="20260721",
            branch="main",
            commit="commit",
            short_sha="abcdef12",
            dirty_state="clean",
            backend_tag="test",
            frontend_tag="test",
            baseline_name="",
            template_dir=None,
            require_fact_rebuild=False,
            fact_rebuild_scope="all",
            frontend_port=18181,
            backend_port=18080,
            ldap_base_url="http://172.22.10.115:8081",
        )

    def test_env_declares_ldap_as_the_only_login_provider(self):
        content = MODULE.env_content(self.build_context())

        self.assertIn("PLATFORM_AUTH_PROVIDER=ldap", content)
        self.assertIn("PLATFORM_LDAP_BASE_URL=http://172.22.10.115:8081", content)
        self.assertNotIn("PLATFORM_ADMIN_USERNAME", content)
        self.assertNotIn("PLATFORM_ADMIN_PASSWORD", content)

    def test_compose_injects_ldap_and_keeps_session_csrf_enabled(self):
        content = MODULE.compose_content(self.build_context())

        self.assertIn("PLATFORM_AUTH_PROVIDER: ${PLATFORM_AUTH_PROVIDER}", content)
        self.assertIn("PLATFORM_LDAP_BASE_URL: ${PLATFORM_LDAP_BASE_URL}", content)
        self.assertIn('PLATFORM_AUTH_CSRF_ENABLED: "true"', content)
        self.assertNotIn("PLATFORM_ADMIN_USERNAME", content)
        self.assertNotIn("PLATFORM_ADMIN_PASSWORD", content)


if __name__ == "__main__":
    unittest.main()
