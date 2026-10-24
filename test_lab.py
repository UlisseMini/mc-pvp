import asyncio
import hashlib
import json
import tempfile
import time
import unittest
from pathlib import Path
from unittest.mock import patch
import lab
from native_windows import overlap
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
        for path in (lab.RUNTIME/'bots').glob('*/events.jsonl'):
            last={}
            for line in path.read_text().splitlines():
                event=json.loads(line)
                if event['type']=='observation':
                    self.assertGreaterEqual(event['time']-event['observation_time'],150);samples+=1
                    if 'target_removed' in event:self.assertFalse(event['target_removed'])
                elif event['type']=='click':
                    # Legacy wall-clock logs cannot distinguish logger pauses from input spacing.
                    # Current runs record the monotonic clock used by the strict limiter.
                    previous=last.get(event['match'])
                    if 'monotonic_ns' in event:
                        if previous is not None and 'monotonic_ns' in previous:
                            self.assertGreaterEqual(event['monotonic_ns']-previous['monotonic_ns'],100_000_000)
                        strict_clicks+=1
                    last[event['match']]=event;clicks+=1
                elif event['type']=='wtap':
                    self.assertGreaterEqual(event['time']-event['cue_time'],150)
                    self.assertEqual(event['release_until']-event['time'],50);taps+=1
                elif event['type']=='engine_attack' and event['sprint_before'] and event['target_visible_hurt']>0 and abs(event['vx_before'])>.01:
                    self.assertAlmostEqual(event['vx_after'],event['vx_before']*.6,places=9)
                    self.assertFalse(event['sprint_after']);slowdowns+=1
        self.assertGreater(samples,100);self.assertGreater(clicks,100);self.assertGreater(slowdowns,10)
        self.assertGreater(strict_clicks,100);self.assertGreater(taps,10)


if __name__=='__main__':unittest.main()
