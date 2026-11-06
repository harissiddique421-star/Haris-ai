package com.myraa.mobile;

import java.math.BigInteger;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Locale;

/** Pure-Java check of a per-phone activation key (no Android classes, so it is testable on a PC).
 *  A key is the owner's ECDSA P-256 signature over "FRIDAY-ACTIVATION-V1|" + the phone's device ID,
 *  written as base32. The app holds only the public key; keys can only be made with the owner's private key
 *  (scripts/friday-activation.mjs). A key made for one phone does not work on another. */
public final class ActivationCodec {
  public static final String MESSAGE_PREFIX = "FRIDAY-ACTIVATION-V1|";
  private static final String BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private ActivationCodec() {}

  /** Uppercase hex only, left-padded to 16 characters (Android's ANDROID_ID can drop leading zeros). */
  public static String normalizeDeviceId(String raw) {
    if (raw == null) return "";
    String hex = raw.toUpperCase(Locale.ROOT).replaceAll("[^0-9A-F]", "");
    if (hex.isEmpty()) return "";
    StringBuilder out = new StringBuilder(hex);
    while (out.length() < 16) out.insert(0, '0');
    return out.toString();
  }

  /** "A1B2C3D4E5F60718" → "A1B2-C3D4-E5F6-0718", the form shown to the user. */
  public static String groupDeviceId(String normalized) {
    StringBuilder out = new StringBuilder();
    for (int i = 0; i < normalized.length(); i++) { if (i > 0 && i % 4 == 0) out.append('-'); out.append(normalized.charAt(i)); }
    return out.toString();
  }

  /** Reads a public key from base64 SPKI DER, with or without PEM header lines. */
  public static PublicKey parsePublicKey(String text) throws Exception {
    String body = text.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
    if (body.isEmpty()) throw new IllegalArgumentException("No activation public key.");
    return KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(body)));
  }

  /** True only when the key is the owner's valid signature for exactly this device ID. Never throws. */
  public static boolean verify(PublicKey publicKey, String deviceId, String activationKey) {
    try {
      String device = normalizeDeviceId(deviceId);
      if (publicKey == null || device.isEmpty() || activationKey == null) return false;
      byte[] raw = base32Decode(activationKey);
      if (raw.length != 64) return false;
      Signature verifier = Signature.getInstance("SHA256withECDSA");
      verifier.initVerify(publicKey);
      verifier.update((MESSAGE_PREFIX + device).getBytes(StandardCharsets.UTF_8));
      return verifier.verify(toDer(raw));
    } catch (Exception e) { return false; }
  }

  /** Base32 (RFC 4648) without padding; spaces, dashes, '=' and letter case are ignored. */
  static byte[] base32Decode(String text) {
    String clean = text.toUpperCase(Locale.ROOT).replaceAll("[\\s=-]", "");
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    int buffer = 0, bits = 0;
    for (int i = 0; i < clean.length(); i++) {
      int value = BASE32.indexOf(clean.charAt(i));
      if (value < 0) throw new IllegalArgumentException("Not a base32 character.");
      buffer = (buffer << 5) | value; bits += 5;
      if (bits >= 8) { bits -= 8; out.write((buffer >> bits) & 0xFF); }
    }
    return out.toByteArray();
  }

  /** Converts a raw 64-byte r||s signature to the DER form Java's verifier expects. */
  private static byte[] toDer(byte[] raw) {
    byte[] r = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 0, 32)).toByteArray();
    byte[] s = new BigInteger(1, java.util.Arrays.copyOfRange(raw, 32, 64)).toByteArray();
    byte[] der = new byte[6 + r.length + s.length];
    der[0] = 0x30; der[1] = (byte) (4 + r.length + s.length);
    der[2] = 0x02; der[3] = (byte) r.length; System.arraycopy(r, 0, der, 4, r.length);
    der[4 + r.length] = 0x02; der[5 + r.length] = (byte) s.length; System.arraycopy(s, 0, der, 6 + r.length, s.length);
    return der;
  }
}
