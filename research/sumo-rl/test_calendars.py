import unittest
from calendars import server_stage

class WorldClockTest(unittest.TestCase):
    def test_one_damage_timer_decrement_per_server_tick(self):
        events = [('drain_start', 0)]
        for world in (0, 1, 2):
            events += [('entity_tick_start', world), ('tracker_tick_start', world)]
        events += [('tick_end', 0)]
        stages = [server_stage(dict(type='replication_server_clock', phase=phase, world_ordinal=world))
                  for phase, world in events]
        self.assertEqual([s for s in stages if s], ['SERVER_DRAIN','SERVER_ENTITY','SERVER_TRACK','SERVER_END'])
