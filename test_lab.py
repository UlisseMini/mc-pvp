import asyncio
import hashlib
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
import lab
from native_windows import overlap,sustained_combo
from lab_runtime import latency_proxy


class NativeWindowMetricTest(unittest.TestCase):
    def test_conditional_time_ratio_uses_attacker_hittable_time(self):
        a=[{'time':t,'in_range':v} for t,v in [(0,True),(100,True),(200,False),(300,False)]]
        b=[{'time':t,'in_range':v} for t,v in [(0,False),(100,True),(200,False),(300,False)]]
        result=overlap(a,b,0,300)
        self.assertEqual(result['paired_time_ms'],300)
        self.assertEqual(result['a']['attacker_can_hit_ms'],200)
        self.assertEqual(result['a']['attacker_can_hit_defender_cannot_ms'],100)
        self.assertEqual(result['a']['one_sided_fraction_given_attacker_can_hit'],.5)

    def test_missing_client_samples_do_not_count_as_denial(self):
        a=[{'time':0,'in_range':True},{'time':1000,'in_range':True}]
        b=[{'time':0,'in_range':False},{'time':1000,'in_range':False}]
        self.assertEqual(overlap(a,b,0,1000)['paired_time_ms'],150)

    def test_independent_timestamps_are_integrated_by_duration(self):
        a=[{'time':0,'in_range':True},{'time':120,'in_range':False}]
        b=[{'time':20,'in_range':False},{'time':50,'in_range':True},{'time':100,'in_range':False}]
        result=overlap(a,b,0,140)
        self.assertEqual(result['paired_time_ms'],120)
        self.assertEqual(result['a']['attacker_can_hit_ms'],100)
        self.assertEqual(result['a']['attacker_can_hit_defender_cannot_ms'],50)
        self.assertEqual(result['durations_ms']['neither'],20)

    def test_denial_duration_allows_attacker_temporarily_out_of_range(self):
        a=[{'time':t,'in_range':v} for t,v in [(0,True),(100,False),(200,True),(300,False)]]
        b=[{'time':t,'in_range':False} for t in (0,100,200,300)]
        result=overlap(a,b,0,300,include_windows=True)
        self.assertEqual(result['a']['longest_one_sided_window_ms'],100)
        self.assertEqual(result['a']['longest_defender_denied_ms'],300)
        self.assertEqual(len(result['a']['defender_denial_windows']),1)

    def test_combo_requires_regular_fresh_contacts_and_no_return(self):
        windows=[{'start':0,'end':5000}]
        contacts=[100,650,1200,3000,3550,4100,4650]
        self.assertEqual(sustained_combo(windows,contacts,[])['duration_ms'],1650)
        self.assertEqual(sustained_combo(windows,contacts,[4000])['duration_ms'],1100)
        self.assertEqual(sustained_combo(windows,[100,650],[])['duration_ms'],0)

    def test_uncapped_sequence_preserves_denial_across_slow_contacts(self):
        windows=[{'start':0,'end':6000}]
        contacts=[100,1600,3100,4600]
        self.assertEqual(sustained_combo(windows,contacts,[])['duration_ms'],0)
        sequence=sustained_combo(windows,contacts,[],max_gap_ms=None)
        self.assertEqual(sequence['duration_ms'],4500)
        self.assertEqual(sequence['fresh_contacts'],4)
        self.assertEqual(sequence['max_contact_gap_ms'],1500)
        self.assertEqual(sustained_combo(windows,contacts,[2500],max_gap_ms=None)['duration_ms'],0)
        split=[{'start':0,'end':2500},{'start':2600,'end':6000}]
        self.assertEqual(sustained_combo(split,contacts,[],max_gap_ms=None)['duration_ms'],0)


class SupplyChainTest(unittest.TestCase):
    def test_modified_locked_artifact_is_rejected_without_network(self):
        directory=lab.RUNTIME/'tmp';directory.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=directory)as folder:
            path=Path(folder)/'artifact.jar';path.write_bytes(b'modified')
            key=str(path.relative_to(lab.ROOT));url='https://invalid.example/no-network'
            with patch.dict(lab.LOCK,{key:{'url':url,'sha256':hashlib.sha256(b'original').hexdigest()}}):
                with self.assertRaisesRegex(RuntimeError,'Hash mismatch'):
                    lab.download(url,path)


class NetworkTest(unittest.IsolatedAsyncioTestCase):
    async def test_delay_preserves_order_without_accumulating_per_packet(self):
        async def echo(reader,writer):
            try:
                while data:=await reader.read(4096):writer.write(data);await writer.drain()
            finally:writer.close()
        upstream=await asyncio.start_server(echo,'127.0.0.1',0)
        directory=lab.RUNTIME/'tmp';directory.mkdir(parents=True,exist_ok=True)
        with tempfile.TemporaryDirectory(dir=directory)as folder:
            config=Path(folder)/'latency.properties';config.write_text('rtt_ms=80\n')
            proxy=await latency_proxy(0,config,upstream.sockets[0].getsockname()[1])
            reader,writer=await asyncio.open_connection('127.0.0.1',proxy.sockets[0].getsockname()[1])
            sent=[];received=[]
            async def write():
                for i in range(10):sent.append(time.monotonic());writer.write(bytes([i]));await writer.drain();await asyncio.sleep(.01)
            async def read():
                for i in range(10):
                    self.assertEqual(await asyncio.wait_for(reader.readexactly(1),2),bytes([i]));received.append(time.monotonic())
            await asyncio.gather(write(),read())
            writer.close();await writer.wait_closed();proxy.close();upstream.close()
            await proxy.wait_closed();await upstream.wait_closed()
            self.assertGreaterEqual(min(r-s for s,r in zip(sent,received)),.075)
            self.assertLess(received[-1]-received[0],.25)


class RealEngineEvidenceTest(unittest.TestCase):
    def test_logged_controls_respect_reaction_delay_and_click_cap(self):
        samples=clicks=slowdowns=strict_clicks=taps=0
        # Historical cohorts retain their 150 ms requirement; newer cohorts
        # explicitly record the user's chosen zero-delay observation rule.
        delays={};rates={}
        for result in (lab.RUNTIME/'results').glob('*.json'):
            game=json.loads(result.read_text())
            for side in ('a','b'):
                if side in game:
                    delays[game['id'],game[side]]=int(game.get('parameters_'+side,{}).get(
                        'reaction_ms',game.get('reaction_ms_'+side,150)))
                    rates[game['id'],game[side]]=int(game.get('parameters_'+side,{}).get('click_cps',10))
        for path in (lab.RUNTIME/'bots').glob('*/events.jsonl'):
            last={}
            player='LabSlot'+path.parent.name if path.parent.name.isdigit() else path.parent.name
            for line in path.read_text().splitlines():
                event=json.loads(line)
                # Validate the completed-match snapshot above. Live trials can
                # append zero-delay inputs while this historical audit runs;
                # without their frozen result, assuming 150 ms is incorrect.
                if (event.get('match'),player) not in delays:continue
                delay=delays.get((event.get('match'),player),150)
                cps=rates.get((event.get('match'),player),10)
                if event['type']=='observation':
                    self.assertGreaterEqual(event['time']-event['observation_time'],delay);samples+=1
                    if 'target_removed' in event:self.assertFalse(event['target_removed'])
                elif event['type']=='click':
                    # Legacy wall-clock logs cannot distinguish logger pauses from input spacing.
                    # Current runs record the monotonic clock used by the strict limiter.
                    previous=last.get(event['match'])
                    if 'monotonic_ns' in event:
                        if previous is not None and 'monotonic_ns' in previous:
                            self.assertGreaterEqual(event['monotonic_ns']-previous['monotonic_ns'],(1_000_000_000+cps-1)//cps)
                        strict_clicks+=1
                    last[event['match']]=event;clicks+=1
                elif event['type']=='wtap':
                    self.assertGreaterEqual(event['time']-event['cue_time'],delay)
                    self.assertGreater(event['release_until'],event['time']);taps+=1
                elif event['type']=='engine_attack' and event['sprint_before'] and event['target_visible_hurt']>0 and abs(event['vx_before'])>.01:
                    self.assertAlmostEqual(event['vx_after'],event['vx_before']*.6,places=9)
                    self.assertFalse(event['sprint_after']);slowdowns+=1
        self.assertGreater(samples,100);self.assertGreater(clicks,100);self.assertGreater(slowdowns,10)
        self.assertGreater(strict_clicks,100);self.assertGreater(taps,10)


if __name__=='__main__':unittest.main()
