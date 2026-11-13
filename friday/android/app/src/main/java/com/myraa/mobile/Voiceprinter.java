package com.myraa.mobile;

/**
 * A speaker model: turns 16 kHz mono speech (floats in -1..1) into a voiceprint compared by cosine
 * similarity. Two are built in: GE2E (VoiceEncoder, plain Java, always there) and NeMo TitaNet small
 * (TitaNet, through sherpa-onnx, when its native library loads on the phone). Scores from different
 * models are on different scales, so every saved voice remembers which model made it (model()).
 */
interface Voiceprinter {
  /** Model names saved with each voice. */
  String GE2E="ge2e-resemblyzer",TITANET="titanet-small";
  /** Stable name saved with each voice profile. */
  String model();
  /** L2-normalised voiceprint, or null when there is too little audio. */
  float[] embed(float[] wav);
}
