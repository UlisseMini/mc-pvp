"""Compare all nine public observation channels against both deployed Java implementations."""
import os
from pathlib import Path
import subprocess
from tempfile import TemporaryDirectory

import numpy as np

from adaptive_router import JAVA8,runtime
from learner import BUILD,ROOT
from remote_league import read
from series_trace import CHANNELS,LIB
from series_trace_native import local_features


def verify():
    pin=runtime();fixture=BUILD/'native-trace.tsv'
    model=ROOT/read(LIB/'protocol.json')['experts']['original-mixture']['reference']
    from expanded_native import native_rows
    from opponent_motion import KEEP
    chunks=fixture.read_text().split('#')[1:]
    batches=[[[float(x) for x in row.split()[:14]] for row in chunk.splitlines()[1:]] for chunk in chunks]
    expected=np.concatenate([native_rows(rows) for rows in batches])
    initial=np.stack([local_features(row) for rows in batches for row in rows])
    np.testing.assert_array_equal(expected[:,:len(CHANNELS)],initial)
    with TemporaryDirectory(dir=BUILD) as scratch:
        temp=Path(scratch)
        subprocess.run(['javac','--release','8','-proc:none','-d',str(temp),'-cp',str(BUILD/pin['sim']),
                        str(Path(__file__).with_name('LeagueTrace.java'))],check=True)
        for java,cp in [(JAVA8,BUILD/pin['forge']['jar']),('java',BUILD/pin['sim'])]:
            output=subprocess.check_output([java,'-XX:+UseSerialGC','-cp',os.pathsep.join(map(str,[temp,cp])),
                                            'LeagueTrace',model,fixture,','.join(map(str,KEEP))])
            actual=np.frombuffer(output,dtype='>f4').reshape(expected.shape)
            np.testing.assert_array_equal(actual,expected)
    print(f'{len(expected)} decisions × {len(KEEP)} identical float32 features in Forge, simulator and native reader')


if __name__=='__main__':verify()
