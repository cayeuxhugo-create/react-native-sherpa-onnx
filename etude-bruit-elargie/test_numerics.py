"""Numerical checks independent of the private speech corpus."""
import unittest
import numpy as np
from prepare import stft,istft,spectral,multichannel
from policies import lcs
class Numerics(unittest.TestCase):
 def test_reconstruction(self):
  rng=np.random.default_rng(41)
  for n in [16000,16193,128000]:
   x=rng.normal(0,.1,n).astype(np.float32)
   self.assertLess(float(np.max(abs(istft(stft(x),n)-x))),1e-6)
 def test_silence_and_identical_channels(self):
  rng=np.random.default_rng(17);x=rng.normal(0,.01,64000).astype(np.float32)
  for a in [x,np.zeros_like(x)]:
   for kind in ['mvdr','mwf']:
    y=multichannel(np.stack([a,a],axis=1),kind)
    self.assertEqual(len(y),len(a));self.assertTrue(np.isfinite(y).all())
   y=spectral(a,'wiener',over=2,floor=.5)
   self.assertEqual(len(y),len(a));self.assertTrue(np.isfinite(y).all())
 def test_ordered_scoring(self):
  self.assertEqual(lcs(['a','b','c'],['noise','a','c']),2)
  self.assertEqual(lcs(['a','b'],['b','a']),1)
if __name__=='__main__':unittest.main()
