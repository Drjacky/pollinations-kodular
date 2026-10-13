import io.github.drjacky.pollinations.client.PollinationsClient;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Tests for PollinationsClient against a local mock of gen/enter.pollinations.ai.
 * No network, no Pollen. Run with: ant test   (or see tools/test.sh)
 */
public class PollinationsClientTest {

  static int passed = 0;
  static List<String> failures = new ArrayList<String>();

  static final Map<String, String> lastHeaders = new ConcurrentHashMap<String, String>();
  static final Map<String, byte[]> lastBodies = new ConcurrentHashMap<String, byte[]>();
  static final AtomicInteger tokenPolls = new AtomicInteger();

  public static void main(String[] args) throws Exception {
    HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", new Mock());
    server.start();
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    PollinationsClient c = new PollinationsClient();
    c.setApiBase(base + "/");
    c.setAuthBase(base);
    try {
      run("base64 matches java.util.Base64", () -> {
        Random r = new Random(1);
        for (int n = 0; n < 70; n++) {
          byte[] b = new byte[n];
          r.nextBytes(b);
          eq(Base64.getEncoder().encodeToString(b), PollinationsClient.base64(b), "len " + n);
        }
      });
      run("image URL encodes prompt as a path segment", () -> {
        String u = c.imageUrl("a cat / dog & more?", "flux", 512, 256, 42, false);
        eq(base + "/image/a%20cat%20%2F%20dog%20%26%20more%3F?width=512&height=256&model=flux"
            + "&seed=42", u, "url");
        check(!c.imageUrl("x", "", 1, 1, -1, false).contains("seed"), "no seed when -1");
      });
      run("chat request has system, history, and multimodal user message", () -> {
        JSONArray hist = new JSONArray().put(new JSONObject().put("role", "user")
            .put("content", "hi")).put(new JSONObject().put("role", "assistant")
            .put("content", "hello"));
        JSONObject req = PollinationsClient.buildChatRequest("m", "be brief", hist, "what is it",
            "data:image/jpeg;base64,AAA", -1);
        JSONArray msgs = req.getJSONArray("messages");
        eq(4, msgs.length(), "message count");
        eq("system", msgs.getJSONObject(0).getString("role"), "system first");
        JSONArray parts = msgs.getJSONObject(3).getJSONArray("content");
        eq("text", parts.getJSONObject(0).getString("type"), "text part");
        eq("data:image/jpeg;base64,AAA",
            parts.getJSONObject(1).getJSONObject("image_url").getString("url"), "image part");
        check(!req.has("temperature"), "no temperature when -1");
      });
      run("chat sends bearer key and returns assistant text", () -> {
        String out = c.chat("sk_test", PollinationsClient.buildChatRequest("openai/gpt-5.4-nano",
            null, null, "ping", null, -1));
        eq("echo: ping", out, "answer");
        eq("Bearer sk_test", lastHeaders.get("/v1/chat/completions"), "auth header");
      });
      run("chat without a key sends no Authorization header", () -> {
        c.chat("", PollinationsClient.buildChatRequest("m", null, null, "ping", null, -1));
        eq("", lastHeaders.get("/v1/chat/completions"), "no auth");
      });
      run("gateway error JSON becomes a readable ApiException", () -> {
        try {
          c.chat("sk_broke", PollinationsClient.buildChatRequest("m", null, null, "x", null, -1));
          check(false, "should throw");
        } catch (PollinationsClient.ApiException e) {
          eq(402, e.status, "status");
          eq("HTTP 402: Insufficient pollen balance", e.getMessage(), "message");
        }
      });
      run("image is downloaded with the key in a header, not the URL", () -> {
        File f = File.createTempFile("img", ".jpg");
        c.generateImage("sk_test", "red circle", "zimage", 64, 64, 1, false, f);
        eq("Bearer sk_test", lastHeaders.get("/image/"), "auth header");
        byte[] b = Files.readAllBytes(f.toPath());
        check(b.length == 4 && (b[0] & 0xff) == 0xFF && (b[1] & 0xff) == 0xD8, "jpeg bytes");
        f.delete();
      });
      run("image endpoint returning JSON is an error, not a broken file", () -> {
        File f = File.createTempFile("img", ".jpg");
        try {
          c.generateImage("sk_test", "json please", "", 64, 64, -1, false, f);
          check(false, "should throw");
        } catch (IOException e) {
          check(e.getMessage().contains("Expected an image"), e.getMessage());
        }
        f.delete();
      });
      run("speech posts JSON and saves mp3", () -> {
        File f = File.createTempFile("tts", ".mp3");
        c.speech("sk_test", PollinationsClient.buildSpeechRequest("openai/tts-1", "hello",
            "nova", "warmly"), f);
        JSONObject sent = new JSONObject(new String(lastBodies.get("/v1/audio/speech"), "UTF-8"));
        eq("hello", sent.getString("input"), "input");
        eq("nova", sent.getString("voice"), "voice");
        eq("warmly", sent.getString("instructions"), "instructions");
        eq("mp3", sent.getString("response_format"), "format");
        eq(3, Files.readAllBytes(f.toPath()).length, "audio bytes");
        f.delete();
      });
      run("transcribe sends multipart with file, model and language", () -> {
        byte[] audio = new byte[] {0, 1, 2, 3, (byte) 0xff};
        String text = c.transcribe("sk_test", "/data/x/recording-1.m4a", audio,
            "openai/whisper-large-v3", "en");
        eq("hello world", text, "transcript");
        String body = new String(lastBodies.get("/v1/audio/transcriptions"), "ISO-8859-1");
        check(body.contains("name=\"model\"\r\n\r\nopenai/whisper-large-v3\r\n"), "model field");
        check(body.contains("name=\"language\"\r\n\r\nen\r\n"), "language field");
        check(body.contains("filename=\"recording-1.m4a\"\r\nContent-Type: audio/mp4"),
            "file part");
        check(body.contains(new String(audio, "ISO-8859-1")), "raw audio bytes intact");
        check(lastHeaders.get("ct:/v1/audio/transcriptions")
            .startsWith("multipart/form-data; boundary="), "content type");
      });
      run("transcribe omits empty language", () -> {
        c.transcribe("sk_test", "a.wav", new byte[] {1}, "m", "");
        String body = new String(lastBodies.get("/v1/audio/transcriptions"), "ISO-8859-1");
        check(!body.contains("name=\"language\""), "no language field");
        check(body.contains("Content-Type: audio/wav"), "wav mime");
      });
      run("audio upload names and MIME types", () -> {
        eq("audio/mpeg", PollinationsClient.audioUploadInfo("a.MP3")[1], "mp3");
        eq("audio/3gpp", PollinationsClient.audioUploadInfo("/x/rec.3gp")[1], "3gp");
        eq("rec.m4a", PollinationsClient.audioUploadInfo("/x/rec.m4a")[0], "m4a name");
        eq("noext.m4a", PollinationsClient.audioUploadInfo("content://x/noext")[0], "fallback");
      });
      run("device flow: code, pending, slow_down, then key", () -> {
        tokenPolls.set(0);
        PollinationsClient.DeviceCode code = c.requestDeviceCode("pk_app");
        eq("ABCD-1234", code.userCode, "user code");
        eq(base + "/device?user_code=ABCD-1234", code.verificationUriComplete, "link");
        JSONObject sent = new JSONObject(new String(lastBodies.get("/api/device/code"), "UTF-8"));
        eq("pk_app", sent.getString("client_id"), "client_id sent");
        final List<Long> sleeps = new ArrayList<Long>();
        String key = c.waitForDeviceToken(code, ms -> sleeps.add(ms), () -> false);
        eq("sk_user", key, "key");
        eq(3, tokenPolls.get(), "polls");
        eq("[1000, 6000, 6000]", sleeps.toString(), "interval then slow_down backoff");
      });
      run("device flow: denied stops with an error", () -> {
        PollinationsClient.DeviceCode code = new PollinationsClient.DeviceCode();
        code.deviceCode = "deny";
        code.interval = 1;
        code.expiresIn = 60;
        try {
          c.waitForDeviceToken(code, ms -> { }, () -> false);
          check(false, "should throw");
        } catch (PollinationsClient.ApiException e) {
          eq("access_denied", e.code, "code");
        }
      });
      run("device flow: cancel stops polling", () -> {
        PollinationsClient.DeviceCode code = new PollinationsClient.DeviceCode();
        code.deviceCode = "pending-forever";
        code.interval = 1;
        code.expiresIn = 60;
        final AtomicInteger n = new AtomicInteger();
        try {
          c.waitForDeviceToken(code, ms -> n.incrementAndGet(), () -> n.get() >= 2);
          check(false, "should throw");
        } catch (InterruptedException e) {
          eq(2, n.get(), "stopped after cancel");
        }
      });
      run("model lists filter by kind", () -> {
        eq("[t1, v1]", c.models("text").toString(), "text");
        eq("[v1]", c.models("vision").toString(), "vision");
        eq("[i1]", c.models("image").toString(), "image");
        eq("[vid1]", c.models("video").toString(), "video");
        eq("[tts1]", c.models("speech").toString(), "speech");
        eq("[stt1]", c.models("transcription").toString(), "transcription");
        try {
          c.models("nope");
          check(false, "should throw");
        } catch (IOException e) {
          check(e.getMessage().contains("Unknown model kind"), "unknown kind");
        }
      });
      run("userinfo and balance", () -> {
        eq("voodoohop", c.userInfo("sk_user")[0], "username");
        eq(12.5, c.balance("sk_user"), "balance");
      });
    } finally {
      server.stop(0);
    }
    System.out.println();
    System.out.println(passed + " passed, " + failures.size() + " failed");
    for (String f : failures) {
      System.out.println("FAIL " + f);
    }
    System.exit(failures.isEmpty() ? 0 : 1);
  }

  // --------------------------------------------------------------------------------------------

  interface Body {
    void run() throws Exception;
  }

  static void run(String name, Body b) {
    try {
      b.run();
      passed++;
      System.out.println("ok   " + name);
    } catch (Throwable t) {
      failures.add(name + ": " + t);
      System.out.println("FAIL " + name + ": " + t);
    }
  }

  static void check(boolean cond, String what) {
    if (!cond) {
      throw new AssertionError(what);
    }
  }

  static void eq(Object want, Object got, String what) {
    if (want == null ? got != null : !want.equals(got)) {
      throw new AssertionError(what + ": want <" + want + "> got <" + got + ">");
    }
  }

  static class Mock implements HttpHandler {
    public void handle(HttpExchange ex) throws IOException {
      String path = ex.getRequestURI().getPath();
      String query = ex.getRequestURI().getRawQuery();
      byte[] body = readBody(ex);
      String auth = ex.getRequestHeaders().getFirst("Authorization");
      boolean imageGen = path.startsWith("/image/") && !path.equals("/image/models");
      String key = imageGen ? "/image/" : path;
      lastHeaders.put(key, auth == null ? "" : auth);
      String ct = ex.getRequestHeaders().getFirst("Content-Type");
      lastHeaders.put("ct:" + key, ct == null ? "" : ct);
      lastBodies.put(key, body);
      try {
        if (path.equals("/v1/chat/completions")) {
          if ("Bearer sk_broke".equals(auth)) {
            send(ex, 402, "application/json", "{\"success\":false,\"error\":{\"message\":"
                + "\"Insufficient pollen balance\",\"code\":\"PAYMENT_REQUIRED\"},\"status\":402}");
            return;
          }
          JSONObject req = new JSONObject(new String(body, "UTF-8"));
          JSONArray msgs = req.getJSONArray("messages");
          String last = msgs.getJSONObject(msgs.length() - 1).getString("content");
          send(ex, 200, "application/json", new JSONObject().put("choices", new JSONArray()
              .put(new JSONObject().put("message", new JSONObject().put("role", "assistant")
              .put("content", "echo: " + last)))).toString());
        } else if (imageGen) {
          if (path.contains("json please")) {
            send(ex, 200, "application/json", "{\"url\":\"x\"}");
          } else {
            sendBytes(ex, 200, "image/jpeg", new byte[] {(byte) 0xFF, (byte) 0xD8, 0, 0});
          }
        } else if (path.equals("/v1/audio/speech")) {
          sendBytes(ex, 200, "audio/mpeg", new byte[] {1, 2, 3});
        } else if (path.equals("/v1/audio/transcriptions")) {
          send(ex, 200, "application/json", "{\"text\":\"hello world\"}");
        } else if (path.equals("/api/device/code")) {
          String b = "http://" + ex.getRequestHeaders().getFirst("Host");
          send(ex, 200, "application/json", new JSONObject().put("device_code", "dc1")
              .put("user_code", "ABCD-1234").put("verification_uri", b + "/device")
              .put("verification_uri_complete", b + "/device?user_code=ABCD-1234")
              .put("expires_in", 1800).put("interval", 1).toString());
        } else if (path.equals("/api/device/token")) {
          String dc = new JSONObject(new String(body, "UTF-8")).getString("device_code");
          if (dc.equals("deny")) {
            send(ex, 400, "application/json", "{\"error\":\"access_denied\"}");
          } else if (dc.equals("pending-forever")) {
            send(ex, 400, "application/json", "{\"error\":\"authorization_pending\"}");
          } else {
            int n = tokenPolls.incrementAndGet();
            if (n == 1) {
              send(ex, 400, "application/json", "{\"error\":\"slow_down\"}");
            } else if (n == 2) {
              send(ex, 400, "application/json", "{\"error\":\"authorization_pending\"}");
            } else {
              send(ex, 200, "application/json",
                  "{\"access_token\":\"sk_user\",\"token_type\":\"bearer\"}");
            }
          }
        } else if (path.equals("/api/device/userinfo")) {
          send(ex, 200, "application/json", "{\"sub\":\"u1\",\"preferred_username\":\"voodoohop\"}");
        } else if (path.equals("/account/balance")) {
          send(ex, 200, "application/json", "{\"balance\":12.5}");
        } else if (path.equals("/text/models")) {
          send(ex, 200, "application/json", "[" + m("t1", "text", "text", null) + ","
              + m("v1", "text\",\"image", "text", null) + "]");
        } else if (path.equals("/image/models")) {
          send(ex, 200, "application/json", "[" + m("i1", "text", "image", null) + ","
              + m("vid1", "text", "video", null) + "]");
        } else if (path.equals("/audio/models")) {
          send(ex, 200, "application/json", "[" + m("tts1", "text", "audio", "/audio/{text}")
              + "," + m("stt1", "audio", "text", "/v1/audio/transcriptions") + "]");
        } else {
          send(ex, 404, "text/plain", "not found " + path + (query == null ? "" : "?" + query));
        }
      } catch (Exception e) {
        send(ex, 500, "text/plain", e.toString());
      }
    }

    static String m(String name, String in, String out, String endpoint) {
      return "{\"name\":\"" + name + "\",\"input_modalities\":[\"" + in + "\"],"
          + "\"output_modalities\":[\"" + out + "\"]"
          + (endpoint == null ? "" : ",\"supported_endpoints\":[\"" + endpoint + "\"]") + "}";
    }

    static byte[] readBody(HttpExchange ex) throws IOException {
      return PollinationsClient.readAll(ex.getRequestBody());
    }

    static void send(HttpExchange ex, int status, String ct, String text) throws IOException {
      sendBytes(ex, status, ct, text.getBytes("UTF-8"));
    }

    static void sendBytes(HttpExchange ex, int status, String ct, byte[] b) throws IOException {
      ex.getResponseHeaders().set("Content-Type", ct);
      ex.sendResponseHeaders(status, b.length);
      OutputStream os = ex.getResponseBody();
      os.write(b);
      os.close();
    }
  }
}
