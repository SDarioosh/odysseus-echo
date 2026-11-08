# "Hey Athena" wake word

The app bundles a custom microWakeWord model,
`app/src/main/assets/microwakeword/wakeWords/hey_athena.{tflite,json}`.

## Model

Trained locally with [microWakeWord](https://github.com/kahrendt/microWakeWord). Nothing was uploaded.

- **Positive clips:** 15,000 "hey athena" variants from Piper LibriTTS-R. Every 10th speaker (1,500 clips) is held out for testing.
- **Adversarial clips:** about 6,000 look-alike phrases, such as "hey anna", "hey tina", "pasadena", "hey athens" and "hey jarvis".
- **Augmentation:** MIT room impulse responses, AudioSet and FMA background audio.
- **Negative features:** the speech, dinner_party and no_speech sets from microWakeWord's published datasets.
- **Model:** MixedNet, the same architecture as the official v2 models. Streaming int8 TFLite, 62 KB, tensor arena 22,860.
- **Training:** 6,000 steps, then 44,000 more in three phases (learning rate 0.001, then 0.0003, then 0.0001; SpecAugment in phases 2 and 3). The best weights, from step 12,000 of the second run, were kept.
- **Training hardware:** GPU, with TensorFlow capped at 3 GB VRAM. Memory-mapped features were released with `MADV_PAGEOUT` so RAM stayed bounded.
- **Final streaming test:** at a cutoff of 0.92, it misses 6.5 % and false-wakes 0.19 times per hour. The full table is under [Threshold](#threshold).

**Retraining:** the scripts are in the owner's scratch folder (`gen_samples.sh`, `make_features.py`, `make_config.py`, `train.sh`, `train_gpu.py`). They're not in the repo because they depend on about 40 GB of local data.
The biggest improvement would come from adding real recordings of the household saying "hey athena". Keep those private, and never commit them.

## Threshold

VACA's global "wake word threshold" setting overrides `probability_cutoff` in the JSON. In HA, set
the device's wake word threshold to **9.2** (that is 0.92) for Hey Athena. The default of 6 (0.6)
gives about 1.5 false wakes per hour.

| Threshold | Missed "Hey Athena" (held-out voices) | False wakes per hour (5.3 h dinner-party test set) |
|---|---|---|
| 0.94 | 6.9 % | 0 |
| 0.92 | 6.5 % | 0.19 |
| 0.78 | 3.6 % | 0.38 |
| 0.59 | 3.1 % | 1.5 |

The app rounds the HA value to the nearest cutoff, so 9.2 means 0.92 (`utils/Helpers.kt`).
Upstream truncated (`Float.round` used `toInt`), so 9.2 became 0.91 (general fix, worth offering
upstream). Scores come in steps of 1/255, and 0.92 rejects the two false wakes seen in testing (a
cough and background speech), which both scored just under 0.92.
