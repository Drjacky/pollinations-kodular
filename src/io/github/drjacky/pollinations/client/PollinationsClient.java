package io.github.drjacky.pollinations.client;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Plain-Java HTTP client for the Pollinations API. It has no Android imports so it can be unit
 * tested on a normal JVM. The App Inventor component calls it from a background thread.
 *
 * Java 7 syntax only: the App Inventor extension toolchain compiles with -source 7.
 */
public class PollinationsClient {

  public static final String DEFAULT_API_BASE = "https://gen.pollinations.ai";
  public static final String DEFAULT_AUTH_BASE = "https://enter.pollinations.ai";
  public static final String USER_AGENT = "pollinations-kodular/1.0";

  private String apiBase = DEFAULT_API_BASE;
  private String authBase = DEFAULT_AUTH_BASE;
  private int connectTimeoutMs = 30000;
  private int readTimeoutMs = 180000;

  public void setApiBase(String apiBase) {
    this.apiBase = stripSlash(apiBase);
  }

  public void setAuthBase(String authBase) {
    this.authBase = stripSlash(authBase);
  }

  public String getApiBase() {
    return apiBase;
  }

  public void setTimeouts(int connectMs, int readMs) {
    this.connectTimeoutMs = connectMs;
    this.readTimeoutMs = readMs;
  }

  // ---------------------------------------------------------------------------------------------
  // Errors and responses

  /** Thrown for any non-2xx answer. The message is the API's own error text when there is one. */
  public static class ApiException extends IOException {
    public final int status;
    public final String code;

    public ApiException(int status, String code, String message) {
      super(message);
      this.status = status;
      this.code = code;
    }
  }

  /** Raw HTTP answer. */
  public static class Response {
    public final int status;
    public final String contentType;
    public final byte[] body;

    Response(int status, String contentType, byte[] body) {
      this.status = status;
      this.contentType = contentType == null ? "" : contentType;
      this.body = body;
    }

    public String text() {
      try {
        return new String(body, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        return new String(body);
      }
    }

    public boolean ok() {
      return status >= 200 && status < 300;
    }
  }

  /**
   * Turns an error body into a short human message. Handles the gateway's
   * {"error":{"message":...}}, OAuth-style {"error":"...","error_description":...}
   * and plain text bodies.
   */
  public static ApiException toApiException(Response r) {
    String text = r.text();
    String code = "";
    String message = null;
    try {
      JSONObject o = new JSONObject(text);
      Object err = o.opt("error");
      if (err instanceof JSONObject) {
        JSONObject e = (JSONObject) err;
        message = e.optString("message", null);
        code = e.optString("code", "");
      } else if (err instanceof String) {
        code = (String) err;
        message = o.optString("error_description", null);
        if (message == null || message.length() == 0) {
          message = code;
        }
      }
      if (message == null) {
        message = o.optString("message", null);
      }
    } catch (JSONException ignored) {
      // not JSON
    }
    if (message == null || message.length() == 0) {
      message = text.length() > 300 ? text.substring(0, 300) : text;
    }
    if (message.length() == 0) {
      message = "HTTP " + r.status;
    }
    return new ApiException(r.status, code, "HTTP " + r.status + ": " + message);
  }

  // ---------------------------------------------------------------------------------------------
  // Low level HTTP

  public Response request(String method, String url, String apiKey, String contentType,
      byte[] body) throws IOException {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    try {
      c.setRequestMethod(method);
      c.setConnectTimeout(connectTimeoutMs);
      c.setReadTimeout(readTimeoutMs);
      c.setInstanceFollowRedirects(true);
      c.setRequestProperty("User-Agent", USER_AGENT);
      c.setRequestProperty("Accept", "*/*");
      if (apiKey != null && apiKey.length() > 0) {
        c.setRequestProperty("Authorization", "Bearer " + apiKey);
      }
      if (body != null) {
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", contentType);
        c.setFixedLengthStreamingMode(body.length);
        OutputStream os = c.getOutputStream();
        try {
          os.write(body);
        } finally {
          os.close();
        }
      }
      int status = c.getResponseCode();
      InputStream is = status >= 400 ? c.getErrorStream() : c.getInputStream();
      byte[] data = is == null ? new byte[0] : readAll(is);
      return new Response(status, c.getContentType(), data);
    } finally {
      c.disconnect();
    }
  }

  private Response checked(Response r) throws ApiException {
    if (!r.ok()) {
      throw toApiException(r);
    }
    return r;
  }

  private JSONObject postJson(String url, String apiKey, JSONObject payload) throws IOException {
    Response r = checked(request("POST", url, apiKey, "application/json", utf8(payload.toString())));
    return parseObject(r.text());
  }

  // ---------------------------------------------------------------------------------------------
  // Device flow (Bring Your Own Pollen)

  public static class DeviceCode {
    public String deviceCode;
    public String userCode;
    public String verificationUri;
    public String verificationUriComplete;
    public int expiresIn;
    public int interval;
  }

  /** Step 1: ask enter.pollinations.ai for a device code. appKey is the optional pk_ key. */
  public DeviceCode requestDeviceCode(String appKey) throws IOException {
    JSONObject payload = new JSONObject();
    try {
      if (appKey != null && appKey.length() > 0) {
        payload.put("client_id", appKey);
      }
    } catch (JSONException e) {
      throw new IOException(e.getMessage());
    }
    JSONObject o = postJson(authBase + "/api/device/code", null, payload);
    DeviceCode d = new DeviceCode();
    d.deviceCode = o.optString("device_code", "");
    d.userCode = o.optString("user_code", "");
    d.verificationUri = o.optString("verification_uri", authBase + "/device");
    d.verificationUriComplete = o.optString("verification_uri_complete", d.verificationUri);
    d.expiresIn = o.optInt("expires_in", 1800);
    d.interval = Math.max(1, o.optInt("interval", 5));
    if (d.deviceCode.length() == 0) {
      throw new IOException("No device_code in response");
    }
    return d;
  }

  /** Result of one poll of the token endpoint. */
  public static class TokenPoll {
    /** "ok", "authorization_pending", "slow_down", "expired_token", "access_denied", ... */
    public String state;
    public String accessToken;
    public String message;
  }

  /** Step 3: poll once. Never throws for the normal OAuth "pending" answers. */
  public TokenPoll pollDeviceToken(String deviceCode) throws IOException {
    JSONObject payload = new JSONObject();
    try {
      payload.put("device_code", deviceCode);
    } catch (JSONException e) {
      throw new IOException(e.getMessage());
    }
    Response r = request("POST", authBase + "/api/device/token", null, "application/json",
        utf8(payload.toString()));
    TokenPoll p = new TokenPoll();
    JSONObject o = null;
    try {
      o = new JSONObject(r.text());
    } catch (JSONException ignored) {
      // handled below
    }
    if (r.ok() && o != null && o.optString("access_token", "").length() > 0) {
      p.state = "ok";
      p.accessToken = o.optString("access_token");
      return p;
    }
    if (o != null && o.opt("error") instanceof String) {
      p.state = o.optString("error");
      p.message = o.optString("error_description", p.state);
      return p;
    }
    throw toApiException(r);
  }

  /** Sleeps between polls. Overridable in tests. */
  public interface Sleeper {
    void sleep(long ms) throws InterruptedException;
  }

  /** Callback for the full device flow. */
  public interface DeviceFlowListener {
    boolean isCancelled();
  }

  /**
   * Polls until the user approves, the code expires, or the listener cancels. Honors the server
   * interval and backs off by 5 seconds on slow_down, as RFC 8628 asks.
   *
   * @return the user's sk_ key
   */
  public String waitForDeviceToken(DeviceCode code, Sleeper sleeper, DeviceFlowListener listener)
      throws IOException, InterruptedException {
    long intervalMs = code.interval * 1000L;
    long deadline = System.currentTimeMillis() + code.expiresIn * 1000L;
    while (true) {
      if (listener != null && listener.isCancelled()) {
        throw new InterruptedException("Sign-in cancelled");
      }
      if (System.currentTimeMillis() > deadline) {
        throw new ApiException(400, "expired_token", "The sign-in code expired. Try again.");
      }
      sleeper.sleep(intervalMs);
      if (listener != null && listener.isCancelled()) {
        throw new InterruptedException("Sign-in cancelled");
      }
      TokenPoll p = pollDeviceToken(code.deviceCode);
      if ("ok".equals(p.state)) {
        return p.accessToken;
      } else if ("authorization_pending".equals(p.state)) {
        continue;
      } else if ("slow_down".equals(p.state)) {
        intervalMs += 5000L;
      } else {
        throw new ApiException(400, p.state, p.message == null ? p.state : p.message);
      }
    }
  }

  /** Returns {username, userId} for a key. */
  public String[] userInfo(String apiKey) throws IOException {
    JSONObject o = parseObject(checked(request("GET", authBase + "/api/device/userinfo", apiKey,
        null, null)).text());
    return new String[] {o.optString("preferred_username", o.optString("name", "")),
        o.optString("sub", "")};
  }

  /** Pollen left for this key. */
  public double balance(String apiKey) throws IOException {
    JSONObject o = parseObject(checked(request("GET", apiBase + "/account/balance", apiKey, null,
        null)).text());
    return o.optDouble("balance", 0);
  }

  // ---------------------------------------------------------------------------------------------
  // Text and vision

  /** Builds an OpenAI-style chat request. history holds earlier messages (may be null). */
  public static JSONObject buildChatRequest(String model, String systemPrompt, JSONArray history,
      String prompt, String imageDataUrl, double temperature) throws JSONException {
    JSONObject req = new JSONObject();
    if (model != null && model.length() > 0) {
      req.put("model", model);
    }
    JSONArray messages = new JSONArray();
    if (systemPrompt != null && systemPrompt.length() > 0) {
      messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
    }
    if (history != null) {
      for (int i = 0; i < history.length(); i++) {
        messages.put(history.get(i));
      }
    }
    messages.put(userMessage(prompt, imageDataUrl));
    req.put("messages", messages);
    if (temperature >= 0) {
      req.put("temperature", temperature);
    }
    return req;
  }

  public static JSONObject userMessage(String prompt, String imageDataUrl) throws JSONException {
    JSONObject msg = new JSONObject().put("role", "user");
    if (imageDataUrl == null || imageDataUrl.length() == 0) {
      msg.put("content", prompt);
    } else {
      JSONArray parts = new JSONArray();
      parts.put(new JSONObject().put("type", "text").put("text", prompt));
      parts.put(new JSONObject().put("type", "image_url")
          .put("image_url", new JSONObject().put("url", imageDataUrl)));
      msg.put("content", parts);
    }
    return msg;
  }

  /** Pulls the assistant text out of a chat completion. */
  public static String extractChatText(JSONObject completion) throws IOException {
    JSONArray choices = completion.optJSONArray("choices");
    if (choices == null || choices.length() == 0) {
      throw new IOException("No choices in response");
    }
    JSONObject message = choices.optJSONObject(0).optJSONObject("message");
    if (message == null) {
      throw new IOException("No message in response");
    }
    Object content = message.opt("content");
    if (content instanceof String) {
      return (String) content;
    }
    if (content instanceof JSONArray) {
      StringBuilder sb = new StringBuilder();
      JSONArray parts = (JSONArray) content;
      for (int i = 0; i < parts.length(); i++) {
        JSONObject part = parts.optJSONObject(i);
        if (part != null && part.has("text")) {
          sb.append(part.optString("text"));
        }
      }
      return sb.toString();
    }
    return "";
  }

  public String chat(String apiKey, JSONObject chatRequest) throws IOException {
    return extractChatText(postJson(apiBase + "/v1/chat/completions", apiKey, chatRequest));
  }

  // ---------------------------------------------------------------------------------------------
  // Images

  public String imageUrl(String prompt, String model, int width, int height, int seed,
      boolean enhance) {
    StringBuilder sb = new StringBuilder(apiBase).append("/image/").append(encodePath(prompt));
    sb.append("?width=").append(width).append("&height=").append(height);
    if (model != null && model.length() > 0) {
      sb.append("&model=").append(encodeQuery(model));
    }
    if (seed >= 0) {
      sb.append("&seed=").append(seed);
    }
    if (enhance) {
      sb.append("&enhance=true");
    }
    return sb.toString();
  }

  /** Downloads a generated image into dest. The key goes in a header, never in the URL. */
  public File generateImage(String apiKey, String prompt, String model, int width, int height,
      int seed, boolean enhance, File dest) throws IOException {
    Response r = checked(request("GET", imageUrl(prompt, model, width, height, seed, enhance),
        apiKey, null, null));
    if (!r.contentType.startsWith("image/")) {
      throw new IOException("Expected an image but got " + r.contentType);
    }
    writeFile(dest, r.body);
    return dest;
  }

  // ---------------------------------------------------------------------------------------------
  // Speech

  public static JSONObject buildSpeechRequest(String model, String text, String voice,
      String instructions) throws JSONException {
    JSONObject o = new JSONObject();
    if (model != null && model.length() > 0) {
      o.put("model", model);
    }
    o.put("input", text);
    if (voice != null && voice.length() > 0) {
      o.put("voice", voice);
    }
    if (instructions != null && instructions.length() > 0) {
      o.put("instructions", instructions);
    }
    o.put("response_format", "mp3");
    return o;
  }

  public File speech(String apiKey, JSONObject speechRequest, File dest) throws IOException {
    Response r = checked(request("POST", apiBase + "/v1/audio/speech", apiKey, "application/json",
        utf8(speechRequest.toString())));
    if (r.contentType.startsWith("application/json") || r.contentType.startsWith("text/")) {
      throw new IOException("Expected audio but got " + r.contentType);
    }
    writeFile(dest, r.body);
    return dest;
  }

  /** Builds a multipart/form-data body for /v1/audio/transcriptions. */
  public static byte[] buildMultipart(String boundary, String fileName, String fileContentType,
      byte[] fileBytes, String[][] fields) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream(fileBytes.length + 1024);
    for (String[] f : fields) {
      if (f[1] == null || f[1].length() == 0) {
        continue;
      }
      out.write(utf8("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + f[0]
          + "\"\r\n\r\n" + f[1] + "\r\n"));
    }
    out.write(utf8("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\""
        + fileName.replace("\"", "") + "\"\r\nContent-Type: " + fileContentType + "\r\n\r\n"));
    out.write(fileBytes);
    out.write(utf8("\r\n--" + boundary + "--\r\n"));
    return out.toByteArray();
  }

  /** Maps a file name to an upload name and MIME type the transcription models accept. */
  public static String[] audioUploadInfo(String fileName) {
    String lower = fileName.toLowerCase();
    String base = fileName;
    int slash = Math.max(base.lastIndexOf('/'), base.lastIndexOf('\\'));
    if (slash >= 0) {
      base = base.substring(slash + 1);
    }
    if (base.length() == 0) {
      base = "audio.m4a";
    }
    if (lower.endsWith(".mp3") || lower.endsWith(".mpga") || lower.endsWith(".mpeg")) {
      return new String[] {base, "audio/mpeg"};
    } else if (lower.endsWith(".wav")) {
      return new String[] {base, "audio/wav"};
    } else if (lower.endsWith(".webm")) {
      return new String[] {base, "audio/webm"};
    } else if (lower.endsWith(".mp4")) {
      return new String[] {base, "audio/mp4"};
    } else if (lower.endsWith(".ogg") || lower.endsWith(".opus")) {
      return new String[] {base, "audio/ogg"};
    } else if (lower.endsWith(".flac")) {
      return new String[] {base, "audio/flac"};
    } else if (lower.endsWith(".3gp") || lower.endsWith(".3gpp") || lower.endsWith(".amr")) {
      return new String[] {base, "audio/3gpp"};
    } else if (lower.endsWith(".aac")) {
      return new String[] {base, "audio/aac"};
    }
    // .m4a (what StartRecording writes) and anything unknown go up as MP4 audio.
    if (!lower.endsWith(".m4a")) {
      int dot = base.lastIndexOf('.');
      base = (dot > 0 ? base.substring(0, dot) : base) + ".m4a";
    }
    return new String[] {base, "audio/mp4"};
  }

  public String transcribe(String apiKey, String fileName, byte[] audio, String model,
      String language) throws IOException {
    String boundary = "----pollinations" + Long.toHexString(System.nanoTime());
    String[] info = audioUploadInfo(fileName);
    byte[] body = buildMultipart(boundary, info[0], info[1], audio,
        new String[][] {{"model", model}, {"language", language}, {"response_format", "json"}});
    Response r = checked(request("POST", apiBase + "/v1/audio/transcriptions", apiKey,
        "multipart/form-data; boundary=" + boundary, body));
    String text = r.text();
    try {
      return new JSONObject(text).optString("text", "");
    } catch (JSONException e) {
      return text.trim();
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Model lists

  /**
   * Model names for one kind: "text", "image", "video", "speech" or "transcription".
   * Reads the live lists, so new models show up without an extension update.
   */
  public List<String> models(String kind) throws IOException {
    String k = kind == null ? "text" : kind.trim().toLowerCase();
    String path;
    if ("text".equals(k) || "vision".equals(k)) {
      path = "/text/models";
    } else if ("image".equals(k) || "video".equals(k)) {
      path = "/image/models";
    } else if ("speech".equals(k) || "transcription".equals(k) || "audio".equals(k)) {
      path = "/audio/models";
    } else {
      throw new IOException("Unknown model kind '" + kind
          + "'. Use text, vision, image, video, speech or transcription.");
    }
    Response r = checked(request("GET", apiBase + path, null, null, null));
    return filterModels(parseArray(r.text()), k);
  }

  public static List<String> filterModels(JSONArray list, String kind) {
    List<String> names = new ArrayList<String>();
    for (int i = 0; i < list.length(); i++) {
      JSONObject m = list.optJSONObject(i);
      if (m == null) {
        continue;
      }
      String name = m.optString("name", "");
      if (name.length() == 0) {
        continue;
      }
      JSONArray in = m.optJSONArray("input_modalities");
      JSONArray out = m.optJSONArray("output_modalities");
      JSONArray endpoints = m.optJSONArray("supported_endpoints");
      boolean keep;
      if ("vision".equals(kind)) {
        keep = contains(in, "image") && contains(out, "text");
      } else if ("image".equals(kind)) {
        keep = out == null || contains(out, "image");
      } else if ("video".equals(kind)) {
        keep = contains(out, "video");
      } else if ("speech".equals(kind)) {
        keep = contains(out, "audio") && contains(in, "text");
      } else if ("transcription".equals(kind)) {
        keep = contains(endpoints, "/v1/audio/transcriptions")
            || (contains(in, "audio") && contains(out, "text"));
      } else {
        keep = out == null || contains(out, "text");
      }
      if (keep) {
        names.add(name);
      }
    }
    return names;
  }

  private static boolean contains(JSONArray a, String v) {
    if (a == null) {
      return false;
    }
    for (int i = 0; i < a.length(); i++) {
      if (v.equals(a.optString(i))) {
        return true;
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------------------------
  // Utilities

  public static JSONObject parseObject(String text) throws IOException {
    try {
      return new JSONObject(text);
    } catch (JSONException e) {
      throw new IOException("Bad JSON from server: " + abbreviate(text));
    }
  }

  public static JSONArray parseArray(String text) throws IOException {
    try {
      return new JSONArray(text);
    } catch (JSONException e) {
      throw new IOException("Bad JSON from server: " + abbreviate(text));
    }
  }

  private static String abbreviate(String s) {
    return s.length() > 200 ? s.substring(0, 200) + "..." : s;
  }

  public static byte[] utf8(String s) {
    try {
      return s.getBytes("UTF-8");
    } catch (UnsupportedEncodingException e) {
      return s.getBytes();
    }
  }

  public static byte[] readAll(InputStream is) throws IOException {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[16384];
      int n;
      while ((n = is.read(buf)) > 0) {
        out.write(buf, 0, n);
      }
      return out.toByteArray();
    } finally {
      is.close();
    }
  }

  public static void writeFile(File dest, byte[] data) throws IOException {
    File parent = dest.getParentFile();
    if (parent != null && !parent.exists()) {
      parent.mkdirs();
    }
    FileOutputStream fos = new FileOutputStream(dest);
    try {
      fos.write(data);
    } finally {
      fos.close();
    }
  }

  /** Percent-encodes one path segment (spaces become %20, slashes are escaped). */
  public static String encodePath(String s) {
    return encodeQuery(s).replace("+", "%20");
  }

  public static String encodeQuery(String s) {
    try {
      return URLEncoder.encode(s, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      return s;
    }
  }

  private static final char[] B64 =
      "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

  /** Standard base64 without line breaks (android.util.Base64 is not on the plain JVM). */
  public static String base64(byte[] data) {
    StringBuilder sb = new StringBuilder((data.length + 2) / 3 * 4);
    int i = 0;
    while (i + 2 < data.length) {
      int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8) | (data[i + 2] & 0xff);
      sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append(B64[(n >> 6) & 63])
          .append(B64[n & 63]);
      i += 3;
    }
    int rest = data.length - i;
    if (rest == 1) {
      int n = (data[i] & 0xff) << 16;
      sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append("==");
    } else if (rest == 2) {
      int n = ((data[i] & 0xff) << 16) | ((data[i + 1] & 0xff) << 8);
      sb.append(B64[(n >> 18) & 63]).append(B64[(n >> 12) & 63]).append(B64[(n >> 6) & 63])
          .append('=');
    }
    return sb.toString();
  }

  private static String stripSlash(String s) {
    if (s == null) {
      return "";
    }
    while (s.endsWith("/")) {
      s = s.substring(0, s.length() - 1);
    }
    return s;
  }
}
