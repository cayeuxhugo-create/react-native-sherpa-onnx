"""Fixed, single-channel classical denoisers for 16 kHz recordings.

All noise profiles use the protocol's speech-free initial 0.5..3 seconds.
That initialization is an explicit experiment assumption, not a speech detector:
an utterance-only file is not a valid input for comparing these configurations.
For short numerical probes, the available profile frames (or all frames when
the profile would be empty) are used. No clean reference, transcript, device
threshold, normalization, or per-utterance parameter selection is used.

The centered Hann STFT has reflect-padded boundaries; a conservative bound on
analysis plus overlap-add look-ahead is a full window (32 or 64 ms). Except for
the explicitly noncausal legacy comparator, output passes through unchanged
until the 3 s noise calibration is ready, followed by a 100 ms crossfade.
Once that profile is fixed, gain and adaptive-noise recursions use current and
past frames only. These are offline study helpers, not zero-latency streaming.
Gains are bounded by one; samples are neither normalized nor clipped. A varying
spectral mask can still increase individual time-domain peaks through phase
cancellation changes, so peak non-amplification is not a mathematical promise.
"""

from functools import lru_cache

import numpy as np
from scipy.ndimage import uniform_filter1d
from scipy.signal import lfilter
from scipy.special import exp1


SAMPLE_RATE = 16000
_EPS = 1e-18
_MAX_SNR = 1e12
_PROTOCOL = "Initial 0.5..3 s must contain noise only; one fixed config per recording."


@lru_cache(maxsize=2)
def _window(n_fft):
    if n_fft not in (512, 1024):
        raise ValueError("n_fft must be 512 or 1024")
    # Float32 matches the previously evaluated 512-point implementation exactly.
    return (.5 - .5 * np.cos(2 * np.pi * np.arange(n_fft) / n_fft)).astype(np.float32)


def stft(x, n_fft=512):
    """Periodic Hann, 50% overlap, centered reflect padding; empty input allowed."""
    window = _window(n_fft)
    x = np.asarray(x)
    if x.ndim != 1:
        raise ValueError("Expected one-dimensional audio")
    if not np.isfinite(x).all():
        raise ValueError("Audio must be finite")
    if len(x) == 0:
        return np.empty((0, n_fft // 2 + 1), dtype=np.complex128)
    padded = np.pad(x, (n_fft // 2, n_fft), mode="reflect")
    frames = np.lib.stride_tricks.sliding_window_view(padded, n_fft)[::n_fft // 2]
    return np.fft.rfft(frames * window, axis=1)


def istft(z, n, n_fft=512):
    """Overlap-add inverse of :func:`stft`, with its exact output length."""
    window = _window(n_fft)
    z = np.asarray(z)
    if n < 0 or z.ndim != 2 or z.shape[1] != n_fft // 2 + 1:
        raise ValueError("Invalid STFT shape or output length")
    if n == 0:
        return np.empty(0, dtype=np.float32)
    if len(z) == 0:
        raise ValueError("No frames for nonempty audio")
    hop = n_fft // 2
    frames = np.fft.irfft(z, n=n_fft, axis=1) * window
    y = np.zeros((len(z) - 1) * hop + n_fft)
    weight = np.zeros_like(y)
    for i, frame in enumerate(frames):
        section = slice(i * hop, i * hop + n_fft)
        y[section] += frame
        weight[section] += window ** 2
    section = slice(hop, hop + n)
    if hop + n > len(y):
        raise ValueError("Insufficient STFT frames for output length")
    return (y[section] / np.maximum(weight[section], 1e-12)).astype(np.float32)


def noise_power(z, seconds=3, n_fft=512, start_seconds=.5, sample_rate=SAMPLE_RATE,
                fully_contained=False):
    """Mean profile power, optionally using only fully contained STFT frames.

    The default preserves old 512-point center-frame indices 32:188. New
    production-plausible configurations require complete frame support within
    the calibration interval, so their profile contains no samples after 3 s.
    """
    if not 0 <= start_seconds < seconds:
        raise ValueError("Noise profile requires 0 <= start_seconds < seconds")
    z = np.asarray(z)
    if z.ndim != 2 or z.shape[1] != n_fft // 2 + 1:
        raise ValueError("Invalid STFT shape")
    if not len(z):
        return np.full(z.shape[1], _EPS)
    hop = n_fft // 2
    if fully_contained:
        start = int(np.ceil(start_seconds * sample_rate / hop + 1))
        stop = min(len(z), int(np.floor(seconds * sample_rate / hop - 1)) + 1)
    else:
        start = int(np.ceil(start_seconds * sample_rate / hop))
        stop = min(len(z), round(seconds * sample_rate / hop))
    profile = z[start:stop] if start < stop else z
    return np.maximum(np.mean(np.abs(profile) ** 2, axis=0), _EPS)


def _ratio(power, noise):
    with np.errstate(over="ignore", divide="ignore", invalid="ignore"):
        ratio = power / np.maximum(noise, _EPS)
    return np.clip(np.nan_to_num(ratio, nan=0, posinf=_MAX_SNR), 1e-12, _MAX_SNR)


def _ema_first(values, rate):
    """Causal EMA whose state starts at the first observed value, not zero."""
    if not len(values) or rate == 1:
        return values.copy()
    initial = (1 - rate) * values[0]
    return lfilter([rate], [1, -(1 - rate)], values, axis=0,
                   zi=initial[np.newaxis, ...])[0]


def _posterior_gains(gamma, algorithm, config, previous=None):
    """Analytical, unsmoothed gains; DD state uses the previous spectral gain."""
    over = config.get("over", 1.)
    if algorithm in ("wiener_sqrt", "legacy_wiener"):
        return np.sqrt(np.maximum(0., 1 - over / gamma))
    if algorithm == "wiener":
        return np.maximum(0., 1 - over / gamma)
    if algorithm == "subtract":
        exponent = config.get("exponent", 1.)
        floor_power = config.get("floor", .35) ** exponent
        residual = 1 - over / gamma ** (exponent / 2)
        softness = config.get("softness", .04)
        # Smooth maximum of the residual and the generalized gain floor.
        clean = floor_power + softness * np.logaddexp(0., (residual - floor_power) / softness)
        return np.minimum(1., clean ** (1 / exponent))
    if algorithm not in ("dd", "logmmse"):
        raise ValueError(f"Unknown gain algorithm: {algorithm}")
    if previous is None:
        prior = np.maximum(gamma - 1., 0.)
    else:
        last_gamma, last_gain = previous
        alpha = config.get("alpha", .98)
        prior = alpha * last_gain ** 2 * last_gamma + (1 - alpha) * np.maximum(gamma - 1., 0.)
    prior = np.clip(prior, 1e-12, _MAX_SNR)
    wiener = prior / (1 + prior)
    if algorithm == "dd":
        return wiener
    # Ephraim-Malah log-spectral amplitude MMSE estimator, conditional on
    # speech presence. Work in log space and cap the actual gain at unity.
    v = np.maximum(gamma * wiener, 1e-12)
    log_gain = np.log(wiener) + .5 * exp1(v)
    return np.exp(np.minimum(log_gain, 0.))


def _speech_band(config, bins):
    frequencies = np.arange(bins) * config.get("sample_rate", SAMPLE_RATE) / config.get("n_fft", 512)
    low, high = config.get("speech_band", (250., 4000.))
    return (frequencies >= low) & (frequencies <= high)


def gain_masks(power, noise, config):
    """Compute bounded gains with a supplied, fixed initial noise profile.

    This helper exposes frame-domain causality independently of centered-STFT
    boundary effects. Adaptive estimators update noise *after* evaluating the
    current gain, and only after the profile interval. Their posterior-based
    speech-presence weighting is approximate, not MCRA/OM-LSA or a neural VAD.
    """
    input_power = np.asarray(power)
    input_noise = np.asarray(noise)
    power = np.asarray(power, dtype=np.float64)
    noise = np.asarray(noise, dtype=np.float64)
    if power.ndim != 2 or noise.shape != (power.shape[1],):
        raise ValueError("Expected power[frames, bins] and noise[bins]")
    if not np.isfinite(power).all() or not np.isfinite(noise).all() or np.any(power < 0) or np.any(noise < 0):
        raise ValueError("Power and noise must be nonnegative and finite")
    family = config["family"]
    if family == "reference":
        return np.ones_like(power)
    if not len(power):
        return np.empty_like(power)
    floor = config.get("floor", .5)
    rate = config.get("time_smooth", .35)
    width = config.get("freq_smooth", 3)
    if not 0 <= floor <= 1 or not 0 < rate <= 1 or width < 1:
        raise ValueError("Invalid floor or smoothing parameter")
    if family == "legacy_wiener":
        # Preserve the old NumPy dtype and operation ordering, including its
        # float32 intermediate mask on NumPy versions with complex64 rFFT.
        posterior = input_power / input_noise
        gains = np.sqrt(np.maximum(0, 1 - config.get("over", 1.) / np.maximum(posterior, 1e-8)))
        gains = uniform_filter1d(np.maximum(gains, floor), size=width, axis=1, mode="nearest")
        return lfilter([rate], [1, -(1 - rate)], gains, axis=0)
    algorithm = {
        "adaptive_wiener": "wiener", "adaptive_dd": "dd",
        "adaptive_logmmse": "logmmse", "protected_wiener": "wiener",
        "bypass_wiener": "wiener", "bypass_dd": "dd",
    }.get(family, family)
    adaptive = family.startswith("adaptive_")
    recursive = adaptive or algorithm in ("dd", "logmmse")
    if not recursive:
        gamma = _ratio(power, noise)
        gains = _posterior_gains(gamma, algorithm, config)
    else:
        gains = np.empty_like(power)
        gamma = np.empty_like(power)
        estimate = np.maximum(noise.copy(), _EPS)
        smooth_posterior = np.ones_like(estimate)
        previous = None
        profile_end = round(config.get("noise_seconds", 3) * config.get("sample_rate", SAMPLE_RATE)
                            / (config.get("n_fft", 512) // 2))
        for t, row in enumerate(power):
            posterior = _ratio(row, estimate)
            gamma[t] = posterior
            current = _posterior_gains(posterior, algorithm, config, previous)
            current = np.clip(current, floor, 1.)
            gains[t] = current
            previous = posterior, current
            if adaptive and t >= profile_end:
                # A bounded, smoothed posterior SNR gives continuous speech
                # presence weights. Long speech can still bias this simple
                # estimator; the initial profile remains the calibrated base.
                smooth_posterior = .8 * smooth_posterior + .2 * posterior
                local = uniform_filter1d(smooth_posterior, size=3, mode="nearest")
                presence = np.clip((local - 1.5) / 4.5, 0., .98)
                base = config.get("noise_alpha", .98)
                retention = base + (1 - base) * presence
                # Limit the influence of isolated large speech excursions.
                observation = np.minimum(row, 3 * estimate)
                estimate = np.maximum(retention * estimate + (1 - retention) * observation, _EPS)
    gains = np.clip(gains, floor, 1.)
    if family == "protected_wiener":
        # Keep a milder floor in the fixed articulation band; this is frequency
        # protection, not a speech detector and not a clean-speech oracle.
        band = _speech_band(config, power.shape[1])
        gains[:, band] = np.maximum(gains[:, band], config.get("protected_floor", .7))
    gains = uniform_filter1d(gains, size=width, axis=1, mode="nearest")
    if family == "legacy_wiener":
        gains = lfilter([rate], [1, -(1 - rate)], gains, axis=0)
    else:
        gains = _ema_first(gains, rate)
    if family.startswith("bypass_"):
        band = _speech_band(config, power.shape[1])
        # A relative, spectrum-derived SNR makes the bypass scale independent.
        noisy_energy = np.sum(power[:, band], axis=1)
        noise_energy = max(float(np.sum(noise[band])), _EPS)
        snr = np.maximum(noisy_energy / noise_energy - 1., 1e-12)
        snr_db = 10 * np.log10(snr)
        low = config.get("bypass_low_db", 0.)
        high = config.get("bypass_high_db", 12.)
        if high <= low:
            raise ValueError("Bypass high SNR must exceed low SNR")
        confidence = np.clip((snr_db - low) / (high - low), 0., 1.)
        confidence = _ema_first(confidence * confidence * (3 - 2 * confidence), .2)
        gains += confidence[:, None] * (1 - gains)
    return np.clip(gains, 0., 1.)


def apply(x, config):
    """Apply one globally fixed configuration, returning finite float32 audio."""
    x = np.asarray(x, dtype=np.float32)
    if x.ndim != 1 or not np.isfinite(x).all():
        raise ValueError("Expected finite, one-dimensional audio")
    if config["family"] == "reference" or len(x) == 0:
        return x.copy()
    if config.get("sample_rate", SAMPLE_RATE) != SAMPLE_RATE:
        raise ValueError("Study configurations require 16 kHz audio")
    n_fft = config.get("n_fft", 512)
    legacy = config["family"] == "legacy_wiener"
    startup = 0 if legacy else round(config.get("startup_passthrough_seconds", 3) * SAMPLE_RATE)
    if len(x) <= startup:
        return x.copy()
    spectrum = stft(x, n_fft)
    noise = noise_power(spectrum, seconds=config.get("noise_seconds", 3), n_fft=n_fft,
                        start_seconds=config.get("noise_start_seconds", .5),
                        fully_contained=not legacy)
    gain = gain_masks(np.abs(spectrum) ** 2, noise, config)
    y = istft(spectrum * gain, len(x), n_fft)
    if startup:
        y[:startup] = x[:startup]
        fade_samples = round(config.get("startup_fade_seconds", .1) * SAMPLE_RATE)
        count = min(max(0, len(x) - startup), fade_samples)
        if count:
            # Fixed-duration raised-cosine crossfade; never stretched to match
            # a truncated input, preserving prefix invariance at file ends.
            blend = (.5 - .5 * np.cos(np.pi * np.arange(count) / max(fade_samples - 1, 1))).astype(np.float32)
            section = slice(startup, startup + count)
            y[section] = x[section] + blend * (y[section] - x[section])
    if not np.isfinite(y).all():
        raise FloatingPointError("Denoiser produced non-finite audio")
    return y


def configs():
    """26 predeclared study choices; no phrase- or recording-specific tuning."""
    choices = [dict(name="original", family="reference", notes="Unmodified mono reference.")]
    common = dict(sample_rate=SAMPLE_RATE, n_fft=512, noise_start_seconds=.5,
                  noise_seconds=3, freq_smooth=3, time_smooth=.35,
                  floor=.5, over=1., protocol=_PROTOCOL,
                  startup_passthrough_seconds=3., startup_fade_seconds=.1)

    def add(name, family, notes, **parameters):
        config = dict(common, name=name, family=family, notes=notes, **parameters)
        config["lookahead_samples"] = config["n_fft"]
        choices.append(config)

    add("wiener-legacy-o1-f0.5", "legacy_wiener",
        "Exact previous winner, explicitly noncausal startup: future initial profile, sqrt power subtraction, 3-bin mean, zero-initialized .35 EMA.",
        startup_passthrough_seconds=0., startup_fade_seconds=0.)
    add("wiener-sqrt-o1-f0.5", "wiener_sqrt", "Previous gain formula; EMA initialized at first gain.")
    add("wiener-sqrt-o0.5-f0.5", "wiener_sqrt", "Milder noise subtraction.", over=.5)
    add("wiener-sqrt-o1-f0.25", "wiener_sqrt", "Lower amplitude floor.", floor=.25)
    add("wiener-sqrt-o1.5-f0.5", "wiener_sqrt", "Stronger noise subtraction.", over=1.5)
    add("wiener-power-o1-f0.5", "wiener", "Standard power-Wiener gain max(1-noise/power,0).")
    add("wiener-power-o0.5-f0.5", "wiener", "Standard power-Wiener gain with milder subtraction.", over=.5)
    add("wiener-power-o1-f0.25", "wiener", "Standard power-Wiener gain with lower floor.", floor=.25)
    add("wiener-power-o1-f0.7", "wiener", "Standard power-Wiener gain with mild attenuation.", floor=.7)
    add("wiener-power-o1-f0.5-n1024", "wiener", "Longer 64 ms window, 32 ms hop.", n_fft=1024)
    add("dd-a0.92-f0.25", "dd", "Decision-directed a-priori SNR; previous unsmoothed spectral gain.", alpha=.92, floor=.25)
    add("dd-a0.98-f0.25", "dd", "Slower decision-directed a-priori SNR.", alpha=.98, floor=.25)
    add("dd-a0.98-f0.5", "dd", "Decision-directed Wiener with conservative floor.", alpha=.98)
    add("dd-a0.98-f0.5-n1024", "dd", "Decision-directed Wiener with 64 ms window.", alpha=.98, n_fft=1024)
    add("logmmse-a0.98-f0.25", "logmmse", "Ephraim-Malah log-MMSE, conditional speech presence; gain capped at one.", alpha=.98, floor=.25)
    add("logmmse-a0.98-f0.5", "logmmse", "Log-MMSE with conservative floor.", alpha=.98)
    add("logmmse-a0.92-f0.5", "logmmse", "Log-MMSE with faster prior-SNR adaptation.", alpha=.92)
    add("subtract-p1-o1-f0.35", "subtract", "Magnitude subtraction with differentiable soft gain floor.", exponent=1., floor=.35, softness=.04)
    add("subtract-p1.5-o1.3-f0.35", "subtract", "Generalized 1.5-power subtraction with differentiable soft gain floor.", exponent=1.5, over=1.3, floor=.35, softness=.04)
    add("adaptive-wiener-f0.5", "adaptive_wiener", "Approximate posterior speech-presence weighted noise updates after initialization.", noise_alpha=.98)
    add("adaptive-dd-a0.98-f0.5", "adaptive_dd", "Approximate adaptive noise plus decision-directed Wiener.", alpha=.98, noise_alpha=.98)
    add("adaptive-logmmse-a0.98-f0.5", "adaptive_logmmse", "Approximate adaptive noise plus conditional speech-present log-MMSE.", alpha=.98, noise_alpha=.98)
    add("protected-wiener-f0.35-band0.7", "protected_wiener", "Gain floor .7 in fixed 250..4000 Hz articulation band.", floor=.35, protected_floor=.7, speech_band=[250., 4000.])
    add("bypass-wiener-f0.35-snr0-12", "bypass_wiener", "Continuous soft bypass for estimated band SNR 0..12 dB; no absolute level threshold.", floor=.35, speech_band=[250., 4000.], bypass_low_db=0., bypass_high_db=12.)
    add("bypass-dd-f0.35-snr0-12", "bypass_dd", "Decision-directed gain with continuous relative-SNR soft bypass.", alpha=.98, floor=.35, speech_band=[250., 4000.], bypass_low_db=0., bypass_high_db=12.)
    return choices
