import io.github.drjacky.pollinations.client.PollinationsClient;
import java.io.File;
import java.nio.file.Files;

/** Live check against gen.pollinations.ai using the cheapest models. Key from POLLINATIONS_KEY. */
public class LiveSmoke {
  public static void main(String[] a) throws Exception {
    String key = System.getenv("POLLINATIONS_KEY");
    PollinationsClient c = new PollinationsClient();
    System.out.println("text models: " + c.models("text").size()
        + ", vision: " + c.models("vision").size()
        + ", image: " + c.models("image").size()
        + ", speech: " + c.models("speech").size()
        + ", transcription: " + c.models("transcription").size());
    PollinationsClient.DeviceCode d = c.requestDeviceCode("");
    System.out.println("device code ok: user_code length " + d.userCode.length()
        + ", link " + d.verificationUri + ", interval " + d.interval + "s");
    PollinationsClient.TokenPoll p = c.pollDeviceToken(d.deviceCode);
    System.out.println("first poll state: " + p.state);
    String txt = c.chat(key, PollinationsClient.buildChatRequest("openai/gpt-5.4-nano",
        "Answer in at most five words.", null, "Say hello to App Inventor builders.", null, -1));
    System.out.println("chat: " + txt);
    File img = new File(a[0], "live-image.jpg");
    c.generateImage(key, "a small yellow bee, flat icon", "lykon/dreamshaper-8-lcm", 256, 256, 7,
        false, img);
    System.out.println("image bytes: " + img.length());
    byte[] jpg = Files.readAllBytes(img.toPath());
    String vis = c.chat(key, PollinationsClient.buildChatRequest("openai/gpt-5.4-nano", null, null,
        "What animal is in this picture? One word.", "data:image/jpeg;base64,"
        + PollinationsClient.base64(jpg), -1));
    System.out.println("vision: " + vis);
    File mp3 = new File(a[0], "live-speech.mp3");
    c.speech(key, PollinationsClient.buildSpeechRequest("openai/tts-1",
        "Hello from App Inventor.", "nova", null), mp3);
    System.out.println("speech bytes: " + mp3.length());
    String stt = c.transcribe(key, mp3.getName(), Files.readAllBytes(mp3.toPath()),
        "openai/whisper-large-v3", "en");
    System.out.println("transcript of that speech: " + stt);
    System.out.println("balance call ok: " + (c.balance(key) >= 0));
  }
}
