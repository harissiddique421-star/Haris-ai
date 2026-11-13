package com.myraa.mobile;

/** An ear for agentic mode: AgentEar (Android's speech recognizer) or GatedEar (Voice ID, only the owner). */
interface Ear {
  void start(String lang);
  void stop();
  /** Pause while FRIDAY speaks; resume when she is done. */
  void pause(boolean p);
  boolean active();
}
