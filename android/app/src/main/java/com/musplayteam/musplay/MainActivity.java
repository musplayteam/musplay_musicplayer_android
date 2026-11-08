package com.musplayteam.musplay;

import android.content.Intent;
import android.net.Uri;
import com.getcapacitor.BridgeActivity;
import org.json.JSONObject;

public class MainActivity extends BridgeActivity {
  @Override public void onCreate(android.os.Bundle savedInstanceState) {
    registerPlugin(MusPlayFilesPlugin.class);
    super.onCreate(savedInstanceState);
    dispatchIncoming(getIntent());
  }
  @Override protected void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    dispatchIncoming(intent);
  }
  private void dispatchIncoming(Intent intent) {
    if (intent == null) return;
    Uri uri = intent.getData();
    if (uri == null && Intent.ACTION_SEND.equals(intent.getAction())) {
      Object stream = intent.getParcelableExtra(Intent.EXTRA_STREAM);
      if (stream instanceof Uri) uri = (Uri) stream;
    }
    if (uri == null && Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction()) && intent.getClipData() != null && intent.getClipData().getItemCount() > 0) {
      uri = intent.getClipData().getItemAt(0).getUri();
    }
    if (uri != null && ("content".equals(uri.getScheme()) || "file".equals(uri.getScheme()))) {
      getSharedPreferences("musplay", MODE_PRIVATE).edit().putString("pending_audio_uri", uri.toString()).apply();
      try {
        JSONObject payload = new JSONObject();
        payload.put("uri", uri.toString());
        getBridge().triggerWindowJSEvent("musplay:incoming-audio", payload.toString());
      } catch (Exception ignored) {}
    }
  }
}
