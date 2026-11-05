# MYRAA private RC4

Includes local desktop character controls and saved foot-contact walking fixes.
Use Drag to enable mouse interaction, or Sit / Stand / arrows / Wave / Stop.
Exact typed character commands work without a Gemini key.

Android RC2 validates key/model access before replacing encrypted settings and
reports suspension, invalid credentials, quota and unavailable models separately.
The owner must resolve Google's CONSUMER_SUSPENDED project state or enter a key
from an active project directly in app settings. Chat-exposed keys must be revoked.

Validation: 198 suite passes, one skip; desktop real-window 14 passes with zero
cloud chat requests; Android isolated instrumentation 29 passes. No live provider
success or production-release certification is claimed. Packaging verification
is recorded separately under artifacts/rc4-*.json. Earlier RC3 remains unchanged.

Open: new full secondary-physics matrix, installer upgrade, portable-wrapper
execution, mixed DPI, long soak and physical Android audio/battery tests.
