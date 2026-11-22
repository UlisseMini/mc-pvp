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

    def test_modified_immunity_uses_declared_threshold(self):
        ticks=[dict(time=t,b={'hurtResistantTime':h}) for t,h in [(100,16),(410,9),(457,8)]]
        self.assertEqual(ready_bracket(ticks,100,500,'b',8),[410,457])

    def test_explicit_missing_probe_ends_previous_reach_validity(self):
        probes=[self.probe(0,4.0),dict(time=20,native_eye=None,expanded_box=None,attack_reach=3)]
        res=reach_window(probes,20,40)
        self.assertEqual(res['classification'],'unknown')
        self.assertEqual(res['duration_ms']['unknown'],20.0)
        self.assertEqual(res['duration_ms']['out'],0.0)

    def test_landmark_starts_at_three_consecutive_unanswered_hits_not_third_total(self):
        from fresh_window_analysis import analyze_events
        game=dict(id='m_test',a='A',b='B',start=0,finish=7000,combat_profile={'fresh_hit_ticks':10})
        def atk(t,who,s_before,s_after,hrt=0):
            return dict(type='science_server_attack',time=t,server_tick=t//50,attacker=who,
                        score_before=s_before,score_after=s_after,
                        target_before={'hurtResistantTime':hrt})
        # Hits at 1s, 2s each followed by a return (including an upgrade at 2.2s);
        # genuine 3-hit unanswered streak at 3s, 4s, 5s, followed by 6s window.
        a_times=(1000,2000,3000,4000,5000,6000)
        raw=[]
        for t in a_times:
            raw.append(dict(type='replication_server_input',time=t,player='A',packet_kind='in',action='ATTACK'))
        raw+=[
            atk(1000,'A',[0,0],[1,0]), atk(1200,'B',[1,0],[1,1]),
            atk(2000,'A',[1,1],[2,1]), atk(2200,'B',[2,1],[2,2],hrt=15),
            atk(3000,'A',[2,2],[3,2]), atk(4000,'A',[3,2],[4,2]),
            atk(5000,'A',[4,2],[5,2]), atk(6000,'A',[5,2],[6,2]),
        ]
        for t in a_times:
            raw.append(dict(type='tick',time=t+550,a={'hurtResistantTime':10},b={'hurtResistantTime':10}))
        order={'replication_server_input':0,'science_server_attack':1,'tick':2}
        raw.sort(key=lambda e:(e['time'],order[e['type']]))
        native=[]
        for k,t in enumerate(a_times,start=1):
            ns=(t-10)*1_000_000
            native.append(dict(type='click',time=t-10,monotonic_ns=ns))
            native.append(dict(type='science_click',time=t-10,monotonic_ns=ns,sequence=k,tick=t//50,
                               miss_cooldown_before=0,selected_target=True,
                               own={'posX':0,'posY':0,'posZ':0,'sprint':True},
                               target={'posX':2.5,'posY':0,'posZ':0,'box':[2.2,0,-.3,2.8,1.8,.3]}))
            native.append(dict(type='engine_attack',time=t-10,monotonic_ns=ns,click_attempt=k,
                               vx_before=0.1,vz_before=0.0,vx_after=0.06,vz_after=0.0,
                               sprint_before=True,sprint_after=False,
                               target_ready=True,ray_selected_target=True))
        native+=[dict(type='reach_probe',time=t,native_eye=[0,1,0],expanded_box=[2.5,0,-.4,3.1,2,.4],attack_reach=3)
                 for t in range(0,6600,50)]
        opposite=[dict(type='reach_probe',time=t,native_eye=[0,1,0],expanded_box=[4.0,0,-.4,4.6,2,.4],attack_reach=3)
                  for t in range(0,6600,50)]
        res=analyze_events(game,'a',raw,native,opposite)
        # First 3-hit unanswered streak completes at t=5000 (hits at 3000, 4000, 5000), NOT t=3000!
        self.assertTrue(res['landmarks']['3']['established'])
        self.assertEqual(res['landmarks']['3']['entry_hit_ms'],5000)
        self.assertEqual(res['post_third_hit_windows'],1)
        self.assertFalse(res['landmarks']['5']['established'])

    def test_timed_scenario_can_classify_more_than_100_hits(self):
        from trade_return_windows import attack_kind
        e=dict(score_before=[120,0],score_after=[121,0],target_before={'hurtResistantTime':9,'maxHurtResistantTime':18})
        self.assertEqual(attack_kind(e,{'scenario':True}),'fresh')
        self.assertEqual(attack_kind(e,{'scenario':False}),'after_winning_score')
        e['target_before']['hurtResistantTime']=10
        self.assertEqual(attack_kind(e,{'scenario':True}),'upgrade')


if __name__=='__main__':unittest.main()
