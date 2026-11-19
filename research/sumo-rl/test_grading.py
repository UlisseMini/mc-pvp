import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from grade_humans import ARMS, grade, register

class HumanGradeTest(unittest.TestCase):
    def test_counterbalance_and_first_eligible_games(self):
        with TemporaryDirectory() as tmp, patch('grade_humans.ROOT',Path(tmp)):
            root=Path(tmp)
            (root/'policies').mkdir()
            for arm in ARMS:(root/'policies'/(arm+'.properties')).write_text('test')
            build=root/'build/practice-client';build.mkdir(parents=True)
            (build/'practice-bridge.jar').write_bytes(b'test')
            p=register(['Example'],2,9201,0)
        order=p['schedule']['Example']
        self.assertCountEqual(order[:2],ARMS)
        self.assertEqual(order[:2],order[2:][::-1])
        def game(i,arm):
            return dict(id=f'm{i}',start=p['frozen_at_ms']+i,mode='sumo',a='Example',b='LabSlot1',
                policy_a='human',policy_b=arm,winner='LabSlot1',reason='ring_out',practice_preset='mmc',
                combat_profile={'knockback_model':'mmc_pre_attack_sprint_v2'},initial_ping_a=0,initial_ping_b=0,
                click_cps_b=20,sumo_arena={'diameter':10,'start_gap':6},attack_reach_a=3,attack_reach_b=3,
                reaction_ms_b=0,duration_limit_ms=0,client_b={'backend':'forge','bridge_sha256':p['bridge_sha256']},
                parameters_b=dict(kind='hold' if arm==ARMS[1] else 'wtap',sumo_jump_mask=5168 if arm==ARMS[1] else 0,
                     aim_delay_ms=0,aim_noise_deg=0,aim_turn_rate_dps=0))
        a,b=game(1,order[0]),game(2,order[1]);b['reason']='disconnect'
        result=grade(p,[b,a])['Example']
        self.assertFalse(result['complete'])
        self.assertEqual(result[order[0]]['win'],1)
        self.assertEqual(result[order[1]]['interrupted'],1)
        with self.assertRaises(ValueError):grade(p,[b])
