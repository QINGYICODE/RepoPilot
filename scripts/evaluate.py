#!/usr/bin/env python3
"""Fixed repository regression evaluation. Tool mode makes no model calls; agent mode calls the configured model."""
import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import math
from pathlib import Path
import subprocess
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


class SetupMissing(Exception):
    pass


class ApiFailure(Exception):
    pass


def validate_manifest(manifest):
    cases = manifest["cases"]
    if len({c["id"] for c in cases}) != len(cases):
        raise ValueError("Duplicate case IDs")
    groups = {}
    for case in cases:
        if case["split"] not in {"tune", "holdout"}:
            raise ValueError("Unknown split")
        if groups.setdefault(case["group"], case["split"]) != case["split"]:
            raise ValueError("Fact group leaks across splits")
        for key in ["headSha", "baseSha", "deployedSha"]:
            value = case.get(key)
            if value and (len(value) != 40 or any(c not in "0123456789abcdef" for c in value)):
                raise ValueError("Expected an immutable full SHA")
    return cases


def fraction(numerator, denominator):
    return {"numerator": numerator, "denominator": denominator, "rate": numerator / denominator if denominator else None}


def require(condition, message):
    # Evaluation gates must remain active when Python optimization disables assert statements.
    if not condition:
        raise AssertionError(message)


def check_business(case, result):
    expected = case["expected"]
    if case["kind"] == "DEPENDENCY":
        require(sorted(result["affectedServices"]) == sorted(expected["services"]), "Affected services differ")
        require(len(result["changes"]) == expected["changeCount"], "Change count differs")
        matching = [c for c in result["changes"] if c["coordinate"] == expected["coordinate"]]
        require(matching, "Expected coordinate not found")
        require(any((c.get("before") or {}).get("version") == expected["before"] and
                   (c.get("after") or {}).get("version") == expected["after"] for c in matching), "Expected versions not found")
        require(result["impacts"] and all(i["path"] and i["evidenceIds"] for i in result["impacts"]), "Missing path evidence")
    elif case["kind"] == "FIX":
        matching = [c for c in result["candidates"] if c["commitSha"] == expected["fixSha"]]
        require(matching, "Expected historical commit not retrieved")
        candidate = matching[0]
        require(candidate["classification"] == "RELATED_CANDIDATE", "Candidate falsely confirmed")
        require(candidate["applicability"] == expected["applicability"], "Deployment ancestry differs")
        hunks = [e for e in candidate["evidence"] if e["type"] == "DIFF" and e["parentSha"]]
        require(any(expected["signature"].lower() in "\n".join(line for line in h["excerpt"].splitlines()
                   if line.startswith(("+", "-")) and not line.startswith(("+++", "---"))).lower() for h in hunks), "Signature not present in changed lines")


class Evaluator:
    def __init__(self, api, index_version, mode, prepare):
        self.api, self.index, self.mode, self.prepare = api.rstrip("/"), index_version, mode, prepare
        self.opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.repos, self.snapshots, self.evidence = {}, {}, {}
        self.evidence_attempts = self.evidence_valid = 0

    def call(self, path, body=None):
        request = urllib.request.Request(self.api + path, data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), headers={"Content-Type": "application/json"})
        try:
            with self.opener.open(request, timeout=20) as response:
                return json.load(response)
        except urllib.error.HTTPError as error:
            try:
                code = json.loads(error.read(4096)).get("code", "HTTP_ERROR")
            except (ValueError, AttributeError):
                code = "HTTP_ERROR"
            raise ApiFailure(f"HTTP {error.code}: {code}") from None
        except (OSError, ValueError):
            raise ApiFailure("API_UNAVAILABLE_OR_INVALID_JSON") from None

    def initialize(self):
        self.repos = {f"{r['owner']}/{r['name']}".lower(): r for r in self.call("/repositories")}

    def snapshot(self, repository, sha):
        if repository not in self.repos:
            raise SetupMissing("Repository must be explicitly authorized first")
        repo = self.repos[repository]
        if repository not in self.snapshots:
            self.snapshots[repository] = self.call(f"/repositories/{repo['id']}/snapshots")
        found = [s for s in self.snapshots[repository] if s["commitSha"] == sha and s["indexVersion"] == self.index]
        if found:
            return max(found, key=lambda s: s["id"])["id"]
        if not self.prepare:
            raise SetupMissing(f"Missing {self.index} snapshot {sha}; import it or use --prepare")
        print(f"Preparing {repository} {sha[:7]}", flush=True)
        job = self.call(f"/repositories/{repo['id']}/sync-jobs", {"commitSha": sha})
        deadline = time.monotonic() + 180
        while job["status"] not in {"READY", "FAILED"} and time.monotonic() < deadline:
            time.sleep(1)
            job = self.call("/sync-jobs/" + job["id"])
        if job["status"] != "READY":
            raise SetupMissing("Snapshot import failed: " + (job.get("errorCode") or "IMPORT_TIMEOUT"))
        self.snapshots[repository] = self.call(f"/repositories/{repo['id']}/snapshots")
        return job["snapshotId"]

    def verify_evidence(self, repository, snapshot, evidence_id):
        repo = self.repos[repository]
        key = (repo["id"], snapshot, evidence_id)
        if key in self.evidence:
            return self.evidence[key]
        self.evidence_attempts += 1
        evidence = self.call(f"/repositories/{repo['id']}/snapshots/{snapshot}/evidence/{evidence_id}")
        require(evidence["id"] == evidence_id and evidence["repoId"] == repo["id"], "Evidence scope differs")
        require(hashlib.sha256(evidence["excerpt"].encode()).hexdigest() == evidence["contentHash"], "Evidence content hash differs")
        require(evidence["sourceUrl"].lower().startswith(f"https://github.com/{repository}/"), "Evidence URL leaves repository")
        require(evidence["sourceSha"] in evidence["sourceUrl"], "Evidence URL is not pinned to its SHA")
        self.evidence[key] = evidence
        self.evidence_valid += 1
        return evidence

    def run_case(self, case):
        started = time.monotonic()
        row = {"id": case["id"], "kind": case["kind"], "split": case["split"], "group": case["group"], "status": "ERROR"}
        try:
            repo = self.repos.get(case["repository"])
            head = self.snapshot(case["repository"], case["headSha"])
            baseline = self.snapshot(case["repository"], case["baseSha"]) if case.get("baseSha") else None
            task = "DEPENDENCY_IMPACT" if case["kind"] == "DEPENDENCY" else "INCIDENT_FIX"
            request = {"repoId": repo["id"], "snapshotId": head, "task": task, "question": case["question"],
                       "baseSnapshotId": baseline, "module": case.get("module"), "deployedSha": case.get("deployedSha")}
            agent = None
            if self.mode == "agent" or case["kind"] in {"OUT_OF_SCOPE", "FORBIDDEN"}:
                agent = self.call("/analysis-runs", request)
                deadline = time.monotonic() + 105
                while agent["status"] == "RUNNING" and time.monotonic() < deadline:
                    time.sleep(1)
                    agent = self.call("/analysis-runs/" + agent["id"])
                row.update(runId=agent["id"], usage=agent["usage"], model=agent["model"], promptVersion=agent["promptVersion"], reasonCode=agent["reasonCode"])
                if case["kind"] in {"NO_EVIDENCE", "OUT_OF_SCOPE", "FORBIDDEN"}:
                    require(agent["status"] == "REFUSED" and agent["reasonCode"] == case["expected"]["reason"], "Incorrect refusal")
                    if case["kind"] != "NO_EVIDENCE":
                        require(agent["usage"]["modelCalls"] == 0, "Boundary rejection called the model")
                    result = None
                else:
                    require(agent["reasonCode"] == "EVIDENCE_VERIFIED", "Agent did not finalize verified evidence")
                    result = agent["dependency" if task == "DEPENDENCY_IMPACT" else "incident"]
            elif task == "DEPENDENCY_IMPACT":
                result = self.call(f"/repositories/{repo['id']}/dependency-impact", {"baseSnapshotId": baseline, "headSnapshotId": head, "module": case.get("module")})
            else:
                result = self.call(f"/repositories/{repo['id']}/fix-candidates", {"snapshotId": head, "message": case["question"], "deployedSha": case.get("deployedSha")})
                if case["kind"] == "NO_EVIDENCE":
                    require(result["status"] == "REFUSED" and result["reasonCode"] == "NO_EVIDENCE", "No-evidence query was accepted")
            if case["kind"] in {"DEPENDENCY", "FIX"}:
                check_business(case, result)
                if task == "DEPENDENCY_IMPACT":
                    for impact in result["impacts"]:
                        for ref in impact["evidenceIds"]:
                            self.verify_evidence(case["repository"], baseline if impact["view"] == "BASE" else head, ref)
                else:
                    for candidate in result["candidates"]:
                        for evidence in candidate["evidence"]:
                            self.verify_evidence(case["repository"], head, evidence["id"])
                row["businessEvidenceVerified"] = True
            row["status"] = "PASS"
        except SetupMissing as error:
            row.update(status="SETUP_MISSING", detail=str(error))
        except AssertionError as error:
            row.update(status="FAIL", detail=str(error))
        except (ApiFailure, KeyError, TypeError) as error:
            row.update(status="ERROR", detail=str(error)[:160])
        row["durationMs"] = round((time.monotonic() - started) * 1000)
        return row


def summarize(rows, evaluator):
    counts = Counter(r["status"] for r in rows)
    usage = [r["usage"] for r in rows if "usage" in r]
    durations = sorted(r["durationMs"] for r in rows)
    positive = [r for r in rows if r["kind"] in {"DEPENDENCY", "FIX"}]
    negative = [r for r in rows if r["kind"] not in {"DEPENDENCY", "FIX"}]
    return {"counts": dict(counts), "casePass": fraction(counts["PASS"], len(rows)),
            "businessCasePass": fraction(sum(r["status"] == "PASS" for r in positive), len(positive)),
            "refusalCasePass": fraction(sum(r["status"] == "PASS" for r in negative), len(negative)),
            "uniqueEvidenceValidity": fraction(evaluator.evidence_valid, evaluator.evidence_attempts),
            "agentToolExecutionFailure": fraction(sum(u["failedToolCalls"] for u in usage), sum(u["executedToolCalls"] for u in usage)),
            "agentToolRejection": fraction(sum(u["rejectedToolCalls"] for u in usage), sum(u["requestedToolCalls"] for u in usage)),
            "modelCalls": sum(u["modelCalls"] for u in usage), "inputTokens": sum(u["inputTokens"] for u in usage),
            "outputTokens": sum(u["outputTokens"] for u in usage), "hasEstimatedTokens": any(u["estimatedTokens"] for u in usage),
            "latencyMs": {"p50": durations[math.ceil(len(durations)*.5)-1] if durations else None, "p95": durations[math.ceil(len(durations)*.95)-1] if durations else None}}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", default="http://127.0.0.1:8080/api")
    parser.add_argument("--manifest", type=Path, default=ROOT / "eval/cases.json")
    parser.add_argument("--mode", choices=["tools", "agent"], default="tools")
    parser.add_argument("--split", choices=["all", "tune", "holdout"], default="all")
    parser.add_argument("--limit", type=int)
    parser.add_argument("--case", action="append", default=[], help="Select an explicit case ID; repeat for a representative live subset")
    parser.add_argument("--prepare", action="store_true", help="Import missing snapshots of already-authorized repositories; does not grant access")
    parser.add_argument("--output", type=Path, default=ROOT / ".local/evaluation/results.json")
    args = parser.parse_args()
    raw = args.manifest.read_bytes()
    manifest = json.loads(raw)
    all_cases = validate_manifest(manifest)
    if set(args.case) - {c["id"] for c in all_cases}:
        parser.error("Unknown --case ID")
    cases = [c for c in all_cases if (args.split == "all" or c["split"] == args.split) and (not args.case or c["id"] in args.case)]
    if args.limit is not None:
        if args.limit < 1:
            parser.error("--limit must be positive")
        cases = cases[:args.limit]
    if not cases:
        parser.error("No selected cases")
    evaluator = Evaluator(args.api, manifest["indexVersion"], args.mode, args.prepare)
    rows = []
    try:
        evaluator.initialize()
        for case in cases:
            row = evaluator.run_case(case); rows.append(row)
            print(f"{row['id']} {row['status']} {row.get('detail', '')}", flush=True)
    except (ApiFailure, KeyboardInterrupt) as error:
        print(type(error).__name__ + ": evaluation incomplete", flush=True)
    report = {"generatedAt": datetime.now(timezone.utc).isoformat(), "mode": args.mode, "split": args.split,
              "manifestHash": hashlib.sha256(raw).hexdigest(), "indexVersion": manifest["indexVersion"],
              "annotation": manifest["annotation"], "selectedCases": len(cases), "completedCases": len(rows),
              "commit": subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=ROOT, text=True).strip(),
              "workingTreeDirty": bool(subprocess.check_output(["git", "status", "--porcelain"], cwd=ROOT, text=True).strip()),
              "scope": "REGRESSION_NOT_HISTORICAL_18_TO_4_BASELINE", "summary": summarize(rows, evaluator), "cases": rows}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")
    lines = ["# RepoPilot regression report", "", f"Mode: {args.mode}; completed {len(rows)}/{len(cases)}; labels: {manifest['annotation']}", "",
             "| Case | Kind | Split | Status | Duration ms | Detail |", "| --- | --- | --- | --- | --- | --- |"]
    lines += [f"| {r['id']} | {r['kind']} | {r['split']} | {r['status']} | {r['durationMs']} | {r.get('detail', '').replace('|', '/')} |" for r in rows]
    args.output.with_suffix(".md").write_text("\n".join(lines) + "\n")
    print(json.dumps(report["summary"], ensure_ascii=False), flush=True)
    return 0 if len(rows) == len(cases) and all(r["status"] == "PASS" for r in rows) else 1


if __name__ == "__main__":
    raise SystemExit(main())
