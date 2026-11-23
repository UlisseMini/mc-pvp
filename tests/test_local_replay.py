"""Native-fixture regressions for unreset, boundary-conditioned trajectory replay."""
import copy
import subprocess
import tempfile
import unittest

from lab import ROOT, RUNTIME, JAVA, JAVAC
from tools.local_replay import query, compare
from tools.trade_grounding import read


class LocalReplayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temp=tempfile.TemporaryDirectory(prefix='replay-test-',dir=RUNTIME/'tmp')
        cls.addClassCleanup(cls.temp.cleanup)
        subprocess.run([str(JAVAC),'-proc:none','-d',cls.temp.name,
            str(ROOT/'src/lab/ExactTravel.java'),str(ROOT/'tests/ExactLocalReplay.java')],
            check=True,capture_output=True,timeout=30)
        try:
            server,_=read('m0404','server');native,_=read('m0404','a')
        except FileNotFoundError:
            raise unittest.SkipTest('Archived m0404 trace not present locally')
        game=next(e['match'] for e in server if e['type']=='start');cls.start=game['start']
        cls.own_id=next(e['packet']['entity_id'] for e in server if e['type']=='replication_send'
            and e['packet']['kind']=='hm' and e['target']==game['a'])
        cls.native=[e for e in native if 12801<=e['time']-cls.start<=13250]

    def replay(self,native):
        queries,refs=query(native,self.own_id)
        run=subprocess.run([str(JAVA),'-cp',self.temp.name,'ExactLocalReplay'],
            input='\n'.join(queries)+'\n',text=True,capture_output=True,check=True,timeout=10)
        return compare(run.stdout.splitlines(),refs,self.start)

    def test_native_window_is_bit_exact_without_position_or_motion_corrections(self):
        result=self.replay(self.native)
        self.assertTrue(result['passed'],result)
        self.assertEqual(result['exact_prefix_movement_steps'],9)

    def test_later_measured_position_motion_and_box_cannot_correct_replay(self):
        altered=copy.deepcopy(self.native);seen=False
        for e in sorted(altered,key=lambda e:e.get('monotonic_ns',0)):
            if e['type']=='science_move':
                if seen:
                    for k in ('posX','posY','posZ','motionX','motionY','motionZ'):e['before'][k]=12345
                    e['before']['box']=[0]*6
                seen=True
        original,_=query(self.native,self.own_id);changed,_=query(altered,self.own_id)
        self.assertEqual(changed,original)
        self.assertTrue(self.replay(altered)['passed'])

    def test_one_wrong_packet_unit_does_not_get_repaired_by_next_native_sample(self):
        altered=copy.deepcopy(self.native)
        packet=next(e for e in altered if e['type']=='replication_apply_after' and e['packet']['kind']=='hm')
        packet['packet']['motionX']+=1
        result=self.replay(altered)
        self.assertFalse(result['passed'])
        self.assertEqual(result['first_failure']['kind'],'s12')
        self.assertEqual(result['exact_prefix_movement_steps'],1)
        self.assertGreater(result['failures']['posX'],2)

    def test_watcher_velocity_never_overwrites_local_motion(self):
        altered=copy.deepcopy(self.native)
        packet=copy.deepcopy(next(e for e in altered if e['type']=='replication_apply_after' and e['packet']['kind']=='hm'))
        packet['packet']['entity_id']=self.own_id+10000;packet['packet']['motionX']=30000
        packet['monotonic_ns']+=1;altered.append(packet)
        original,_=query(self.native,self.own_id);changed,_=query(altered,self.own_id)
        self.assertEqual(changed,original)


if __name__=='__main__':unittest.main()
