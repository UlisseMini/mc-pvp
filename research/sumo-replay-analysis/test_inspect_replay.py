"""Exercise the conditional movement math and real archive regressions."""
import json
from pathlib import Path
import unittest

from inspect_replay import Cut, Floor, Move, branch, evidence


class BranchTests(unittest.TestCase):
    def test_early_and_late_cuts_use_comparable_movement(self):
        floor = Floor(0.,.3)
        first = Move(100,1,(0.,12.,0.),(1.,12.36,0.),(1.,.36,0.),.546,True,True,True,True)
        second = Move(200,2,(1.,12.36,0.),(1.546,12.64,0.),(.546,.28,0.),.91,False,True,True,True)
        early = branch([first,second],[Cut(90,(1.,0.),1.,.6,'add')],floor,(1.,0.))
        late = branch([first,second],[Cut(190,(.546,0.),1.,.6,'add')],floor,(1.,0.))
        reset = branch([first,second],[Cut(90,(1.,0.),1.,.6,'add')],floor,(1.,0.),overwrite=150)
        self.assertAlmostEqual(-early['final_shift'][0],.4*(1+.546),places=6)
        self.assertAlmostEqual(-late['final_shift'][0],.4*.546,places=6)
        self.assertAlmostEqual(-reset['final_shift'][0],.4,places=6)

    def test_footprint_has_actual_round_corners(self):
        floor = Floor(4096.5,.3)
        self.assertTrue(floor.covers((4096.5,6.8)))
        self.assertFalse(floor.covers((4102.8,6.8)))
        self.assertAlmostEqual(floor.margin((4096.5,.5),(1.,0.)),6.8)

    def test_changed_landing_limits_the_prefix(self):
        floor = Floor(0.,.3)
        missed = Move(100,1,(6.5,12.1,.5),(7.5,11.9,.5),(1.,-.2,0.),.91,False,True,True,True)
        later = Move(200,2,(7.5,11.9,.5),(8.4,11.6,.5),(.9,-.3,0.),.91,False,True,True,True)
        replay = branch([missed,later],[Cut(90,(1.,0.),1.,.6,'add')],floor,(1.,0.))
        self.assertEqual(replay['status'],'landing_diverges')
        self.assertEqual(replay['n'],1)
        self.assertTrue(replay['rows'][0]['edited_support'])
        self.assertFalse(replay['rows'][0]['factual_support'])


class ArchiveTests(unittest.TestCase):
    def game(self, mid):
        manifest = json.loads(Path(__file__).with_name('cohort.json').read_text())
        source = next(row for label in ('new','previous') for row in manifest[label] if row['id']==mid)
        return evidence(source,True)

    def test_brake_carries_position_through_next_velocity(self):
        game = self.game('m9153')
        anchor = next(h for h in game['launches'] if h['i']==49)
        early = next(c for c in anchor['candidates'] if c['edit']=='click at recorded aimed sprint sample')
        self.assertEqual(anchor['next_incoming_hit'],50)
        self.assertAlmostEqual(early['at_next']['actual_margin'],.717,delta=.01)
        self.assertAlmostEqual(early['at_next']['edited_margin'],2.414,delta=.02)
        self.assertAlmostEqual(early['at_next']['edited_followup_peak'],.974,delta=.01)
        self.assertTrue(early['landing']['edited_support'])
        self.assertFalse(early['landing']['actual_support'])

    def test_contact_chain_stops_at_lost_reach(self):
        game = self.game('m8900')
        hit = next(h for h in game['launches'] if h['i']==49)
        actual = hit['presses'][1]
        self.assertEqual(actual['server']['accepts'],[50])
        self.assertEqual(actual['kind'],'brake')
        branch_without_first = next(c for c in hit['candidates'] if c['edit']=='remove brake')
        self.assertEqual(branch_without_first['status'],'recorded_contact_may_change')
        self.assertEqual(branch_without_first['measured_steps'],2)

    def test_landing_launch_links_adjacent_wall_times(self):
        game = self.game('m5922')
        hit = next(h for h in game['launches'] if h['i']==20)
        self.assertEqual(hit['victim'],'b')
        self.assertAlmostEqual(hit['slide'],3.681,delta=.01)
        self.assertTrue(hit['candidates'][0]['landing']['edited_support'])


if __name__=='__main__':
    unittest.main()
