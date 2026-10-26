import unittest
import numpy as np
from evaluate import summarize
from delayed_compatibility import delayed_legacy,use_original_candidate,LOOKAHEAD_SAMPLES
from filters import apply,configs
class Evaluation(unittest.TestCase):
 def test_missing_and_duplicate_gates_fail(self):
  row=dict(id='a',method='x',family='x',group='speech',dataset='vacuum3m',segment=2,expected='a',text='a')
  with self.assertRaises(ValueError):summarize([row],{})
  with self.assertRaises(ValueError):summarize([row,row],{'a':True})
  self.assertEqual(summarize([row],{'a':False})[0]['admittedExact'],0)
 def test_causal_compatibility(self):
  x=np.random.default_rng(31).normal(0,.01,16000*7).astype(np.float32)
  old=apply(x,next(c for c in configs() if c['family']=='legacy_wiener'));new=delayed_legacy(x)
  self.assertTrue(np.array_equal(new[:round(3.04*16000)],x[:round(3.04*16000)]))
  self.assertTrue(np.array_equal(new[round(3.14*16000):],old[round(3.14*16000):]))
  for sec in [1.5,3.,5.]:
   end=round(sec*16000);prefix=delayed_legacy(x[:end])
   self.assertTrue(np.array_equal(prefix[:-LOOKAHEAD_SAMPLES],new[:end-LOOKAHEAD_SAMPLES]))
  self.assertTrue(use_original_candidate(0));self.assertFalse(use_original_candidate(16000*15))
if __name__=='__main__':unittest.main()
