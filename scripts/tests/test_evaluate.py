import copy
import json
from pathlib import Path
import sys
import unittest
from unittest.mock import Mock

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from evaluate import ApiFailure, Evaluator, check_business, fraction, summarize, validate_manifest


class EvaluationTest(unittest.TestCase):
    def test_manifest_has_fifty_cases_and_no_fact_group_leakage(self):
        manifest = json.loads((Path(__file__).resolve().parents[2] / "eval/cases.json").read_text())
        cases = validate_manifest(manifest)
        self.assertEqual(len(cases), 50)
        self.assertEqual(len({c["group"] for c in cases}), 25)
        broken = copy.deepcopy(manifest)
        broken["cases"][1]["split"] = "holdout"
        with self.assertRaisesRegex(ValueError, "leaks"):
            validate_manifest(broken)

    def test_missing_execution_denominator_is_not_reported_as_zero_failure_rate(self):
        self.assertEqual(fraction(0, 0), {"numerator": 0, "denominator": 0, "rate": None})
        evaluator = Evaluator("http://unused", "v2.3", "tools", False)
        summary = summarize([{"status": "PASS", "kind": "FIX", "durationMs": 1},
                             {"status": "SETUP_MISSING", "kind": "FIX", "durationMs": 1}], evaluator)
        self.assertEqual(summary["casePass"]["rate"], .5)
        self.assertIsNone(summary["agentToolExecutionFailure"]["rate"])

    def test_wrong_services_or_versions_cannot_pass(self):
        case = {"kind": "DEPENDENCY", "expected": {"services": ["api"], "coordinate": "lib:core", "before": "1", "after": "2", "changeCount": 1}}
        result = {"affectedServices": ["wrong"], "changes": [], "impacts": []}
        with self.assertRaisesRegex(AssertionError, "services"):
            check_business(case, result)
        result.update(affectedServices=["api"], changes=[{"coordinate": "lib:core", "before": {"version": "1"}, "after": {"version": "3"}}])
        with self.assertRaisesRegex(AssertionError, "versions"):
            check_business(case, result)

    def test_context_lines_and_confirmed_fix_claims_do_not_satisfy_a_patch_label(self):
        case = {"kind": "FIX", "expected": {"fixSha": "a" * 40, "signature": "ALERT", "applicability": "DEPLOYED_VERSION_UNKNOWN"}}
        candidate = {"commitSha": "a" * 40, "classification": "RELATED_CANDIDATE", "applicability": "DEPLOYED_VERSION_UNKNOWN",
                     "evidence": [{"type": "DIFF", "parentSha": "b" * 40, "excerpt": " ALERT\n-unrelated\n+unrelated"}]}
        with self.assertRaisesRegex(AssertionError, "changed lines"):
            check_business(case, {"candidates": [candidate]})
        candidate["evidence"][0]["excerpt"] = "-ALERT\n+retry"
        check_business(case, {"candidates": [candidate]})
        candidate["classification"] = "CONFIRMED_FIX"
        with self.assertRaisesRegex(AssertionError, "falsely confirmed"):
            check_business(case, {"candidates": [candidate]})

    def test_backend_failure_is_not_counted_as_a_successful_no_evidence_refusal(self):
        evaluator = Evaluator("http://unused", "v2.3", "tools", False)
        evaluator.repos = {"fixture/repo": {"id": 1}}
        evaluator.snapshot = Mock(return_value=2)
        evaluator.call = Mock(side_effect=ApiFailure("HTTP 503: GITHUB_RATE_LIMITED"))
        result = evaluator.run_case({"id": "negative", "kind": "NO_EVIDENCE", "split": "holdout", "group": "absent",
                                     "repository": "fixture/repo", "headSha": "a" * 40, "question": "ABSENT 告警", "expected": {"reason": "NO_EVIDENCE"}})
        self.assertEqual(result["status"], "ERROR")


if __name__ == "__main__":
    unittest.main()
