package com.myraa.mobile;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.SecureRandom;

/** Per-phone activation. Until this phone has a valid key from the owner, FRIDAY shows only this screen.
 *  The key is checked again on every launch, so copying the app's data to another phone does not unlock it. */
final class Activation {
  private static final String PREFS = "friday.activation.v1", KEY = "key", FALLBACK_ID = "fallbackDeviceId";
  private Activation() {}

  private static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

  /** Android's per-device ID for this app. It stays the same after reinstalling, so the same key works again. */
  static String deviceId(Context c) {
    String id = ActivationCodec.normalizeDeviceId(Settings.Secure.getString(c.getContentResolver(), Settings.Secure.ANDROID_ID));
    if (!id.isEmpty()) return id;
    // Very rare: no ANDROID_ID. Use a random ID kept for this install.
    String saved = prefs(c).getString(FALLBACK_ID, "");
    if (saved.isEmpty()) {
      byte[] random = new byte[8]; new SecureRandom().nextBytes(random);
      StringBuilder hex = new StringBuilder(); for (byte b : random) hex.append(String.format("%02X", b));
      saved = hex.toString(); prefs(c).edit().putString(FALLBACK_ID, saved).apply();
    }
    return saved;
  }

  private static PublicKey publicKey(Context c) {
    try (java.io.InputStream in = c.getAssets().open("activation_public_key.txt")) {
      byte[] buffer = new byte[4096]; int total = 0, read;
      while (total < buffer.length && (read = in.read(buffer, total, buffer.length - total)) > 0) total += read;
      return ActivationCodec.parsePublicKey(new String(buffer, 0, total, StandardCharsets.UTF_8));
    } catch (Exception e) { return null; }
  }

  static boolean isActivated(Context c) {
    String saved = prefs(c).getString(KEY, null);
    return saved != null && ActivationCodec.verify(publicKey(c), deviceId(c), saved);
  }

  /** Shows the activation screen. onActivated runs once a valid key has been saved. */
  static void showGate(Activity a, Runnable onActivated) {
    int bg = Color.rgb(16,19,23), card = Color.rgb(28,33,40), text = Color.WHITE, muted = Color.rgb(170,178,189), accent = Color.rgb(0,200,255);
    a.getWindow().setStatusBarColor(bg); a.getWindow().setNavigationBarColor(bg);
    float dp = a.getResources().getDisplayMetrics().density;
    int pad = (int) (20 * dp);
    String device = deviceId(a), shown = ActivationCodec.groupDeviceId(device);
    boolean keyBundled = publicKey(a) != null;

    LinearLayout box = new LinearLayout(a); box.setOrientation(LinearLayout.VERTICAL); box.setPadding(pad, pad * 2, pad, pad); box.setBackgroundColor(bg);
    TextView title = label(a, "FRIDAY activation", 24, text); title.setTypeface(Typeface.DEFAULT_BOLD); box.addView(title);
    box.addView(label(a, "Is phone par FRIDAY chalane ke liye activation key chahiye. Har phone ki key alag hoti hai.\n\n"
      + "1. Neeche wala Device ID copy karke app ke owner ko bhejein.\n2. Owner aap ko is phone ki key dega.\n3. Key yahan paste karke Activate dabayein.", 15, muted));

    TextView idLabel = label(a, "Device ID", 13, muted); idLabel.setPadding(0, (int) (18 * dp), 0, (int) (4 * dp)); box.addView(idLabel);
    TextView id = label(a, shown, 22, accent); id.setTypeface(Typeface.MONOSPACE, Typeface.BOLD); id.setTextIsSelectable(true);
    id.setBackgroundColor(card); id.setPadding(pad / 2, pad / 2, pad / 2, pad / 2); id.setGravity(Gravity.CENTER); box.addView(id);
    Button copy = button(a, "Device ID copy karein"); box.addView(copy);

    TextView keyLabel = label(a, "Activation key", 13, muted); keyLabel.setPadding(0, (int) (18 * dp), 0, (int) (4 * dp)); box.addView(keyLabel);
    EditText input = new EditText(a); input.setHint("Yahan key paste karein"); input.setHintTextColor(Color.rgb(110,118,129)); input.setTextColor(text);
    input.setTypeface(Typeface.MONOSPACE); input.setTextSize(14); input.setBackgroundColor(card); input.setPadding(pad / 2, pad / 2, pad / 2, pad / 2);
    input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
    input.setMinLines(4); input.setGravity(Gravity.TOP | Gravity.START); box.addView(input, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    Button paste = button(a, "Paste"); box.addView(paste);
    Button activate = button(a, "Activate"); activate.setBackgroundColor(accent); activate.setTextColor(Color.BLACK); box.addView(activate);
    TextView status = label(a, keyBundled ? "" : "Is APK mein owner ki public key nahi hai, is liye koi key kaam nahi karegi. Owner ko naya APK banana hoga.", 14, Color.rgb(255,120,120));
    status.setPadding(0, (int) (12 * dp), 0, 0); box.addView(status);

    copy.setOnClickListener(v -> {
      ClipboardManager clip = a.getSystemService(ClipboardManager.class);
      if (clip != null) { clip.setPrimaryClip(ClipData.newPlainText("FRIDAY device ID", shown)); status.setTextColor(muted); status.setText("Device ID copy ho gaya: " + shown); }
    });
    paste.setOnClickListener(v -> {
      ClipboardManager clip = a.getSystemService(ClipboardManager.class);
      ClipData data = clip == null ? null : clip.getPrimaryClip();
      if (data != null && data.getItemCount() > 0 && data.getItemAt(0).coerceToText(a) != null) input.setText(data.getItemAt(0).coerceToText(a).toString().trim());
    });
    activate.setOnClickListener(v -> {
      String key = input.getText().toString().trim();
      if (key.isEmpty()) { status.setTextColor(Color.rgb(255,120,120)); status.setText("Pehle activation key paste karein."); return; }
      if (ActivationCodec.verify(publicKey(a), device, key)) {
        prefs(a).edit().putString(KEY, key).commit();
        status.setTextColor(Color.rgb(120,230,150)); status.setText("Activate ho gaya. FRIDAY khul raha hai...");
        v.setEnabled(false); v.postDelayed(onActivated, 600);
      } else { status.setTextColor(Color.rgb(255,120,120)); status.setText("Yeh key is phone ke liye sahi nahi hai. Check karein ke owner ne isi Device ID (" + shown + ") ki key banayi hai."); }
    });

    ScrollView scroll = new ScrollView(a); scroll.setBackgroundColor(bg); scroll.setFillViewport(true); scroll.addView(box);
    a.setContentView(scroll);
  }

  private static TextView label(Context c, String value, int sp, int color) {
    TextView t = new TextView(c); t.setText(value); t.setTextSize(sp); t.setTextColor(color); t.setPadding(0, 0, 0, (int) (8 * c.getResources().getDisplayMetrics().density)); return t;
  }
  private static Button button(Context c, String value) {
    Button b = new Button(c); b.setText(value); b.setAllCaps(false);
    LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    lp.topMargin = (int) (8 * c.getResources().getDisplayMetrics().density); b.setLayoutParams(lp); b.setVisibility(View.VISIBLE); return b;
  }
}
