"""Versioned artifact identity shared by launch/experiment tooling. No game control."""
import hashlib
import json
from pathlib import Path
from lab import ROOT

ARTIFACTS = {
    'agent_sha256': 'build/lab-agent.jar',
    'bridge_sha256': 'build/practice-client/practice-bridge.jar',
    'module_sha256': 'build/practice-client/practice-module.jar',
    'headless_sha256': 'build/forge/headless-shaded.jar',
}


def artifact_hashes(root=ROOT):
    """Freeze existing local artifacts; absent Forge artifacts do not affect legacy."""
    result = {'schema': 1, 'minecraft': '1.8.9', 'protocol': 47}
    for key, relative in ARTIFACTS.items():
        path = Path(root)/relative
        if path.is_file():
            result[key] = hashlib.sha256(path.read_bytes()).hexdigest()
    return result


def client_provenance(heartbeat):
    backend = heartbeat.get('backend', 'legacy')
    fields = ('agent_sha256',) if backend == 'legacy' else ('bridge_sha256', 'module_sha256')
    return {'backend': backend, **{key: heartbeat.get(key, 'unknown') for key in fields}}


def client_matches(heartbeat, expected):
    """Unknown identities never qualify, including a missing loaded module hash."""
    identity = client_provenance(heartbeat)
    if identity['backend'] not in ('legacy', 'forge'):
        return False
    return all(value not in ('', 'unknown', 'unreported', None) and expected.get(key) == value
               for key, value in identity.items() if key != 'backend')


def cohort_provenance(root=ROOT):
    result = artifact_hashes(root)
    try:
        manager = json.loads((Path(root)/'runtime/manager.json').read_text())
        result['slot_backends'] = manager.get('bot_backends', ['legacy']*manager.get('bot_slots', 8))
    except (OSError, ValueError):
        result['slot_backends'] = []
    return result
