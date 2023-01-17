package com.vinzscam.rntusclient;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.net.Uri;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;

import io.tus.android.client.TusPreferencesURLStore;
import io.tus.android.client.TusAndroidUpload;
import io.tus.java.client.ProtocolException;
import io.tus.java.client.TusClient;
import io.tus.java.client.TusExecutor;
import io.tus.java.client.TusUpload;
import io.tus.java.client.TusUploader;
import android.util.Log;

public class RNTusClientModule extends ReactContextBaseJavaModule {
  private static final String TAG = "RNTusClient";

  private final String ON_ERROR = "onError";
  private final String ON_SUCCESS = "onSuccess";
  private final String ON_PROGRESS = "onProgress";

  private final ReactApplicationContext reactContext;
  private Map<String, TusRunnable> executorsMap;
  private ExecutorService pool;

  public RNTusClientModule(ReactApplicationContext reactContext) {
    super(reactContext);
    this.reactContext = reactContext;
    this.executorsMap = new HashMap<String, TusRunnable>();
    pool = Executors.newFixedThreadPool(Runtime.getRuntime().availableProcessors());
  }

  @Override
  public String getName() {
    return "RNTusClient";
  }

  @ReactMethod
  public void createUpload(String fileUrl, ReadableMap options, Callback callback) {
    String endpoint = options.getString("endpoint");
    Map<String, Object> rawHeaders = options.getMap("headers").toHashMap();
    Map<String, Object> rawMetadata = options.getMap("metadata").toHashMap();

    Map<String, String> metadata = new HashMap<>();
    for (String key : rawMetadata.keySet()) {
      metadata.put(key, String.valueOf(rawMetadata.get(key)));
    }
    Map<String, String> headers = new HashMap<>();
    for (String key : rawHeaders.keySet()) {
      headers.put(key, String.valueOf(rawHeaders.get(key)));
    }

    try {
      String uploadId = UUID.randomUUID().toString();
      TusRunnable executor = new TusRunnable(fileUrl, uploadId, endpoint, metadata, headers);
      this.executorsMap.put(uploadId, executor);
      Log.d(TAG, "CREATE UPLOAD " + uploadId);

      callback.invoke(uploadId);
    } catch (FileNotFoundException | MalformedURLException e) {
      callback.invoke((Object) null, e.getMessage());
    }
  }

  @ReactMethod
  public void resume(String uploadId, Callback callback) {
    TusRunnable executor = this.executorsMap.get(uploadId);
    if (executor != null) {
      Log.d(TAG, "on resume upload");

      pool.submit(executor);
      callback.invoke(true);
    } else {
      callback.invoke(false);
    }
  }
  
  @ReactMethod
  public void abort(String uploadId, Callback callback) {
    try {
      TusRunnable executor = this.executorsMap.get(uploadId);
      if (executor != null) {
        executor.finish();
      }
      callback.invoke((Object) null);
    } catch (IOException | ProtocolException e) {
      callback.invoke(e);
    }
  }

  class TusRunnable extends TusExecutor implements Runnable {
    private TusUpload upload;
    private TusUploader uploader;
    private String uploadId;
    private String uploadEndPoint;
    private TusClient client;
    private boolean shouldFinish;
    private boolean isRunning;
    private Map<String, String> headers;
    private Timer progressTicker;
    private long offset = 0;

    public TusRunnable(String fileUrl, String uploadId, String endpoint, Map<String, String> metadata,
        Map<String, String> headers) throws FileNotFoundException, MalformedURLException {
      this.uploadId = uploadId;
      this.uploadEndPoint = endpoint;
      this.headers = headers;

      client = new TusClient();

      Uri uri = Uri.parse(fileUrl);
      SharedPreferences pref = reactContext.getSharedPreferences("tus", 0);
      client.enableResuming(new TusPreferencesURLStore(pref));

      if (uri.getScheme().equals(ContentResolver.SCHEME_CONTENT)) {
          Log.d(TAG, "Creating TusAndroidUpload");
          upload = new TusAndroidUpload(uri, reactContext);
      } else {
          Log.d(TAG, "Creating generic TusUpload");
          URI javaUri = URI.create(fileUrl);
          upload = new TusUpload(new File(javaUri));
      }

      upload.setMetadata(metadata);

      shouldFinish = false;
      isRunning = false;
    }

    protected void makeAttempt() throws ProtocolException, IOException {
      // uploader = client.resumeOrCreateUpload(upload);
      // client.setHeaders(updatedHeaders);
      headers.put("Upload-Offset", String.valueOf(offset));
      client.setHeaders(headers);
      uploader = client.beginOrResumeUploadFromURL(upload, new URL(uploadEndPoint));

      uploader.setChunkSize(2048);
      uploader.setRequestPayloadSize(2 * 1024 * 1024);
      Log.d(TAG, "attempt upload " + client.getHeaders());

      progressTicker = new Timer();

      progressTicker.scheduleAtFixedRate(new TimerTask() {
        @Override
        public void run() {
          sendProgressEvent(upload.getSize(), uploader.getOffset());
          Log.d(TAG, "attempt progress " + uploader.getOffset());
          offset = uploader.getOffset();
        }
      }, 0, 500);

      do {
      } while (uploader.uploadChunk() > -1 && !shouldFinish);

      progressTicker.cancel();
      sendProgressEvent(upload.getSize(), upload.getSize());

      uploader.finish();
    }

    private void sendProgressEvent(long bytesTotal, long bytesUploaded) {
      WritableMap params = Arguments.createMap();

      params.putString("uploadId", uploadId);
      params.putDouble("bytesWritten", bytesUploaded);
      params.putDouble("bytesTotal", bytesTotal);

      reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(ON_PROGRESS, params);
    }

    public void finish() throws ProtocolException, IOException {
      if (isRunning) {
        shouldFinish = true;
      } else {
        if (uploader != null) {
          uploader.finish();
        }
      }
    }

    @Override
    public void run() {
      isRunning = true;
      try {
        makeAttempts();
        String uploadUrl = uploader.getUploadURL().toString();
        executorsMap.remove(this.uploadId);
        WritableMap params = Arguments.createMap();
        params.putString("uploadId", uploadId);
        params.putString("uploadUrl", uploadUrl);
        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(ON_SUCCESS, params);
      } catch (ProtocolException | IOException e) {
        progressTicker.cancel();

        WritableMap params = Arguments.createMap();
        params.putString("uploadId", uploadId);
        params.putString("error", e.toString());

        reactContext.getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class).emit(ON_ERROR, params);
      }
      isRunning = false;
    }
  }
}