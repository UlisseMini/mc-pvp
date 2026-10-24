"""Pure Java model checks; no Minecraft launch, server, replay data or network."""
import subprocess
import tempfile
import unittest
from pathlib import Path

import lab


class JavaModelTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        if not lab.JAVA.is_file() or not lab.JAVAC.is_file():
            raise unittest.SkipTest('Java and javac are required for the pure model checks')
        temporary_root = lab.RUNTIME / 'tmp'
        temporary_root.mkdir(parents=True, exist_ok=True)
        cls.temporary = tempfile.TemporaryDirectory(prefix='model-check-', dir=temporary_root)
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.classes = Path(cls.temporary.name)
        result = subprocess.run(
            [str(lab.JAVAC), '-d', str(cls.classes),
             str(lab.ROOT / 'src/lab/ComboModel.java'),
             str(lab.ROOT / 'src/lab/ExactTravel.java'),
             str(lab.ROOT / 'tests/ExactTravelCheck.java'),
             str(lab.ROOT / 'src/lab/VisibleTracker.java'),
             str(lab.ROOT / 'src/lab/TargetWatchdog.java'),
             str(lab.ROOT / 'src/lab/MomentumController.java'),
             str(lab.ROOT / 'tests/MomentumControllerCheck.java'),
             str(lab.ROOT / 'tests/NativeClockReplay.java'),
             str(lab.ROOT / 'tests/NativeClockReplayCheck.java'),
             str(lab.ROOT / 'tests/TargetWatchdogCheck.java'),
             str(lab.ROOT / 'tests/JavaModelCheck.java')],
            cwd=lab.ROOT, capture_output=True, text=True, timeout=30,
        )
        if result.returncode:
            raise AssertionError('Standalone Java compilation failed:\n' + result.stdout + result.stderr)

    def run_checks(self, group):
        result = subprocess.run(
            [str(lab.JAVA), '-cp', str(self.classes), 'JavaModelCheck', group],
            cwd=lab.ROOT, capture_output=True, text=True, timeout=30,
        )
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS', result.stdout)

    def test_model_contracts(self):
        self.run_checks('model')

    def test_exact_travel(self):
        result = subprocess.run([str(lab.JAVA), '-cp', str(self.classes), 'ExactTravelCheck'],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS', result.stdout)

    def test_visible_only_observer(self):
        self.run_checks('tracker')

    def test_native_clock_replay(self):
        result = subprocess.run([str(lab.JAVA), '-cp', str(self.classes), 'NativeClockReplayCheck'],
                                capture_output=True, text=True, timeout=30)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('passed', result.stdout.lower())

    def test_momentum_and_planner_control_limits(self):
        result = subprocess.run([str(lab.JAVA), '-cp', str(self.classes), 'MomentumControllerCheck'],
                                capture_output=True, text=True, timeout=20)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS', result.stdout)

    def test_target_watchdog_fails_closed(self):
        result = subprocess.run([str(lab.JAVA), '-cp', str(self.classes), 'lab.TargetWatchdogCheck'],
                                capture_output=True, text=True, timeout=10)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn('PASS', result.stdout)


if __name__ == '__main__':
    unittest.main()
