import contextlib
import io
import os
from pathlib import Path
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import dev


class LocalEnvironmentTest(unittest.TestCase):
    def test_env_is_data_and_explicit_process_settings_win(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(dev, "ROOT", Path(folder)), patch.dict(os.environ, {"DB_USERNAME": "process-user"}, clear=True):
            Path(folder, ".env").write_text("DB_USERNAME=file-user\nDB_PASSWORD='$(touch must-not-run)'\nLLM_API_KEY=example-fake-key\n")
            values = dev.load_environment()
            self.assertEqual(values["DB_USERNAME"], "process-user")
            self.assertEqual(values["DB_PASSWORD"], "$(touch must-not-run)")
            self.assertFalse(Path(folder, "must-not-run").exists())

    def test_malformed_env_error_reports_line_number_without_value(self):
        with tempfile.TemporaryDirectory() as folder, patch.object(dev, "ROOT", Path(folder)):
            Path(folder, ".env").write_text("# comment\nINVALID SECRET EXAMPLE\n")
            with self.assertRaises(SystemExit) as raised:
                dev.load_environment()
            self.assertIn("line 2", str(raised.exception))
            self.assertNotIn("SECRET EXAMPLE", str(raised.exception))


if __name__ == "__main__":
    unittest.main()
