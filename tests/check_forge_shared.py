#!/usr/bin/env python3
"""Run Java 8 host-safety, aim and common practice checks on the installed bot JVM."""
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from lab import BUILD, CACHE, JAVAC
from forge_runtime import JAVA8

out = BUILD/'forge-checks'
out.mkdir(parents=True, exist_ok=True)
cp = os.pathsep.join(map(str, [BUILD/'practice-client/shim', BUILD/'practice-client/compile-api.jar',
                              CACHE/'asm.jar', CACHE/'asm-tree.jar', CACHE/'practice-client/launchwrapper.jar',
                              *map(Path, json.loads((CACHE/'classpath.json').read_text()))]))
subprocess.run([str(JAVAC), '--release', '8', '-proc:none', '-cp', cp, '-d', str(out),
                str(ROOT/'tests/AimControllerCheck.java'),
                str(ROOT/'practice-client/smoke/BotSafetyCheck.java'),
                str(ROOT/'practice-client/shim/practice/bridge/BotTransformer.java')], check=True)
cp = str(out)+os.pathsep+cp
subprocess.run([str(JAVA8), '-ea', '-cp', cp, 'AimControllerCheck'], check=True)
subprocess.run([str(JAVA8), '-ea', '-cp', cp, 'practice.bridge.BotSafetyCheck', str(CACHE/'client.jar')], check=True)
practicecp = os.pathsep.join(str(BUILD/'practice-client'/d) for d in ('api', 'core', 'tests'))
subprocess.run([str(JAVA8), '-ea', '-cp', practicecp, 'practice.tests.Check',
                str(BUILD/'practice-client/practice-module.jar')], check=True)
