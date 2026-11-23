import gzip
import hashlib
import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from tools import event_logs
from tools.event_logs import EventLogError, finalize_rotation, iter_events, prepare_rotation


class EventLogsTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.runtime = Path(self.temporary.name) / 'runtime'
        self.slot = self.runtime / 'bots/0'
        self.slot.mkdir(parents=True)
        self.path = self.slot / 'events.jsonl'
        self.path.write_bytes(b'')
        self.control = self.slot / 'control.properties'
        self.control.write_text('enabled=false\n')
        self.status = self.runtime / 'status.json'
        self.status.write_text('{"matches":[]}')

    def rows(self, *names):
        return b''.join((json.dumps({'match': name, 'order': index}) + '\n').encode()
                        for index, name in enumerate(names))

    def rotate(self):
        plan = prepare_rotation(self.path)
        finalize_rotation(plan, writer_stopped=True)
        return plan

    def test_two_phase_byte_verification_and_chronological_multiple_history(self):
        original = self.rows('m1', 'm2', 'm1')
        self.path.write_bytes(original)
        before = self.path.stat()
        plan = prepare_rotation(self.path)
        self.assertEqual(self.path.read_bytes(), original)
        self.assertEqual(self.path.stat().st_ino, before.st_ino)
        self.assertFalse((self.slot / 'events-history/manifest.json').exists())
        self.assertEqual(plan['verified_sha256'], hashlib.sha256(original).hexdigest())
        archive = self.slot / 'events-history' / plan['entry']['file']
        self.assertEqual(gzip.decompress(archive.read_bytes()), original)
        self.assertEqual(plan['entry']['match_ids'], ['m1', 'm2'])
        self.assertTrue(plan['entry']['match_index_complete'])
        finalize_rotation(plan, writer_stopped=True)
        self.assertEqual(self.path.read_bytes(), b'')
        self.assertNotEqual(self.path.stat().st_ino, before.st_ino)
        self.assertEqual(self.path.stat().st_mode & 0o777, before.st_mode & 0o777)
        second = self.rows('m3')
        self.path.write_bytes(second)
        self.rotate()
        self.path.write_bytes(self.rows('m4'))
        self.assertEqual([e['match'] for e in iter_events(self.path)], ['m1', 'm2', 'm1', 'm3', 'm4'])
        self.assertEqual(len(list(iter_events(self.path, 'm1'))), 2)
        self.assertTrue(archive.exists())

    def test_finalize_requires_explicit_stopped_writer_and_unchanged_source(self):
        self.path.write_bytes(self.rows('m1'))
        plan = prepare_rotation(self.path)
        with self.assertRaisesRegex(EventLogError, 'stop the source writer'):
            finalize_rotation(plan)
        with self.assertRaises(EventLogError):
            finalize_rotation(plan, writer_stopped='yes')
        self.path.write_bytes(self.rows('m2'))  # Same size, changed timestamp/content.
        with self.assertRaisesRegex(EventLogError, 'Source identity'):
            finalize_rotation(plan, writer_stopped=True)
        self.assertEqual([e['match'] for e in iter_events(self.path)], ['m2'])
        self.assertFalse((self.slot / 'events-history/manifest.json').exists())

    def test_refuses_active_matches_enabled_or_unknown_controls_in_both_phases(self):
        self.path.write_bytes(self.rows('m1'))
        self.status.write_text('{"matches":[{"id":"human-duel"}]}')
        with self.assertRaises(EventLogError):
            prepare_rotation(self.path)
        self.status.write_text('{"matches":[]}')
        self.control.write_text('enabled=true\n')
        with self.assertRaises(EventLogError):
            prepare_rotation(self.path)
        self.control.write_text('enabled=false\n')
        plan = prepare_rotation(self.path)
        other = self.runtime / 'bots/1'
        other.mkdir()
        (other / 'control.properties').write_text('enabled=true\n')
        with self.assertRaises(EventLogError):
            finalize_rotation(plan, writer_stopped=True)
        (other / 'control.properties').write_text('enabled=false\n')
        self.status.write_text('{"matches":null}')
        with self.assertRaises(EventLogError):
            finalize_rotation(plan, writer_stopped=True)
        self.status.write_text('{"matches":[]}')
        self.control.unlink()
        with self.assertRaises(EventLogError):
            finalize_rotation(plan, writer_stopped=True)
        self.assertEqual(self.path.read_bytes(), self.rows('m1'))

    def test_prepare_detects_append_and_archive_corruption_without_truncation(self):
        self.path.write_bytes(self.rows('m1'))
        real = event_logs._verify_archive
        def changed(archive):
            result = real(archive)
            with self.path.open('ab') as source:
                source.write(self.rows('m2'))
            return result
        with patch('tools.event_logs._verify_archive', side_effect=changed):
            with self.assertRaisesRegex(EventLogError, 'Source changed'):
                prepare_rotation(self.path)
        self.assertEqual([e['match'] for e in iter_events(self.path)], ['m1', 'm2'])
        plan = prepare_rotation(self.path)
        archive = self.slot / 'events-history' / plan['entry']['file']
        with archive.open('ab') as stream:
            stream.write(b'changed')
        with self.assertRaisesRegex(EventLogError, 'archive changed'):
            finalize_rotation(plan, writer_stopped=True)
        self.assertEqual([e['match'] for e in iter_events(self.path)], ['m1', 'm2'])

    def test_bad_decompressed_verification_never_publishes_history(self):
        original = self.rows('m1')
        self.path.write_bytes(original)
        with patch('tools.event_logs._verify_archive', return_value=('0' * 64, len(original))):
            with self.assertRaisesRegex(EventLogError, 'SHA256'):
                prepare_rotation(self.path)
        self.assertEqual(self.path.read_bytes(), original)
        self.assertFalse((self.slot / 'events-history/manifest.json').exists())

    def test_replaced_source_and_changed_manifest_are_refused(self):
        self.path.write_bytes(self.rows('m1'))
        plan = prepare_rotation(self.path)
        replacement = self.slot / 'replacement'
        replacement.write_bytes(self.path.read_bytes())
        os.replace(replacement, self.path)
        with self.assertRaisesRegex(EventLogError, 'Source identity'):
            finalize_rotation(plan, writer_stopped=True)
        plan = prepare_rotation(self.path)
        (self.slot / 'events-history/manifest.json').write_text('{"version":1,"archives":[]}')
        with self.assertRaisesRegex(EventLogError, 'manifest changed'):
            finalize_rotation(plan, writer_stopped=True)
        self.assertEqual([e['match'] for e in iter_events(self.path)], ['m1'])

    def test_crlf_and_non_object_matching_error_follow_normal_text_reader(self):
        self.path.write_bytes(self.rows('m1').replace(b'\n', b'\r\n') + b'"bad"\r\n')
        self.rotate()
        self.assertEqual([e['match'] for e in iter_events(self.path, 'm1')], ['m1'])
        with self.assertRaises(AttributeError):
            list(iter_events(self.path, 'bad'))

    def test_new_match_skips_unrelated_gzip_but_old_match_reads_it(self):
        self.path.write_bytes(self.rows('old'))
        self.rotate()
        self.path.write_bytes(self.rows('new'))
        with patch('tools.event_logs.gzip.open', side_effect=AssertionError('history opened')):
            self.assertEqual([e['match'] for e in iter_events(self.path, 'new')], ['new'])
            with self.assertRaisesRegex(AssertionError, 'history opened'):
                list(iter_events(self.path, 'old'))

    def test_malformed_line_filter_behavior_and_unfiltered_failure_are_preserved(self):
        self.path.write_bytes(b'broken unrelated\n{"match":"bad",broken}\n' + self.rows('good'))
        self.rotate()
        self.assertEqual([e['match'] for e in iter_events(self.path, 'good')], ['good'])
        with self.assertRaises(json.JSONDecodeError):
            list(iter_events(self.path, 'bad'))
        with self.assertRaises(json.JSONDecodeError):
            list(iter_events(self.path))
        manifest = json.loads((self.slot / 'events-history/manifest.json').read_text())
        self.assertFalse(manifest['archives'][0]['match_index_complete'])

    def test_partial_utf8_and_json_line_continue_through_history_boundaries(self):
        whole = '{"match":"old","text":"café"}\n'.encode()
        at = whole.index('é'.encode()) + 1
        self.path.write_bytes(whole[:at])
        self.rotate()
        self.path.write_bytes(whole[at:] + self.rows('new'))
        self.assertEqual(list(iter_events(self.path))[0]['text'], 'café')
        self.assertEqual([e['match'] for e in iter_events(self.path, 'old')], ['old'])
        self.rotate()
        self.path.write_bytes(self.rows('later'))
        self.assertEqual([e['match'] for e in iter_events(self.path)], ['old', 'new', 'later'])

    def test_interrupted_finalization_has_no_duplicate_records_and_can_retry(self):
        self.path.write_bytes(self.rows('m1', 'm2'))
        plan = prepare_rotation(self.path)
        original_replace = os.replace
        def interrupted(source, target):
            if Path(target) == self.path:
                raise OSError('simulated interruption')
            return original_replace(source, target)
        with patch('tools.event_logs.os.replace', side_effect=interrupted):
            with self.assertRaisesRegex(OSError, 'simulated interruption'):
                finalize_rotation(plan, writer_stopped=True)
        self.assertEqual([e['match'] for e in iter_events(self.path)], ['m1', 'm2'])
        with self.assertRaisesRegex(EventLogError, 'prior finalize'):
            prepare_rotation(self.path)
        finalize_rotation(plan, writer_stopped=True)
        self.assertEqual(self.path.read_bytes(), b'')
        self.assertEqual([e['match'] for e in iter_events(self.path)], ['m1', 'm2'])

    def test_reader_started_before_finalization_retains_old_inode_without_loss(self):
        self.path.write_bytes(self.rows('m1', 'm2'))
        plan = prepare_rotation(self.path)
        original_manifest = event_logs._manifest
        called = False
        def rotate_on_manifest(path):
            nonlocal called
            if not called:
                called = True
                old = original_manifest(path)
                finalize_rotation(plan, writer_stopped=True)
                return old
            return original_manifest(path)
        with patch('tools.event_logs._manifest', side_effect=rotate_on_manifest):
            self.assertEqual([e['match'] for e in iter_events(self.path)], ['m1', 'm2'])


if __name__ == '__main__':
    unittest.main()
