package io.github.drjacky.pollinations;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaRecorder;
import android.net.Uri;

import com.google.appinventor.components.annotations.DesignerComponent;
import com.google.appinventor.components.annotations.DesignerProperty;
import com.google.appinventor.components.annotations.PropertyCategory;
import com.google.appinventor.components.annotations.SimpleEvent;
import com.google.appinventor.components.annotations.SimpleFunction;
import com.google.appinventor.components.annotations.SimpleProperty;
import com.google.appinventor.components.common.ComponentCategory;
import com.google.appinventor.components.common.PropertyTypeConstants;
import com.google.appinventor.components.runtime.AndroidNonvisibleComponent;
import com.google.appinventor.components.runtime.Component;
import com.google.appinventor.components.runtime.EventDispatcher;
import com.google.appinventor.components.runtime.Form;
import com.google.appinventor.components.runtime.OnDestroyListener;
import com.google.appinventor.components.runtime.PermissionResultHandler;
import com.google.appinventor.components.runtime.util.AsynchUtil;
import com.google.appinventor.components.runtime.util.MediaUtil;
import com.google.appinventor.components.runtime.util.YailList;

import io.github.drjacky.pollinations.client.PollinationsClient;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Pollinations for Kodular (and MIT App Inventor): text, vision, images, speech and speech-to-text from blocks,
 * paid with the end user's own Pollen through the Bring Your Own Pollen device flow.
 */
@DesignerComponent(
    version = 1,
    versionName = "1.0",
    description = "Pollinations.ai from blocks: chat, ask about a picture, make images, "
        + "text-to-speech and speech-to-text. Users sign in with their own Pollen "
        + "(no API key inside your app). Docs: "
        + "https://github.com/Drjacky/pollinations-kodular",
    category = ComponentCategory.EXTENSION,
    nonVisible = true,
    iconName = "icon.png",
    helpUrl = "https://github.com/Drjacky/pollinations-kodular#blocks")
public class Pollinations extends AndroidNonvisibleComponent
    implements Component, OnDestroyListener {

  private static final String PREFS = "pollinations_extension";
  private static final String PREF_KEY = "user_key";
  private static final String PREF_NAME = "user_name";

  private final Activity activity;
  private final PollinationsClient client = new PollinationsClient();

  private String apiKey = "";
  private String appKey = "";
  private String textModel = "openai/gpt-5.4-nano";
  private String imageModel = "tongyi-mai/z-image-turbo";
  private String speechModel = "openai/tts-1";
  private String transcriptionModel = "openai/whisper-large-v3";
  private String voice = "alloy";
  private String systemPrompt = "";
  private String language = "";
  private int imageWidth = 1024;
  private int imageHeight = 1024;
  private int seed = -1;
  private boolean keepHistory = true;
  private boolean rememberSignIn = true;
  private boolean openBrowser = true;

  private String userKey = "";
  private String userName = "";
  private volatile boolean signInRunning = false;
  private volatile boolean signInCancelled = false;
  private final JSONArray history = new JSONArray();

  private MediaRecorder recorder;
  private File recordingFile;

  public Pollinations(Form form) {
    super(form);
    this.activity = form;
    form.registerForOnDestroy(this);
    SharedPreferences prefs = prefs();
    userKey = prefs.getString(PREF_KEY, "");
    userName = prefs.getString(PREF_NAME, "");
  }

  // ---------------------------------------------------------------------------------------------
  // Designer properties

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "")
  @SimpleProperty(description = "Optional publishable app key (pk_...) from "
      + "enter.pollinations.ai. It only labels sign-ins with your app's name. It is safe to "
      + "ship; it cannot spend Pollen.")
  public void AppKey(String key) {
    appKey = key == null ? "" : key.trim();
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public String AppKey() {
    return appKey;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "")
  @SimpleProperty(description = "Optional secret key (sk_...) for your own testing. Leave it "
      + "empty in apps you share: anyone can pull it out of the APK. Use SignIn instead so "
      + "each user pays with their own Pollen.")
  public void ApiKey(String key) {
    apiKey = key == null ? "" : key.trim();
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR, userVisible = false)
  public String ApiKey() {
    return apiKey;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
      defaultValue = "openai/gpt-5.4-nano")
  @SimpleProperty(description = "Model for AskText and AskAboutImage. Any name from "
      + "GetModels \"text\" (or \"vision\" for pictures).")
  public void TextModel(String model) {
    textModel = model;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public String TextModel() {
    return textModel;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
      defaultValue = "tongyi-mai/z-image-turbo")
  @SimpleProperty(description = "Model for GenerateImage. Any name from GetModels \"image\".")
  public void ImageModel(String model) {
    imageModel = model;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public String ImageModel() {
    return imageModel;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
      defaultValue = "openai/tts-1")
  @SimpleProperty(description = "Model for Speak. Any name from GetModels \"speech\".")
  public void SpeechModel(String model) {
    speechModel = model;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public String SpeechModel() {
    return speechModel;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
      defaultValue = "openai/whisper-large-v3")
  @SimpleProperty(description = "Model for Transcribe. Any name from GetModels "
      + "\"transcription\".")
  public void TranscriptionModel(String model) {
    transcriptionModel = model;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public String TranscriptionModel() {
    return transcriptionModel;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING,
      defaultValue = "alloy")
  @SimpleProperty(description = "Voice for Speak, for example alloy, echo, fable, onyx, nova "
      + "or shimmer. Other speech models have their own voices.")
  public void Voice(String v) {
    voice = v;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public String Voice() {
    return voice;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_TEXTAREA, defaultValue = "")
  @SimpleProperty(description = "Optional instructions sent before every AskText, for example "
      + "\"Answer in one short sentence.\"")
  public void SystemPrompt(String prompt) {
    systemPrompt = prompt;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public String SystemPrompt() {
    return systemPrompt;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_STRING, defaultValue = "")
  @SimpleProperty(description = "Optional language of recordings for Transcribe, as a "
      + "two-letter code such as en, de or fa. Empty means detect it.")
  public void TranscriptionLanguage(String lang) {
    language = lang == null ? "" : lang.trim();
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public String TranscriptionLanguage() {
    return language;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_NON_NEGATIVE_INTEGER,
      defaultValue = "1024")
  @SimpleProperty(description = "Width of generated images in pixels.")
  public void ImageWidth(int w) {
    imageWidth = w;
  }

  @SimpleProperty(category = PropertyCategory.APPEARANCE)
  public int ImageWidth() {
    return imageWidth;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_NON_NEGATIVE_INTEGER,
      defaultValue = "1024")
  @SimpleProperty(description = "Height of generated images in pixels.")
  public void ImageHeight(int h) {
    imageHeight = h;
  }

  @SimpleProperty(category = PropertyCategory.APPEARANCE)
  public int ImageHeight() {
    return imageHeight;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_INTEGER, defaultValue = "-1")
  @SimpleProperty(description = "Seed for GenerateImage. The same prompt and seed give the same "
      + "picture. -1 means a new picture every time.")
  public void Seed(int s) {
    seed = s;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public int Seed() {
    return seed;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN,
      defaultValue = "True")
  @SimpleProperty(description = "When true, AskText remembers the conversation so follow-up "
      + "questions work. Use ClearHistory to start over.")
  public void KeepHistory(boolean keep) {
    keepHistory = keep;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public boolean KeepHistory() {
    return keepHistory;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN,
      defaultValue = "True")
  @SimpleProperty(description = "When true, the user's key is kept in this app's private "
      + "storage so they stay signed in after a restart (keys last 7 days by default).")
  public void RememberSignIn(boolean remember) {
    rememberSignIn = remember;
    if (!remember) {
      prefs().edit().remove(PREF_KEY).remove(PREF_NAME).apply();
    }
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public boolean RememberSignIn() {
    return rememberSignIn;
  }

  @DesignerProperty(editorType = PropertyTypeConstants.PROPERTY_TYPE_BOOLEAN,
      defaultValue = "True")
  @SimpleProperty(description = "When true, SignIn opens the approval page in the browser. "
      + "Turn it off to show the code and link yourself (for example as a QR code).")
  public void OpenBrowserOnSignIn(boolean open) {
    openBrowser = open;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR)
  public boolean OpenBrowserOnSignIn() {
    return openBrowser;
  }

  // ---------------------------------------------------------------------------------------------
  // Read-only properties

  @SimpleProperty(category = PropertyCategory.BEHAVIOR,
      description = "True when a user key from SignIn (or ApiKey) is ready to use.")
  public boolean IsSignedIn() {
    return activeKey().length() > 0;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR,
      description = "Pollinations user name of the signed-in user, or empty.")
  public String UserName() {
    return userName;
  }

  @SimpleProperty(category = PropertyCategory.BEHAVIOR,
      description = "True while StartRecording is recording.")
  public boolean IsRecording() {
    return recorder != null;
  }

  // ---------------------------------------------------------------------------------------------
  // Sign-in (Bring Your Own Pollen device flow)

  @SimpleFunction(description = "Signs the user in with their own Pollen. Fires SignInCode with "
      + "a short code and a link (and opens the link when OpenBrowserOnSignIn is true). After "
      + "the user approves in the browser, SignedIn fires. No API key ships in your app.")
  public void SignIn() {
    if (signInRunning) {
      return;
    }
    signInRunning = true;
    signInCancelled = false;
    AsynchUtil.runAsynchronously(new Runnable() {
      public void run() {
        try {
          final PollinationsClient.DeviceCode code = client.requestDeviceCode(appKey);
          activity.runOnUiThread(new Runnable() {
            public void run() {
              SignInCode(code.userCode, code.verificationUriComplete, code.expiresIn);
              if (openBrowser) {
                OpenUrl(code.verificationUriComplete);
              }
            }
          });
          String key = client.waitForDeviceToken(code, new PollinationsClient.Sleeper() {
            public void sleep(long ms) throws InterruptedException {
              Thread.sleep(ms);
            }
          }, new PollinationsClient.DeviceFlowListener() {
            public boolean isCancelled() {
              return signInCancelled;
            }
          });
          String name = "";
          try {
            name = client.userInfo(key)[0];
          } catch (IOException ignored) {
            // the key works even if the profile lookup fails
          }
          final String finalKey = key;
          final String finalName = name;
          activity.runOnUiThread(new Runnable() {
            public void run() {
              signInRunning = false;
              userKey = finalKey;
              userName = finalName;
              if (rememberSignIn) {
                prefs().edit().putString(PREF_KEY, finalKey).putString(PREF_NAME, finalName)
                    .apply();
              }
              SignedIn(finalName);
            }
          });
        } catch (InterruptedException e) {
          signInRunning = false;
        } catch (Exception e) {
          signInRunning = false;
          fail("SignIn", e);
        }
      }
    });
  }

  @SimpleFunction(description = "Stops a SignIn that is still waiting for the user.")
  public void CancelSignIn() {
    signInCancelled = true;
  }

  @SimpleFunction(description = "Forgets the user's key on this device. (The user can also "
      + "revoke it at enter.pollinations.ai.)")
  public void SignOut() {
    userKey = "";
    userName = "";
    prefs().edit().remove(PREF_KEY).remove(PREF_NAME).apply();
  }

  @SimpleFunction(description = "Opens a web page in the browser. Handy for the SignInCode link.")
  public void OpenUrl(String url) {
    try {
      Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      activity.startActivity(intent);
    } catch (ActivityNotFoundException e) {
      ErrorOccurred("OpenUrl", "No browser found to open " + url);
    }
  }

  @SimpleEvent(description = "Show this code and link to the user. They open the link, sign "
      + "in and approve. expiresInSeconds is how long the code works.")
  public void SignInCode(String userCode, String url, int expiresInSeconds) {
    EventDispatcher.dispatchEvent(this, "SignInCode", userCode, url, expiresInSeconds);
  }

  @SimpleEvent(description = "The user approved. All blocks now use their Pollen.")
  public void SignedIn(String userName) {
    EventDispatcher.dispatchEvent(this, "SignedIn", userName);
  }

  // ---------------------------------------------------------------------------------------------
  // Text and vision

  @SimpleFunction(description = "Sends a prompt to TextModel. The answer arrives in GotText. "
      + "With KeepHistory on, follow-up questions remember the conversation.")
  public void AskText(final String prompt) {
    chatAsync("AskText", prompt, null);
  }

  @SimpleFunction(description = "Asks TextModel about a picture: an ImagePicker.Selection, a "
      + "Camera photo, a media file name or a web link. The answer arrives in GotText. Pick a "
      + "model from GetModels \"vision\" if the default cannot see pictures.")
  public void AskAboutImage(final String prompt, final String image) {
    chatAsync("AskAboutImage", prompt, image);
  }

  @SimpleFunction(description = "Forgets the AskText conversation.")
  public void ClearHistory() {
    synchronized (history) {
      while (history.length() > 0) {
        history.remove(history.length() - 1);
      }
    }
  }

  private void chatAsync(final String action, final String prompt, final String image) {
    final String key = activeKey();
    final String model = textModel;
    final String system = systemPrompt;
    final boolean useHistory = keepHistory;
    AsynchUtil.runAsynchronously(new Runnable() {
      public void run() {
        try {
          String dataUrl = null;
          if (image != null && image.length() > 0) {
            dataUrl = imageToDataUrl(image);
          }
          JSONArray past;
          synchronized (history) {
            past = useHistory ? new JSONArray(history.toString()) : null;
          }
          JSONObject req = PollinationsClient.buildChatRequest(model, system, past, prompt,
              dataUrl, -1);
          final String answer = client.chat(key, req);
          if (useHistory) {
            synchronized (history) {
              // Store the text only; resending old photos would cost Pollen on every turn.
              history.put(PollinationsClient.userMessage(prompt, null));
              history.put(new JSONObject().put("role", "assistant").put("content", answer));
            }
          }
          activity.runOnUiThread(new Runnable() {
            public void run() {
              GotText(answer);
            }
          });
        } catch (Exception e) {
          fail(action, e);
        }
      }
    });
  }

  @SimpleEvent(description = "The answer from AskText or AskAboutImage.")
  public void GotText(String text) {
    EventDispatcher.dispatchEvent(this, "GotText", text);
  }

  // ---------------------------------------------------------------------------------------------
  // Images

  @SimpleFunction(description = "Makes a picture with ImageModel. GotImage gives a file you can "
      + "put straight into Image.Picture or Canvas.BackgroundImage.")
  public void GenerateImage(final String prompt) {
    final String key = activeKey();
    final String model = imageModel;
    final int w = imageWidth;
    final int h = imageHeight;
    final int s = seed;
    AsynchUtil.runAsynchronously(new Runnable() {
      public void run() {
        try {
          File dest = cacheFile("image", ".jpg");
          client.generateImage(key, prompt, model, w, h, s, false, dest);
          final String path = dest.getAbsolutePath();
          activity.runOnUiThread(new Runnable() {
            public void run() {
              GotImage(path);
            }
          });
        } catch (Exception e) {
          fail("GenerateImage", e);
        }
      }
    });
  }

  @SimpleEvent(description = "The picture from GenerateImage, saved as a file on the device.")
  public void GotImage(String image) {
    EventDispatcher.dispatchEvent(this, "GotImage", image);
  }

  // ---------------------------------------------------------------------------------------------
  // Speech

  @SimpleFunction(description = "Turns text into speech with SpeechModel and Voice. GotSpeech "
      + "gives an mp3 file for Player.Source. instructions is optional, for example "
      + "\"speak slowly and warmly\" (models that support it).")
  public void Speak(final String text, final String instructions) {
    final String key = activeKey();
    final String model = speechModel;
    final String v = voice;
    AsynchUtil.runAsynchronously(new Runnable() {
      public void run() {
        try {
          File dest = cacheFile("speech", ".mp3");
          client.speech(key, PollinationsClient.buildSpeechRequest(model, text, v, instructions),
              dest);
          final String path = dest.getAbsolutePath();
          activity.runOnUiThread(new Runnable() {
            public void run() {
              GotSpeech(path);
            }
          });
        } catch (Exception e) {
          fail("Speak", e);
        }
      }
    });
  }

  @SimpleEvent(description = "The mp3 file from Speak. Set Player.Source to it and call "
      + "Player.Start.")
  public void GotSpeech(String audioFile) {
    EventDispatcher.dispatchEvent(this, "GotSpeech", audioFile);
  }

  @SimpleFunction(description = "Starts recording from the microphone (asks for permission the "
      + "first time). Call StopRecording when the user is done.")
  public void StartRecording() {
    if (recorder != null) {
      return;
    }
    if (form.isDeniedPermission(Manifest.permission.RECORD_AUDIO)) {
      form.askPermission(Manifest.permission.RECORD_AUDIO, new PermissionResultHandler() {
        public void HandlePermissionResponse(String permission, boolean granted) {
          if (granted) {
            startRecorder();
          } else {
            ErrorOccurred("StartRecording", "Microphone permission was denied.");
          }
        }
      });
      return;
    }
    startRecorder();
  }

  private void startRecorder() {
    try {
      recordingFile = cacheFile("recording", ".m4a");
      MediaRecorder r = new MediaRecorder();
      r.setAudioSource(MediaRecorder.AudioSource.MIC);
      // MPEG-4/AAC, a format every transcription model accepts (the built-in SoundRecorder
      // writes 3GP/AMR, which most of them reject).
      r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
      r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
      r.setAudioSamplingRate(16000);
      r.setAudioEncodingBitRate(64000);
      r.setAudioChannels(1);
      r.setOutputFile(recordingFile.getAbsolutePath());
      r.prepare();
      r.start();
      recorder = r;
    } catch (Exception e) {
      releaseRecorder();
      fail("StartRecording", e);
    }
  }

  @SimpleFunction(description = "Stops recording. When transcribe is true the "
      + "recording is sent to Transcribe right away; RecordingStopped fires either way.")
  public void StopRecording(boolean transcribe) {
    if (recorder == null) {
      ErrorOccurred("StopRecording", "Not recording. Call StartRecording first.");
      return;
    }
    try {
      recorder.stop();
    } catch (RuntimeException e) {
      // stop() throws when nothing was captured (a very short tap)
      releaseRecorder();
      ErrorOccurred("StopRecording", "The recording was too short.");
      return;
    }
    releaseRecorder();
    String path = recordingFile.getAbsolutePath();
    RecordingStopped(path);
    if (transcribe) {
      Transcribe(path);
    }
  }

  @SimpleEvent(description = "The recording is saved. Play it with Player or send it to "
      + "Transcribe.")
  public void RecordingStopped(String audioFile) {
    EventDispatcher.dispatchEvent(this, "RecordingStopped", audioFile);
  }

  @SimpleFunction(description = "Speech to text for an audio file (a StartRecording file, a "
      + "media file name or a picked file; m4a, mp3, wav, webm, ogg or flac). The text "
      + "arrives in GotTranscript.")
  public void Transcribe(final String audioFile) {
    final String key = activeKey();
    final String model = transcriptionModel;
    final String lang = language;
    AsynchUtil.runAsynchronously(new Runnable() {
      public void run() {
        try {
          byte[] audio = readMedia(audioFile);
          final String text = client.transcribe(key, audioFile, audio, model, lang);
          activity.runOnUiThread(new Runnable() {
            public void run() {
              GotTranscript(text);
            }
          });
        } catch (Exception e) {
          fail("Transcribe", e);
        }
      }
    });
  }

  @SimpleEvent(description = "The text from Transcribe.")
  public void GotTranscript(String text) {
    EventDispatcher.dispatchEvent(this, "GotTranscript", text);
  }

  // ---------------------------------------------------------------------------------------------
  // Models and account

  @SimpleFunction(description = "Gets the live model list for kind: text, vision, image, video, "
      + "speech or transcription. The names arrive in GotModels, ready for a ListPicker.")
  public void GetModels(final String kind) {
    AsynchUtil.runAsynchronously(new Runnable() {
      public void run() {
        try {
          final List<String> names = client.models(kind);
          activity.runOnUiThread(new Runnable() {
            public void run() {
              GotModels(kind, YailList.makeList(names));
            }
          });
        } catch (Exception e) {
          fail("GetModels", e);
        }
      }
    });
  }

  @SimpleEvent(description = "Model names from GetModels.")
  public void GotModels(String kind, YailList models) {
    EventDispatcher.dispatchEvent(this, "GotModels", kind, models);
  }

  @SimpleFunction(description = "Gets how much Pollen the signed-in user has left. The amount "
      + "arrives in GotBalance.")
  public void GetBalance() {
    final String key = activeKey();
    AsynchUtil.runAsynchronously(new Runnable() {
      public void run() {
        try {
          final double pollen = client.balance(key);
          activity.runOnUiThread(new Runnable() {
            public void run() {
              GotBalance(pollen);
            }
          });
        } catch (Exception e) {
          fail("GetBalance", e);
        }
      }
    });
  }

  @SimpleEvent(description = "Pollen left for the signed-in user.")
  public void GotBalance(double pollen) {
    EventDispatcher.dispatchEvent(this, "GotBalance", pollen);
  }

  @SimpleEvent(description = "Something went wrong. action is the block that failed, message "
      + "says why (for example HTTP 402 when the user is out of Pollen, or 401 when they need "
      + "to sign in again).")
  public void ErrorOccurred(String action, String message) {
    EventDispatcher.dispatchEvent(this, "ErrorOccurred", action, message);
  }

  // ---------------------------------------------------------------------------------------------
  // Lifecycle and helpers

  @Override
  public void onDestroy() {
    signInCancelled = true;
    releaseRecorder();
  }

  private void releaseRecorder() {
    if (recorder != null) {
      try {
        recorder.release();
      } catch (RuntimeException ignored) {
        // already released
      }
      recorder = null;
    }
  }

  private String activeKey() {
    return userKey.length() > 0 ? userKey : apiKey;
  }

  private SharedPreferences prefs() {
    return activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
  }

  private File cacheFile(String prefix, String ext) {
    File dir = new File(activity.getCacheDir(), "pollinations");
    if (!dir.exists()) {
      dir.mkdirs();
    }
    // Keep the cache small: drop our files older than one day.
    File[] old = dir.listFiles();
    long cutoff = System.currentTimeMillis() - 24L * 3600 * 1000;
    if (old != null) {
      for (File f : old) {
        if (f.lastModified() < cutoff) {
          f.delete();
        }
      }
    }
    return new File(dir, prefix + "-" + System.currentTimeMillis() + ext);
  }

  private void fail(final String action, Exception e) {
    String msg = e.getMessage();
    if (msg == null || msg.length() == 0) {
      msg = e.getClass().getSimpleName();
    }
    if (e instanceof PollinationsClient.ApiException) {
      PollinationsClient.ApiException api = (PollinationsClient.ApiException) e;
      if (api.status == 401 && activeKey().length() == 0) {
        msg = "Sign in first (call SignIn). " + msg;
      } else if (api.status == 401) {
        msg = "The key was rejected or expired. Call SignIn again. " + msg;
      } else if (api.status == 402) {
        msg = "Not enough Pollen. Top up at enter.pollinations.ai. " + msg;
      }
    }
    final String finalMsg = msg;
    activity.runOnUiThread(new Runnable() {
      public void run() {
        ErrorOccurred(action, finalMsg);
      }
    });
  }

  private byte[] readMedia(String path) throws IOException {
    if (path == null || path.length() == 0) {
      throw new IOException("No file given");
    }
    File f = new File(path);
    InputStream is = f.isAbsolute() && f.exists()
        ? new java.io.FileInputStream(f) : MediaUtil.openMedia(form, path);
    return PollinationsClient.readAll(is);
  }

  /** Loads a picture, scales it to at most 1024 px and returns a JPEG data URL. */
  private String imageToDataUrl(String path) throws IOException {
    byte[] raw = readMedia(path);
    BitmapFactory.Options bounds = new BitmapFactory.Options();
    bounds.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
    if (bounds.outWidth <= 0) {
      throw new IOException("Not a picture: " + path);
    }
    int sample = 1;
    while (Math.max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1024) {
      sample *= 2;
    }
    BitmapFactory.Options opts = new BitmapFactory.Options();
    opts.inSampleSize = sample;
    Bitmap bmp = BitmapFactory.decodeByteArray(raw, 0, raw.length, opts);
    if (bmp == null) {
      throw new IOException("Could not read picture: " + path);
    }
    int max = Math.max(bmp.getWidth(), bmp.getHeight());
    if (max > 1024) {
      float scale = 1024f / max;
      Bitmap scaled = Bitmap.createScaledBitmap(bmp, Math.round(bmp.getWidth() * scale),
          Math.round(bmp.getHeight() * scale), true);
      bmp.recycle();
      bmp = scaled;
    }
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    bmp.compress(Bitmap.CompressFormat.JPEG, 85, out);
    bmp.recycle();
    return "data:image/jpeg;base64," + PollinationsClient.base64(out.toByteArray());
  }
}
