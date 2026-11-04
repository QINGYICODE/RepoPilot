#!/usr/bin/env python3
"""Run real model checks on explicitly authorized RepoPilot snapshots; no credentials are printed."""
import argparse
import json
from pathlib import Path
import time
import urllib.request


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--api", default="http://127.0.0.1:8080/api")
    parser.add_argument("--head", default="4e06ec5fe551998e99cf798aaca629a85b85ce3d")
    parser.add_argument("--base", default="4836f61de22da2664bdb6b01f01278c9fb44befa")
    args = parser.parse_args()
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))

    def call(path, body=None):
        request = urllib.request.Request(args.api + path, data=None if body is None else json.dumps(body, ensure_ascii=False).encode(), headers={"Content-Type": "application/json"})
        with opener.open(request, timeout=20) as response:
            return json.load(response)

    repo = next((r for r in call("/repositories") if f"{r['owner']}/{r['name']}".lower() == "qingyicode/repopilot"), None)
    if not repo:
        raise SystemExit("Connect and explicitly authorize QINGYICODE/RepoPilot in repository management first.")
    snapshots = call(f"/repositories/{repo['id']}/snapshots")

    def snapshot(sha):
        found = next((s for s in snapshots if s["commitSha"] == sha and s["indexVersion"] == "v2.3"), None)
        if not found:
            raise SystemExit(f"Import this complete SHA with index v2.3 first: {sha}")
        return found["id"]

    head, baseline = snapshot(args.head), snapshot(args.base)
    runs = []
    for task, question, extra in [
        ("INCIDENT_FIX", "IMPECCABLE_HOME 告警，查找有 diff 依据的历史修复候选。", {}),
        ("DEPENDENCY_IMPACT", "backend 模块的依赖变更会影响哪些服务？请比较固定版本并给出证据。", {"baseSnapshotId": baseline, "module": "backend"}),
    ]:
        result = call("/analysis-runs", {"repoId": repo["id"], "snapshotId": head, "task": task, "question": question, **extra})
        deadline = time.monotonic() + 105
        while result["status"] == "RUNNING" and time.monotonic() < deadline:
            time.sleep(1)
            result = call("/analysis-runs/" + result["id"])
        runs.append(result)
        print(task, result["status"], result["reasonCode"], flush=True)
    output = Path(__file__).resolve().parents[1] / ".local/repository-agent-smoke.json"
    output.parent.mkdir(exist_ok=True)
    output.write_text(json.dumps(runs, ensure_ascii=False, indent=2) + "\n")
    if any(r["reasonCode"] != "EVIDENCE_VERIFIED" for r in runs):
        raise SystemExit("Real model check did not pass; inspect .local/repository-agent-smoke.json")
    assert any(c["commitSha"] == "bf9c07ff7639c2f8dc779d48eac8fbfb561613fc" for c in runs[0]["incident"]["candidates"])
    assert runs[1]["dependency"]["affectedServices"] == ["backend"]


if __name__ == "__main__":
    main()
