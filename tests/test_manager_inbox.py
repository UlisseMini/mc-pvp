import json
import os
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch

import lab_runtime


class ManagerInboxTest(unittest.TestCase):
    def test_command_first_becomes_visible_after_its_contents_are_complete(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / 'manager.json').write_text(json.dumps({'pid': os.getpid()}))
            (root / 'status.json').write_text(json.dumps({'time': time.time() * 1000}))
            inbox = root / 'manager/inbox'

            def write_in_parts(path, message):
                with path.open('w') as file:
                    file.write(message[:1])
                    file.flush()
                    self.assertEqual(list(inbox.glob('*.json')), [])
                    file.write(message[1:])

            with patch.object(lab_runtime, 'RUNTIME', root), patch.object(Path, 'write_text', write_in_parts):
                lab_runtime.send('lab combat')

            request, = inbox.iterdir()
            self.assertEqual(request.suffix, '.json')
            self.assertEqual(json.loads(request.read_text()), {'command': 'lab combat'})
