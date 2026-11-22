"""Regression tests for Bow Duels physics, RNG independence, Rust parity, and native verification."""
from pathlib import Path
import sys
import unittest

import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import arena
import compare_native
from rust_arena import Geometry, RustArena, build


class BowDuelsTest(unittest.TestCase):
    def test_arrows_cannot_overwrite_inflight_pearl(self):
        env = arena.Arena(1, 101)
        env.active_box[:] = False
        env.pos[0, 0] = [0, 0, -5]
        env.pos[0, 1] = [0, 0, 5]
        env.yaw[0, 0], env.pitch[0, 0] = 0, -45  # High arc so pearl stays airborne
        # Throw pearl
        env.launch(np.array([[True, False]]), True, np.full((1, 2), 1.5))
        pearl_slots = np.flatnonzero(env.apearl[0] & (env.age[0] > 0))
        self.assertEqual(len(pearl_slots), 1)
        pearl_slot = int(pearl_slots[0])
        # Fire 7 consecutive arrows while pearl is airborne
        for _ in range(7):
            env.launch(np.array([[True, False]]), False, np.full((1, 2), 1.5))
        self.assertTrue(env.apearl[0, pearl_slot])
        self.assertGreater(env.age[0, pearl_slot], 0)

    def test_unrelated_duels_do_not_change_each_others_spread(self):
        single = arena.Arena(1, 777)
        batch = arena.Arena(8, 777)
        np.testing.assert_array_equal(single.pos[0], batch.pos[0])
        np.testing.assert_array_equal(single.yaw[0], batch.yaw[0])
        # Fire in arenas 1..7 of batch first, then fire identically in arena 0 of both.
        batch_other = np.zeros((8, 2), bool)
        batch_other[1:, 0] = True
        batch.launch(batch_other, False, np.full((8, 2), 3.0))
        single.launch(np.array([[True, False]]), False, np.full((1, 2), 3.0))
        mask0 = np.zeros((8, 2), bool)
        mask0[0, 0] = True
        batch.launch(mask0, False, np.full((8, 2), 3.0))
        np.testing.assert_array_equal(single.av[0, 0], batch.av[0, 0])

    def test_wall_hugging_player_is_still_hit_by_fast_arrow(self):
        env = arena.Arena(1, 55)
        env.active_box[:] = False
        env.pos[0, 0] = [10.0, 0.0, 0.0]
        env.pos[0, 1] = [float(arena.WALL[0]), 0.0, 0.0]  # Pressed against +X wall (17.7)
        env.ap[0, 0] = [16.5, 1.0, 0.0]
        env.av[0, 0] = [2.9, 0.0, 0.0]  # Endpoint 19.4 > WALL+0.3 (18.0), crosses player at t~0.2
        env.age[0, 0] = 1
        env.move_projectiles(np.ones(1, bool))
        self.assertEqual(env.hits[0, 0], 1)
        self.assertLess(env.hp[0, 1], 20.0)

    def test_pearl_wall_impact_teleports_and_respects_hurt_immunity(self):
        env = arena.Arena(1, 56)
        env.active_box[:] = False
        env.pos[0, 0] = [10.0, 0.0, 0.0]
        env.pos[0, 1] = [-10.0, 0.0, 0.0]
        env.ap[0, 5] = [17.4, 1.5, 0.0]
        env.av[0, 5] = [1.2, 0.0, 0.0]  # Hits +X wall (18.0)
        env.age[0, 5] = 1
        env.apearl[0, 5] = True
        env.move_projectiles(np.ones(1, bool))
        self.assertAlmostEqual(float(env.pos[0, 0, 0]), 17.4, places=4)
        self.assertEqual(float(env.hp[0, 0]), 15.0)
        self.assertEqual(int(env.hurt[0, 0]), 10)
        # Second pearl landing during hurt immunity teleports without extra damage
        env.ap[0, 5] = [12.0, 0.5, 0.0]
        env.av[0, 5] = [0.0, -1.0, 0.0]
        env.age[0, 5] = 1
        env.apearl[0, 5] = True
        env.move_projectiles(np.ones(1, bool))
        self.assertAlmostEqual(float(env.pos[0, 0, 0]), 12.0, places=4)
        self.assertEqual(float(env.hp[0, 0]), 15.0)

    def test_bow_draw_cancels_first_tick_sprint_jump(self):
        env = arena.Arena(1, 57)
        env.active_box[:] = False
        env.pos[0, 0] = [0.0, 0.0, 0.0]
        env.vel[0, 0] = [0.0, -0.0784, 0.0]
        env.yaw[0, 0] = 0.0
        act = np.zeros((1, 2, len(arena.KEYS) + 2), np.float32)
        act[0, 0, 0] = 2  # forward
        act[0, 0, 1] = 1  # no strafe
        act[0, 0, 2] = 1  # jump
        act[0, 0, 3] = 1  # sprint
        act[0, 0, 5] = 1  # use (bow draw)
        act[0, 1, :2] = 1
        env.step(act)
        self.assertFalse(bool(env.sprint[0, 0]))
        self.assertLess(float(env.vel[0, 0, 2]), 0.15)  # No +0.20 sprint-jump boost

    def test_melee_knockback_and_hitbox_expansion(self):
        env = arena.Arena(1, 58)
        env.active_box[:] = False
        env.pos[0, 0] = [0.0, 0.0, 0.0]
        env.pos[0, 1] = [0.35, 0.0, 2.0]  # Outside 0.30 box, inside 0.40 expanded box
        env.yaw[0, 0], env.pitch[0, 0] = 0.0, 0.0
        env.melee(np.array([[True, False]]), np.ones(1, bool))
        self.assertLess(float(env.hp[0, 1]), 20.0)
        self.assertGreater(float(env.vel[0, 1, 2]), 0.25)

    def test_rust_parity_with_sneak_and_empty_rows(self):
        lib = Path(__file__).resolve().parents[2] / 'runtime/bow-league/parity/libbow_geometry.dylib'
        if not lib.exists():
            lib = build(lib.parent)
        geom = Geometry(lib)
        a = arena.Arena(8, 303)
        b = RustArena(8, 303, geometry=geom)
        # Empty rows must not ZeroDivisionError
        out = b.terrain_hit(np.zeros((0, 3)), np.zeros((0, 3)), rows=np.array([], dtype=np.int64))
        self.assertEqual(out.shape, (0,))
        a.sneak[:, 0] = b.sneak[:, 0] = True
        a.launch(np.ones((8, 2), bool), False, np.full((8, 2), 2.5))
        b.launch(np.ones((8, 2), bool), False, np.full((8, 2), 2.5))
        a.update_vision()
        b.update_vision()
        np.testing.assert_allclose(a.observe(0), b.observe(0), rtol=5e-6, atol=5e-6)

    def test_native_verifier_rejects_broken_arrow_damage(self):
        rec = Path(__file__).resolve().parents[2] / 'runtime/bow-league/parity/native-006'
        if not rec.exists():
            self.skipTest('Native parity fixture absent')
        server = compare_native.load(rec / 'server.tsv')
        proj = compare_native.projectile(arena, server)
        hits = [c for c in proj['cases'] if not c['pearl'] and c['native_hit']]
        self.assertTrue(hits)
        for c in hits:
            self.assertAlmostEqual(c['sim_hp_loss'], c['expected_base_hp_loss'], places=3)


if __name__ == '__main__':
    unittest.main()
