import unittest
from fresh_window_analysis import ready_bracket,reach_window


class FreshWindowsCheck(unittest.TestCase):
    def probe(self,t,d):return dict(time=t,native_eye=[0,1,0],expanded_box=[d,0,-.4,d+.6,2,.4],attack_reach=3)

    def test_ready_uses_observed_immunity_not_half_second_or_click_gate(self):
        ticks=[dict(time=t,b={'hurtResistantTime':h}) for t,h in [(100,20),(510,11),(557,10),(608,9)]]
        self.assertEqual(ready_bracket(ticks,100,650,'b'),[510,557])

    def test_outside_during_immunity_does_not_fail_ready_window(self):
        p=[self.probe(t,4 if t<500 else 2.8) for t in range(100,651,50)]
        self.assertEqual(reach_window(p,500,600)['classification'],'in')
        self.assertEqual(reach_window(p,450,550)['classification'],'mixed')

    def test_future_samples_do_not_fill_missing_past(self):
        self.assertEqual(reach_window([self.probe(500,2)],450,550)['classification'],'unknown')
        self.assertEqual(reach_window([self.probe(400,2),self.probe(650,2)],450,660)['classification'],'unknown')


if __name__=='__main__':unittest.main()
