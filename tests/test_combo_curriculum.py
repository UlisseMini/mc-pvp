import unittest
from unittest.mock import patch
from tools.combo_curriculum import maintenance


class CurriculumCheck(unittest.TestCase):
    def setUp(self):
        self.game={'id':'synthetic','a':'p','b':'q','start':0,'finish':28500}
        self.hits=[{'type':'hit','attacker':'p','time':500+i*700,'damage_upgrade':False} for i in range(40)]
        self.probes=[{'type':'reach_probe','time':t,'native_eye':[0,1,0],
                      'expanded_box':[1.7,0,-.4,2.5,2,.4],'attack_reach':1.5} for t in range(1900,28501,50)]
        self.own=[{**p,'attack_reach':3} for p in self.probes]

    def evaluate(self):
        with patch('tools.combo_curriculum.server_events',return_value=self.hits),patch('tools.combo_curriculum.client_events',side_effect=lambda game,side:self.own if side=='a' else self.probes):
            return maintenance(self.game)

    def test_reduced_reach_and_long_finite_chain(self):
        self.assertTrue(self.evaluate()['passed'])
        for p in self.probes:p['attack_reach']=2.5
        self.assertFalse(self.evaluate()['passed'])

    def test_return_upgrade_breaks_even_when_later_chain_is_long(self):
        self.hits.append({'type':'hit','attacker':'q','time':2000,'damage_upgrade':True})
        self.assertFalse(self.evaluate()['passed'])

    def test_stalling_at_safe_distance_is_not_success(self):
        self.hits=self.hits[:-4]
        self.assertFalse(self.evaluate()['passed'])

    def test_missing_geometry_is_not_denial(self):
        self.probes=[p for p in self.probes if not 8000<=p['time']<=8500]
        self.assertFalse(self.evaluate()['passed'])

    def test_leaving_own_reach_invalidates_even_without_a_return(self):
        self.own[100]={**self.own[100],'expanded_box':[3.1,0,-.4,3.9,2,.4]}
        self.assertFalse(self.evaluate()['passed'])

    def test_unknown_own_view_is_not_one_sided_reach(self):
        self.own=[]
        self.assertFalse(self.evaluate()['passed'])


if __name__=='__main__':unittest.main()
