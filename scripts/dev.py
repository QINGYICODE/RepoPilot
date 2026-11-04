#!/usr/bin/env python3
"""Run local commands with .env loaded as data (never evaluated as shell code)."""
import argparse
import os
from pathlib import Path
import re
import shutil
import socket
import subprocess
import sys
import urllib.parse

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


def doctor(environment):
    """Check local prerequisites without echoing configuration values or calling a model."""
    failures = 0

    def report(name, passed, note="", optional=False):
        nonlocal failures
        print(f"{'PASS' if passed else 'WARN' if optional else 'FAIL'} {name}" + (f": {note}" if note else ""))
        if not passed and not optional:
            failures += 1

    def version(command, pattern, minimum):
        try:
            result = subprocess.run(command, env=environment, capture_output=True, text=True, timeout=12)
            match = re.search(pattern, result.stdout + result.stderr)
            return result.returncode == 0 and match is not None and tuple(int(x) for x in match.groups()) >= minimum
        except (OSError, subprocess.TimeoutExpired):
            return False

    java_home = environment.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else shutil.which("java") or "java"
    javac = str(Path(java_home) / "bin/javac") if java_home else shutil.which("javac") or "javac"
    report("Java runtime >= 21", version([java, "-version"], r'version "(\d+)', (21,)))
    report("Full JDK compiler >= 21", version([javac, "-version"], r'javac (\d+)', (21,)), "Set JAVA_HOME to a full JDK if this fails")
    report("Maven >= 3.9", version(["mvn", "-v"], r'Apache Maven (\d+)\.(\d+)', (3, 9)))
    report("Node >= 22.12", version(["node", "--version"], r'v(\d+)\.(\d+)', (22, 12)))
    report("npm", version(["npm", "--version"], r'(\d+)', (1,)))
    report("MySQL credentials configured", bool(environment.get("DB_USERNAME") and environment.get("DB_PASSWORD")), "Checks presence only; values are never printed")
    reachable = False
    try:
        uri = urllib.parse.urlsplit(environment.get("DB_URL", "jdbc:mysql://localhost:3306/repopilot").removeprefix("jdbc:"))
        local = uri.scheme == "mysql" and uri.hostname in {"localhost", "127.0.0.1", "::1"} and (uri.port or 3306) == 3306
        if local:
            with socket.create_connection((uri.hostname, uri.port or 3306), timeout=2):
                reachable = True
    except (OSError, ValueError):
        pass
    report("Local MySQL localhost:3306 TCP", reachable, "TCP reachability only; run mysql-test to verify authentication, schema and migrations")
    report("Frontend dependencies installed", (ROOT / "frontend/node_modules/typescript").is_dir(), "Run npm ci --ignore-scripts in frontend if needed")
    missing = [key for key in ["LLM_BASE_URL", "LLM_MODEL", "LLM_API_KEY"] if not environment.get(key)]
    report("Model settings", not missing, "Configured; no model request was sent" if not missing else "Missing " + ", ".join(missing) + "; backend mode remains available", optional=True)
    return 1 if failures else 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["doctor", "check", "backend", "agent", "agent-check", "backend-check", "mysql-test", "upstream-test", "live-llm", "full-check", "dependency-tree"])
    args = parser.parse_args()
    environment = load_environment()
    if args.command == "doctor":
        return doctor(environment)
    command = ["mvn", "-B", "-ntp"]
    if environment.get("MAVEN_REPO_LOCAL"):
        command.append("-Dmaven.repo.local=" + environment["MAVEN_REPO_LOCAL"])
    if args.command in {"check", "backend", "agent", "agent-check", "mysql-test", "upstream-test", "live-llm", "full-check"}:
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
    elif args.command in {"mysql-test", "check"}:
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
        result = subprocess.call(command, cwd=ROOT / "backend", env=environment)
        if result or args.command != "check":
            return result
        for action, directory in [([sys.executable, "-m", "unittest", "discover", "-s", "scripts/tests", "-v"], ROOT),
                                  (["npm", "test"], ROOT / "frontend"), (["npm", "run", "build"], ROOT / "frontend")]:
            result = subprocess.call(action, cwd=directory, env=environment)
            if result:
                return result
        return 0
    except FileNotFoundError:
        print("A required command is missing; run python3 scripts/dev.py doctor.", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    sys.exit(main())
