# "Hey Athena" wake-word model

Trained locally with [microWakeWord](https://github.com/kahrendt/microWakeWord). Nothing was uploaded.

- **Positive clips:** 15,000 "hey athena" variants from Piper LibriTTS-R. Every 10th speaker (1,500 clips) is held out for testing.
- **Adversarial clips:** about 6,000 look-alike phrases, such as "hey anna", "hey tina", "pasadena", "hey athens" and "hey jarvis".
- **Augmentation:** MIT room impulse responses, AudioSet and FMA background audio.
- **Negative features:** the speech, dinner_party and no_speech sets from microWakeWord's published datasets.
- **Model:** MixedNet, the same architecture as the official v2 models. Streaming int8 TFLite, 62 KB, tensor arena 22,860.
- **Training:** 6,000 steps, then 44,000 more in three phases (learning rate 0.001, then 0.0003, then 0.0001; SpecAugment in phases 2 and 3). The best weights, from step 12,000 of the second run, were kept.
- **Training hardware:** GPU, with TensorFlow capped at 3 GB VRAM. Memory-mapped features were released with `MADV_PAGEOUT` so RAM stayed bounded.
- **Final streaming test:** at a cutoff of 0.92, it misses 6.5 % and false-wakes 0.19 times per hour. The full ROC is in `echo-show-5.md`.

**Retraining:** the scripts are in the owner's scratch folder (`gen_samples.sh`, `make_features.py`, `make_config.py`, `train.sh`, `train_gpu.py`). They're not in the repo because they depend on about 40 GB of local data.
The biggest improvement would come from adding real recordings of the household saying "hey athena". Keep those private, and never commit them.
