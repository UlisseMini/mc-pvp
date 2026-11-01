import hashlib
import tempfile
import unittest
from pathlib import Path
from runtime_provenance import artifact_hashes, client_provenance, client_matches, cohort_provenance


class RuntimeProvenanceTest(unittest.TestCase):
    def test_legacy_and_forge_require_their_actual_loaded_code(self):
        expected = {'agent_sha256': 'agent', 'bridge_sha256': 'bridge', 'module_sha256': 'module'}
        self.assertTrue(client_matches({'agent_sha256': 'agent'}, expected))
        self.assertFalse(client_matches({'agent_sha256': 'old'}, expected))
        forge = {'backend': 'forge', 'agent_sha256': 'unknown', 'bridge_sha256': 'bridge', 'module_sha256': 'module'}
        self.assertTrue(client_matches(forge, expected))
        self.assertEqual(client_provenance(forge), {'backend':'forge','bridge_sha256':'bridge','module_sha256':'module'})
        self.assertFalse(client_matches({**forge, 'module_sha256': 'old'}, expected))
        self.assertFalse(client_matches({**forge, 'bridge_sha256': 'old'}, expected))
        self.assertFalse(client_matches({'backend':'forge','agent_sha256':'agent'}, expected))
        self.assertFalse(client_matches({'backend':'unexpected','agent_sha256':'agent'}, expected))
        self.assertFalse(client_matches({}, {}))

    def test_artifact_changes_and_backend_assignment_are_explicit(self):
        with tempfile.TemporaryDirectory() as temporary:
            root=Path(temporary);(root/'build').mkdir();(root/'runtime').mkdir()
            artifact=root/'build/lab-agent.jar';artifact.write_bytes(b'first')
            (root/'runtime/manager.json').write_text('{"bot_backends":["forge","legacy"]}')
            first=cohort_provenance(root)
            self.assertEqual(first['protocol'],47)
            self.assertEqual(first['slot_backends'],['forge','legacy'])
            self.assertEqual(first['agent_sha256'],hashlib.sha256(b'first').hexdigest())
            artifact.write_bytes(b'second')
            self.assertNotEqual(first['agent_sha256'],artifact_hashes(root)['agent_sha256'])
