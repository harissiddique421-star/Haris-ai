package com.myraa.mobile;

import org.json.*;
import javax.net.ssl.HttpsURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Metadata-only credential checks and safe errors. Provider bodies never reach UI/logs. */
public final class GeminiAccess {
  public interface Connections { HttpsURLConnection open(URL url) throws IOException; }
  public interface ConnectionObserver { void opened(HttpsURLConnection connection); }
  private final Connections connections;
  public GeminiAccess() { this(url -> (HttpsURLConnection) url.openConnection()); }
  public GeminiAccess(Connections connections) { this.connections = connections; }

  public static final class Failure extends Exception {
    public final String code;
    public final int status;
    public Failure(String code, int status, String message) { super(message); this.code = code; this.status = status; }
  }

  public static Failure failure(int status, String payload) {
    if (status == 429) return new Failure("QUOTA_EXCEEDED", status, "Google's quota for this model is exhausted. Your key may still be valid. Wait for quota to reset or choose another model you can use.");
    if (status >= 500) return new Failure("PROVIDER_UNAVAILABLE", status, "Google is temporarily unavailable. Your key and conversation were kept. Try again later.");
    String reason = "", message = "";
    try {
      JSONObject error = new JSONObject(payload).optJSONObject("error");
      if (error != null) {
        message = error.optString("message");
        JSONArray details = error.optJSONArray("details");
        if (details != null) for (int i = 0; i < details.length(); i++) {
          JSONObject detail = details.optJSONObject(i);
          if (detail != null && !detail.optString("reason").isEmpty()) { reason = detail.optString("reason"); break; }
        }
      }
    } catch (JSONException ignored) {}
    Map<String,String> known = new LinkedHashMap<>();
    known.put("ACCOUNT_STATE_INVALID", "Google says the service account linked to this key is disabled or deleted. Create a key linked to an active service account in Google AI Studio, or have your project administrator restore it.");
    known.put("CONSUMER_SUSPENDED", "Google says this key's project or consumer is suspended. Resolve its status in Google AI Studio or Google Cloud, then check the key again. Changing MYRAA's model cannot repair a suspended project.");
    known.put("API_KEY_INVALID", "Google says this API key is invalid. Create a replacement in Google AI Studio and enter it only in the key field.");
    known.put("API_KEY_EXPIRED", "Google says this API key has expired. Create a replacement in Google AI Studio.");
    known.put("API_KEY_SERVICE_BLOCKED", "This key is not allowed to use the Gemini API. Check its API restrictions and linked project in Google Cloud.");
    known.put("SERVICE_DISABLED", "The Gemini API is disabled for this key's project. Enable the Generative Language API in Google Cloud.");
    known.put("API_KEY_HTTP_REFERRER_BLOCKED", "This key's website restrictions do not permit this Android app. Review its application restrictions in Google Cloud.");
    known.put("API_KEY_IP_ADDRESS_BLOCKED", "Google blocked this key because of its allowed IP addresses. Review its application restrictions in Google Cloud.");
    known.put("API_KEY_ANDROID_APP_BLOCKED", "This key's Android application restrictions do not match MYRAA's package and signing certificate. Review them in Google Cloud.");
    for (Map.Entry<String,String> entry : known.entrySet()) {
      if (entry.getKey().equals(reason) || message.contains(entry.getKey())) return new Failure(entry.getKey(), status, entry.getValue());
    }
    String lower = message.toLowerCase(Locale.ROOT);
    if (lower.contains("leaked") || lower.contains("exposed")) return new Failure("API_KEY_LEAKED", status, "Google blocked this key as exposed. Replace it in Google AI Studio and paste the new key only into MYRAA's key field, never into chat.");
    if (lower.contains("api key not valid")) return new Failure("API_KEY_INVALID", status, known.get("API_KEY_INVALID"));
    if (status == 404) return new Failure("MODEL_UNAVAILABLE", status, "Google could not find this model for your key. Check the model identifier in Settings or choose a model available to your project.");
    if (status == 401 || status == 403) return new Failure("GOOGLE_ACCESS_DENIED", status, "Google denied this key or project access. Check its project, linked service account and API restrictions in Google AI Studio or Google Cloud.");
    return new Failure("GOOGLE_HTTP_ERROR", status, "Google returned HTTP " + status + ". Your key and conversation were kept.");
  }

  public void verify(String key, String model, ConnectionObserver observer) throws Exception {
    if (key == null || !key.matches("[A-Za-z0-9._-]{20,300}")) throw new Failure("KEY_FORMAT_INVALID", 0, "Enter a complete Gemini API key without spaces. Both AQ and AIza key formats are supported.");
    if (model == null || !model.matches("[A-Za-z0-9._-]{1,100}")) throw new Failure("MODEL_FORMAT_INVALID", 0, "Enter a valid Gemini model identifier.");
    HttpsURLConnection connection = null;
    try {
      connection = connections.open(new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model));
      observer.opened(connection);
      connection.setInstanceFollowRedirects(false); connection.setRequestMethod("GET");
      connection.setConnectTimeout(10000); connection.setReadTimeout(12000);
      connection.setRequestProperty("Accept", "application/json");
      connection.setRequestProperty("x-goog-api-key", key);
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) throw failure(status, read(connection.getErrorStream(), 65536));
      JSONObject metadata = new JSONObject(read(connection.getInputStream(), 65536));
      JSONArray methods = metadata.optJSONArray("supportedGenerationMethods");
      boolean supported = false;
      if (methods != null) for (int i = 0; i < methods.length(); i++) if ("generateContent".equals(methods.optString(i))) supported = true;
      if (!supported) throw new Failure("MODEL_CHAT_UNSUPPORTED", 200, "This model does not support this app's text chat API. Choose a Gemini text model with generateContent support.");
    } finally { if (connection != null) connection.disconnect(); observer.opened(null); }
  }

  /** Mint a single-use Live-only credential. The durable key never enters WebView. */
  public String createLiveToken(String key, ConnectionObserver observer) throws Exception {
    HttpsURLConnection connection = null;
    try {
      connection = connections.open(new URL("https://generativelanguage.googleapis.com/v1beta/auth_tokens"));
      observer.opened(connection);
      connection.setInstanceFollowRedirects(false); connection.setRequestMethod("POST");
      connection.setConnectTimeout(10000); connection.setReadTimeout(12000); connection.setDoOutput(true);
      connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("x-goog-api-key", key);
      java.text.SimpleDateFormat iso = new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
      iso.setTimeZone(TimeZone.getTimeZone("UTC")); long now = System.currentTimeMillis();
      JSONObject body = new JSONObject().put("uses", 1).put("expireTime", iso.format(new Date(now + 30*60*1000)))
        .put("newSessionExpireTime", iso.format(new Date(now + 60*1000)));
      try (OutputStream out = connection.getOutputStream()) { out.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
      int status = connection.getResponseCode();
      if (status < 200 || status >= 300) throw failure(status, read(connection.getErrorStream(), 65536));
      String token = new JSONObject(read(connection.getInputStream(), 65536)).optString("name");
      if (!token.startsWith("auth_tokens/") || token.length() > 8192) throw new IOException("Invalid Live token response.");
      return token;
    } finally { if (connection != null) connection.disconnect(); observer.opened(null); }
  }

  private static String read(InputStream input, int limit) throws IOException {
    if (input == null) return "";
    try (InputStream source = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[4096]; int count;
      while ((count = source.read(buffer)) != -1) {
        if (out.size() + count > limit) throw new IOException("Response exceeds the size limit.");
        out.write(buffer, 0, count);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
  }
}
