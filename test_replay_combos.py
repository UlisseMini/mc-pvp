"""Pure server-replay combo tests, without a running Minecraft server."""
import subprocess
import tempfile
import unittest

import lab


class ReplayComboTests(unittest.TestCase):
    def test_combo_event_semantics(self):
        with tempfile.TemporaryDirectory(prefix='pvp-replay-combos-') as classes:
            subprocess.run([str(lab.JAVAC), '-proc:none', '-encoding', 'UTF-8', '-d', classes,
                            str(lab.ROOT/'src/lab/ReplayCombos.java'),
                            str(lab.ROOT/'tests/ReplayCombosCheck.java')], check=True, capture_output=True, text=True)
            result=subprocess.run([str(lab.JAVA), '-cp', classes, 'lab.ReplayCombosCheck'],
                                  check=True, capture_output=True, text=True)
            self.assertIn('PASS replay combos: 15 checks', result.stdout)


if __name__=='__main__':
    unittest.main()
