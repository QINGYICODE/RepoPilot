#!/usr/bin/env python3
"""Run local commands with .env loaded as data (never evaluated as shell code)."""
import argparse
import os
from pathlib import Path
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]


def load_environment():
    environment = os.environ.copy()
    path = ROOT / ".env"
    if path.exists():
        for number, raw in enumerate(path.read_text().splitlines(), 1):
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            key, separator, value = line.partition("=")
            key, value = key.strip(), value.strip()
            if not separator or not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", key):
                sys.exit(f"Invalid .env entry at line {number}; use KEY=value.")
            if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
                value = value[1:-1]
            environment.setdefault(key, value)
    return environment


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["backend", "agent", "agent-check", "backend-check", "mysql-test", "upstream-test", "live-llm", "full-check", "dependency-tree"])
    args = parser.parse_args()
    environment = load_environment()
    command = ["mvn", "-B", "-ntp"]
    if environment.get("MAVEN_REPO_LOCAL"):
        command.append("-Dmaven.repo.local=" + environment["MAVEN_REPO_LOCAL"])
    if args.command in {"backend", "agent", "agent-check", "mysql-test", "upstream-test", "live-llm", "full-check"}:
        if not environment.get("DB_PASSWORD"):
            sys.exit("Set DB_PASSWORD in .env or the environment first.")
    if args.command in {"live-llm", "full-check", "agent", "agent-check"}:
        missing = [name for name in ["LLM_BASE_URL", "LLM_MODEL", "LLM_API_KEY"] if not environment.get(name)]
        if missing:
            sys.exit("Live model check NOT RUN; missing: " + ", ".join(missing))
        if args.command == "agent":
            command += ["spring-boot:run", "-Dspring-boot.run.profiles=llm"]
        else:
            command += ["-P", "upstream-it,live-llm" if args.command == "full-check" else "live-llm", "verify"]
            if args.command == "agent-check":
                command += ["-Drepopilot.agent-live=true"]
    elif args.command == "mysql-test":
        command += ["-P", "mysql-it", "verify"]
    elif args.command == "upstream-test":
        command += ["-P", "upstream-it", "verify"]
    elif args.command == "backend":
        command += ["spring-boot:run"]
    elif args.command == "dependency-tree":
        command += ["dependency:tree", "-DoutputFile=target/dependency-tree.txt"]
    else:
        command += ["verify"]
    try:
        return subprocess.call(command, cwd=ROOT / "backend", env=environment)
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
