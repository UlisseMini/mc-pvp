import unittest
from combo_survival import episodes, geometric_distance


class ComboCheck(unittest.TestCase):
    def test_distance_ignores_aim(self):
        p = {'native_eye': [0, 1.62, 0], 'expanded_box': [3, 0, -.4, 3.8, 1.9, .4], 'in_range': False}
        self.assertEqual(geometric_distance(p), 3)
        p['native_eye'][1] = 2.9
        self.assertAlmostEqual(geometric_distance(p), 10 ** .5)

    def test_upgrades_rods_and_terminal_censoring(self):
        def h(t, who='a', **k):
            return dict(time=t, attacker=who, damage_upgrade=False, **k)
        hits = [h(0), {**h(20), 'damage_upgrade': True}, h(500), h(510, 'b', rod=True), h(1000), h(1200, 'b'), h(1800)]
        rows = episodes(hits, 'a', 1850)
        self.assertEqual([r['fresh_hits'] for r in rows], [3, 1])
        self.assertFalse(rows[0]['censored'])
        self.assertTrue(rows[1]['censored'])
        self.assertEqual(rows[0]['duration_ms'], 1200)

    def test_opponent_upgrade_still_breaks_no_reply_run(self):
        rows = episodes([{'time': 1, 'attacker': 'a', 'damage_upgrade': False},
                         {'time': 20, 'attacker': 'b', 'damage_upgrade': True}], 'a', 100)
        self.assertEqual(len(rows), 1)
        self.assertEqual(rows[0]['return'], 20)


if __name__ == '__main__':
    unittest.main()
