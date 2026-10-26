"""Numerical checks using synthetic signals only; no private audio is read.

Run with ``PYTHONPATH=/workspace/voice-engine-tools/python python -m unittest
discover -s etude-filtres-classiques -p 'test_filters.py'`` from the repository.
"""

from __future__ import annotations

import importlib.util
import json
from pathlib import Path
import unittest

import numpy as np
from scipy.special import exp1


HERE = Path(__file__).resolve().parent


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


filters = load_module("classical_filters_under_test", HERE / "filters.py")


def synthetic_recording(seed=12, seconds=4.13):
    """A fixed noise-only initialization followed by a synthetic voiced tone."""
    rng = np.random.default_rng(seed)
    samples = round(seconds * 16000)
    x = rng.normal(0, 0.025, samples)
    first = min(samples, round(3.25 * 16000))
    t = np.arange(samples - first) / 16000
    x[first:] += 0.13 * np.sin(2 * np.pi * 330 * t)
    x[first:] += 0.04 * np.sin(2 * np.pi * 660 * t)
    return x.astype(np.float32)


class FilterNumerics(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.configurations = filters.configs()
        cls.by_name = {c["name"]: c for c in cls.configurations}

    def test_configuration_catalog_is_fixed_and_serializable(self):
        self.assertGreaterEqual(len(self.configurations), 20)
        self.assertLessEqual(len(self.configurations), 26)
        self.assertEqual(len(self.by_name), len(self.configurations))
        self.assertEqual(json.loads(json.dumps(self.configurations)), self.configurations)
        self.assertEqual(filters.configs(), self.configurations)
        self.assertIn("wiener-legacy-o1-f0.5", self.by_name)

    def test_stft_round_trip_including_short_odd_and_even_lengths(self):
        rng = np.random.default_rng(93)
        for n_fft in (512, 1024):
            for length in (0, 1, 2, 255, 256, 511, 512, 513, 16093, 16094):
                with self.subTest(n_fft=n_fft, length=length):
                    x = rng.normal(0, 0.2, length).astype(np.float32)
                    y = filters.istft(filters.stft(x, n_fft=n_fft), length, n_fft=n_fft)
                    self.assertEqual(y.shape, x.shape)
                    self.assertEqual(y.dtype, np.float32)
                    np.testing.assert_allclose(y, x, atol=1e-7, rtol=1e-6)

    def test_all_filters_handle_silence_empty_and_tiny_inputs(self):
        for c in self.configurations:
            for length in (0, 1, 17, 513, 48123):
                with self.subTest(config=c["name"], length=length):
                    x = np.zeros(length, dtype=np.float32)
                    y = filters.apply(x, c)
                    self.assertEqual(y.shape, x.shape)
                    self.assertEqual(y.dtype, np.float32)
                    self.assertTrue(np.isfinite(y).all())
                    np.testing.assert_array_equal(y, x)

    def test_reference_is_an_independent_exact_copy(self):
        reference = next(c for c in self.configurations if c["family"] == "reference")
        x = synthetic_recording(seconds=0.2)
        y = filters.apply(x, reference)
        np.testing.assert_array_equal(y, x)
        self.assertFalse(np.shares_memory(x, y))

    def test_noise_profile_uses_only_the_declared_initialization_window(self):
        # Startup transients before .5 s and subsequent speech after 3 s must
        # not contaminate the fixed initialization noise estimate.
        for n_fft in (512, 1024):
            with self.subTest(n_fft=n_fft):
                hop = n_fft // 2
                start = int(np.ceil(0.5 * 16000 / hop))
                end = round(3 * 16000 / hop)
                z = np.full((300, n_fft // 2 + 1), 99.0, dtype=np.complex128)
                z[:start] = 100
                z[start:end] = 2
                estimate = filters.noise_power(z, n_fft=n_fft)
                np.testing.assert_array_equal(estimate, np.full(n_fft // 2 + 1, 4.0))

    def test_contained_noise_profile_excludes_waveform_samples_outside_calibration(self):
        rng = np.random.default_rng(52)
        x = rng.normal(0, 0.03, 64000).astype(np.float32)
        changed = x.copy()
        changed[:8000] *= 10
        changed[48000:] *= 10
        for n_fft in (512, 1024):
            with self.subTest(n_fft=n_fft):
                baseline = filters.noise_power(
                    filters.stft(x, n_fft=n_fft), n_fft=n_fft, fully_contained=True,
                )
                modified = filters.noise_power(
                    filters.stft(changed, n_fft=n_fft), n_fft=n_fft, fully_contained=True,
                )
                np.testing.assert_array_equal(baseline, modified)

    def test_outputs_are_finite_deterministic_and_do_not_mutate_input(self):
        x = synthetic_recording()
        original = x.copy()
        for c in self.configurations:
            with self.subTest(config=c["name"]):
                y = filters.apply(x, c)
                self.assertEqual(y.shape, x.shape)
                self.assertEqual(y.dtype, np.float32)
                self.assertTrue(np.isfinite(y).all())
                np.testing.assert_array_equal(filters.apply(x, c), y)
                np.testing.assert_array_equal(x, original)

    def test_legacy_winner_matches_previous_implementation_exactly(self):
        previous = load_module(
            "previous_noise_study_reference",
            HERE.parent / "etude-bruit-elargie" / "prepare.py",
        )
        x = synthetic_recording(seed=24)
        expected = previous.spectral(x, "wiener", over=1.0, floor=0.5)
        actual = filters.apply(x, self.by_name["wiener-legacy-o1-f0.5"])
        np.testing.assert_array_equal(actual, expected)

    def test_masks_only_attenuate_and_are_finite(self):
        rng = np.random.default_rng(44)
        for c in self.configurations:
            if c["family"] == "reference":
                continue
            with self.subTest(config=c["name"]):
                bins = c.get("n_fft", 512) // 2 + 1
                noise = np.exp(rng.uniform(-12, 4, bins))
                power = noise * np.exp(rng.uniform(-20, 20, (240, bins)))
                gain = filters.gain_masks(power, noise, c)
                self.assertEqual(gain.shape, power.shape)
                self.assertTrue(np.isfinite(gain).all())
                self.assertGreaterEqual(float(gain.min()), 0.0)
                self.assertLessEqual(float(gain.max()), 1.0 + 1e-12)

    def test_mask_recursion_does_not_use_future_frames(self):
        # Supply one fixed initialization PSD to both runs. The prefix extends
        # beyond the initial noise-only profile, exercising adaptive updates.
        rng = np.random.default_rng(45)
        prefix_length = 260
        for c in self.configurations:
            if c["family"] == "reference":
                continue
            with self.subTest(config=c["name"]):
                bins = c.get("n_fft", 512) // 2 + 1
                noise = np.exp(rng.uniform(-4, 1, bins))
                power = noise * np.exp(rng.normal(0, 1, (420, bins)))
                short = filters.gain_masks(power[:prefix_length], noise, c)
                full = filters.gain_masks(power, noise, c)
                changed = power.copy()
                changed[prefix_length:] *= 1e6
                modified = filters.gain_masks(changed, noise, c)
                np.testing.assert_array_equal(short, full[:prefix_length])
                np.testing.assert_array_equal(short, modified[:prefix_length])

    def test_new_filters_pass_through_the_noise_initialization_audio(self):
        x = synthetic_recording(seed=20)
        for c in self.configurations:
            if c["family"] == "legacy_wiener":
                continue
            with self.subTest(config=c["name"]):
                np.testing.assert_array_equal(filters.apply(x[:8001], c), x[:8001])
                output = filters.apply(x, c)
                np.testing.assert_array_equal(output[:48000], x[:48000])

    def test_new_filter_waveform_prefix_is_invariant_with_declared_lookahead(self):
        rng = np.random.default_rng(46)
        x = synthetic_recording(seed=46, seconds=5.1)
        prefix_length = round(3.9 * 16000)
        changed = x.copy()
        changed[prefix_length:] = rng.normal(0, 2, len(x) - prefix_length)
        for c in self.configurations:
            if c["family"] == "legacy_wiener":
                continue
            with self.subTest(config=c["name"]):
                # The centered analysis and overlap-add require bounded future
                # input. Exclude the full declared window at the cut boundary.
                lookahead = c.get("lookahead_samples", c.get("n_fft", 512))
                safe_end = prefix_length - lookahead
                prefix = filters.apply(x[:prefix_length], c)
                full = filters.apply(x, c)
                modified = filters.apply(changed, c)
                np.testing.assert_array_equal(prefix[:safe_end], full[:safe_end])
                np.testing.assert_array_equal(prefix[:safe_end], modified[:safe_end])

    def test_initialized_smoothing_avoids_the_legacy_startup_fade(self):
        noise = np.ones(257)
        power = np.full((80, 257), 4.0)
        expected = np.sqrt(0.75)
        legacy = filters.gain_masks(power, noise, self.by_name["wiener-legacy-o1-f0.5"])
        initialized = filters.gain_masks(power, noise, self.by_name["wiener-sqrt-o1-f0.5"])
        np.testing.assert_allclose(legacy[0], 0.35 * expected, atol=1e-12, rtol=1e-12)
        np.testing.assert_allclose(initialized, expected, atol=1e-12, rtol=1e-12)
        np.testing.assert_allclose(legacy[-1], initialized[-1], atol=1e-12, rtol=1e-12)

    def test_decision_directed_gain_matches_the_prior_snr_recurrence(self):
        gamma = np.array([
            [4.0, 2.0, 8.0],
            [1.2, 5.0, 0.6],
            [0.1, 1.0, 20.0],
            [2.0, 1.3, 0.4],
        ])
        alpha = 0.9
        config = dict(self.by_name["dd-a0.98-f0.25"], alpha=alpha,
                      floor=0.0, freq_smooth=1, time_smooth=1.0)
        expected = np.empty_like(gamma)
        prior = gamma[0] - 1
        expected[0] = prior / (1 + prior)
        for t in range(1, len(gamma)):
            prior = (alpha * expected[t - 1] ** 2 * gamma[t - 1]
                     + (1 - alpha) * np.maximum(gamma[t] - 1, 0))
            expected[t] = prior / (1 + prior)
        actual = filters.gain_masks(gamma, np.ones(gamma.shape[1]), config)
        np.testing.assert_allclose(actual, expected, atol=1e-14, rtol=1e-14)

    def test_logmmse_gain_matches_the_conditional_ephraim_malah_formula(self):
        gamma = np.array([[0.01, 0.5, 1.0, 1.1, 2.0, 5.0, 100.0]])
        prior = np.maximum(gamma - 1, 1e-12)
        wiener = prior / (1 + prior)
        v = np.maximum(gamma * wiener, 1e-12)
        expected = np.minimum(1, wiener * np.exp(0.5 * exp1(v)))
        for floor in (0.0, 0.25):
            with self.subTest(floor=floor):
                config = dict(self.by_name["logmmse-a0.98-f0.25"], floor=floor,
                              freq_smooth=1, time_smooth=1.0)
                actual = filters.gain_masks(gamma, np.ones(gamma.shape[1]), config)
                np.testing.assert_allclose(actual, np.maximum(expected, floor),
                                           atol=1e-14, rtol=1e-13)

    def test_global_level_changes_preserve_relative_processing_without_clipping(self):
        # With a learned PSD, global level scaling must not change decisions.
        # The high-level case exceeds +/-1 and catches hidden audio clipping.
        x = synthetic_recording(seed=77)
        for c in self.configurations:
            with self.subTest(config=c["name"]):
                y = filters.apply(x, c)
                for scale in (0.001, 80.0):
                    scaled = filters.apply(x * np.float32(scale), c)
                    np.testing.assert_allclose(
                        scaled / scale, y, atol=2e-7, rtol=3e-5,
                        err_msg=f"Level dependence or clipping for {c['name']} at scale {scale}",
                    )

    def test_logmmse_is_stable_at_extreme_posterior_snrs(self):
        variants = [c for c in self.configurations if "logmmse" in c["family"]]
        self.assertTrue(variants)
        for c in variants:
            with self.subTest(config=c["name"]):
                bins = c.get("n_fft", 512) // 2 + 1
                # Include exactly zero, near-zero, and very high power. The
                # exponential integral has a singularity at zero argument.
                row = np.resize(np.array([0.0, 1e-30, 1e-12, 1, 1e12, 1e30]), bins)
                power = np.tile(row, (220, 1))
                gain = filters.gain_masks(power, np.ones(bins), c)
                self.assertTrue(np.isfinite(gain).all())
                self.assertTrue(((gain >= 0) & (gain <= 1)).all())


if __name__ == "__main__":
    unittest.main()
