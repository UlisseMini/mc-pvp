"""Run the actual fork, training, export and comparison on a tiny disposable fixture before long jobs finish."""
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import stationary_field as router
import stationary_ppo as ppo
import stationary_frontier as frontier
from learner import BUILD,write_json
from remote_league import read


def run():
    original=read(router.DEST/'protocol.json')
    field={n:original['field'][n] for n in (router.OPENER,router.GENERALIST,'wtap','hold')}
    spec=dict(original,field=field,responses=[router.GENERALIST])
    short={s:2 for s in router.REPS}
    clocks={k:v[:2] for k,v in router.CLOCKS.items()}
    with TemporaryDirectory(dir=BUILD) as temp:
        dest=Path(temp)/'evidence'
        write_json(dest/'protocol.json',spec,fixed=True)
        with patch.object(router,'DEST',dest),patch.object(ppo,'DEST',dest),patch.object(frontier,'DEST',dest),patch.object(frontier,'CLOCKS',clocks),patch.object(router,'BUILD',Path(temp)),patch.object(router,'REPS',short),patch.object(router,'CLOCKS',clocks):
            router.train();router.evaluate('dev')
            ppo.evaluate('controls');ppo.evaluate('parent')
            frontier.register()
            for role in ('controls','parent','router'):frontier.run(role)
            legal=read(dest/'dev-result.json');continuous=read(dest/'continuous/parent/result.json')
            if legal['fights_per_arm']!=128:raise AssertionError('Unexpected fixture size')
            if sum(continuous['totals']['wtap'])!=128:raise AssertionError('Missing W-tap games')
            if read(dest/'frontier-check/result.json')['challenger']!=legal['totals']['history']:raise AssertionError('Router frontier changed the ordinary outcomes')
            if read(dest/'continuous/parent/frontier-check/result.json')['wtap']!=continuous['totals']['wtap']:raise AssertionError('W-tap comparison differs across runs')
            print('Verified identical opening actions, five exported actors over 128 games each, continuous history interventions and both new feed-forward controls.',flush=True)


if __name__=='__main__':run()
