import unittest
from score import tokens,distance
class Scoring(unittest.TestCase):
 def test_only_case_and_punctuation_ignored(self):
  self.assertEqual(tokens('La fenêtre !'),tokens('la fenêtre.'))
  self.assertNotEqual(tokens('mes chaussures'),tokens('les chaussures'))
  self.assertNotEqual(tokens('fenêtre'),tokens('fenetre'))
 def test_empty_silence_is_separate_from_speech(self):
  self.assertEqual(distance([],[]),0)
  self.assertEqual(distance([],tokens('hum')),1)
  self.assertEqual(distance(tokens('a b c'),tokens('a c')),1)
if __name__=='__main__':unittest.main()
