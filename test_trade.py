"""Research regressions: classify native evidence without inventing hit events."""
import unittest
from unittest.mock import patch
import trade_causes
import trade_science
import trade_grounding
from trade_return_windows import attack_kind


def state(h=0):
    return dict(hurtResistantTime=h, sprint=True, posX=0, posY=4, posZ=0,
                box=[-.3,4,-.3,.3,5.8,.3])


def attack(t, who='A', h=0, accepted=True):
    return dict(type='science_server_attack', time=t, server_tick=t//50,
                attacker=who, target_before=state(h), attacker_before=state(),
                attacker_after=dict(state(), sprint=False),
                score_before=0, score_after=int(accepted))


def incoming(t):
    return dict(type='replication_server_input', time=t, player='A', packet_kind='in', action='ATTACK')


class TradeEvidenceTest(unittest.TestCase):
    def test_sumo_continues_scoring_after_one_hundred_hits(self):
        contact=dict(score_before=[100,70],score_after=[101,70],target_before=state())
        self.assertEqual(attack_kind(contact,{'mode':'sumo'}),'fresh')
        self.assertEqual(attack_kind(contact,{'mode':'boxing'}),'after_winning_score')

    def test_unscored_terminal_contact_is_not_called_immune(self):
        e=attack(100,h=0,accepted=False)
        e.update(score_before=[100,70],score_after=[100,70])
        self.assertEqual(trade_causes.classify(e),'after_winning_score')

    def test_same_server_tick_is_not_denial_and_tail_is_censored(self):
        a=dict(time=600,server_tick=12)
        b=dict(time=601,server_tick=12)
        self.assertEqual(trade_grounding.outcome(a,b,1000,100,0,850),'prompt_return')
        self.assertEqual(trade_grounding.outcome(a,None,650,100,150,850),'right_censored')
        self.assertEqual(trade_grounding.outcome(a,None,1000,100,150,850),'A_double_with_denial')

    def test_packet_payload_mismatch_fails_grounding_link_closed(self):
        packet=dict(kind='hm',entity_id=1,motionX=100,motionY=200,motionZ=0)
        server=[dict(type='replication_send',time=100,recipient='A',target='A',packet=packet)]
        native=[dict(type='replication_apply_after',time=110,packet_id=1,packet=dict(packet,motionX=101))]
        links,diagnostics=trade_grounding.link_side(server,native,dict(a='A',start=0),'a')
        self.assertEqual(links,{})
        self.assertIn('fifo_integer_payload_mismatch_at_0',diagnostics['issues'])

    def test_rejected_packet_does_not_shift_contact_fifo(self):
        raw=[incoming(110), incoming(210), attack(210), incoming(310), attack(310,h=18,accepted=False)]
        native=[]
        for seq,t in enumerate((100,200,300),11):
            native += [dict(type='engine_attack',time=t,click_attempt=seq),
                       dict(type='science_click',time=t,sequence=seq,tick=t//50,
                            miss_cooldown_before=0,selected_target=True,own=state(),target=state())]
        with patch.object(trade_causes,'server_events',return_value=raw), patch.object(trade_causes,'client_events',side_effect=lambda g,s:native if s=='a' else []):
            r=trade_causes.analyze(dict(id='test',a='A',b='B',start=0))['sides']['a']
        self.assertEqual([x['outcome'] for x in r['native_clicks']],['server_rejected','fresh','immune'])
        self.assertEqual(r['counts']['fifo_link_valid'],1)

    def test_upgrade_is_not_a_fresh_double_and_end_is_censored(self):
        raw=[attack(100),attack(150,'B'),attack(250,h=17),attack(600),
             attack(610,'B'),attack(1100),attack(1600)]
        with patch.object(trade_science,'server_events',return_value=raw), patch.object(trade_science,'client_events',return_value=[]):
            r=trade_science.analyze(dict(id='test',a='A',b='B',start=0,finish=1650))['sides']['a']
        self.assertEqual(r['counts']['fresh'],4)
        self.assertEqual(r['counts']['upgrades'],1)
        self.assertEqual(r['counts']['fresh_double_transitions'],1)
        self.assertEqual(r['counts']['fresh_double_return_delayed_150ms'],0)
        self.assertEqual(r['doubles'][0]['observed_after_second_ms'],50)

    def test_native_tick_w_release_ignores_wall_clock_jitter(self):
        native=[dict(type='wtap',time=100,cue_time=-50,native_tick=10),
                dict(type='science_move',time=99,tick=10,forward=0),
                dict(type='science_move',time=148,tick=11,forward=1)]
        with patch.object(trade_causes,'server_events',return_value=[]), patch.object(trade_causes,'client_events',side_effect=lambda g,s:native if s=='a' else []):
            r=trade_causes.analyze(dict(id='test',a='A',b='B',start=0))['sides']['a']
        self.assertEqual(r['release_tick_histogram'],{1:1})
        self.assertEqual(r['counts']['native_click_probe_available'],0)
        self.assertNotIn('reset_without_queued_click',r['counts'])


if __name__=='__main__':
    unittest.main()
