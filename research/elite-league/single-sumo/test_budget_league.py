"""Regression checks for the strict adversary gate, opponent reuse, and the paired 7-CPS schedule."""
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import adversary
from learner import CLOCKS, training_schedule
from successor_pool import OUT, read


class BudgetLeague(unittest.TestCase):
    def test_exact_boundary_and_draws(self):
        self.assertEqual(adversary.UPDATES*64,65536)
        for wins,games,expected in ((60,100,False),(61,100,True),(307,512,False),(308,512,True)):
            self.assertEqual(adversary.passes(wins,games),expected)
        with self.assertRaises(ValueError):adversary.passes(1,0)

    def test_closed_target_is_skipped_even_under_a_new_alias(self):
        field={n:{'sha256':v} for n,v in [('old','a'),('old-alias','a'),('next','b')]}
        with tempfile.TemporaryDirectory() as scratch,patch.object(adversary,'STORE',Path(scratch)),patch.object(adversary,'import_originals'):
            path=Path(scratch)/'a/result.json'
            path.parent.mkdir();path.write_text('{"confirmed":false}')
            self.assertEqual(adversary.choose(field,list(field)),'next')

    def test_same_mixture_and_fixtures(self):
        mlp,gru=[read(OUT/r/'config.json') for r in ('mlp','gru')]
        for step in (0,1,2,16,511):
            rows=training_schedule(mlp,'actor',step)
            self.assertEqual(rows,training_schedule(gru,'actor',step))
            counts=[sum(r[1] in mlp['families'][k] for r in rows) for k in ('counters','anchors','archive')]
            self.assertEqual(counts,[32,16,16])
            self.assertTrue(all(r[2] in CLOCKS['train'] and not {31,109}.intersection(r[4:6]) for r in rows))


if __name__=='__main__':unittest.main()
