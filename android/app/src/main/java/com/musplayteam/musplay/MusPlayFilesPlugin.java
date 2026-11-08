package com.musplayteam.musplay;

import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.activity.result.ActivityResult;
import com.getcapacitor.*;
import com.getcapacitor.annotation.*;
import androidx.documentfile.provider.DocumentFile;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import java.io.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@CapacitorPlugin(name = "MusPlayFiles", permissions = {
  @com.getcapacitor.annotation.Permission(alias = "notifications", strings = { android.Manifest.permission.POST_NOTIFICATIONS })
})
public class MusPlayFilesPlugin extends Plugin {
  private static final int PICK_AUDIO = 8101;
  private static final int PICK_FOLDER = 8102;
  private static final int SAVE_TEXT = 8103;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private PluginCall pendingAudio, pendingFolder, pendingSave;
  private String pendingTextName, pendingTextContent;

  @PluginMethod public void pickAudioFiles(PluginCall call) {
    pendingAudio = call;
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(Intent.CATEGORY_OPENABLE);
    intent.setType("audio/*");
    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(call, intent, "audioResult");
  }
  @ActivityCallback private void audioResult(PluginCall call, ActivityResult result) {
    if (call == null) return;
    Intent data = result.getData();
    if (data == null || result.getResultCode() != android.app.Activity.RESULT_OK) { call.resolve(new JSObject().put("files", new JSArray())); return; }
    final Intent selected = data;
    io.execute(() -> {
      JSArray files = new JSArray();
      try {
        if (selected.getClipData() != null) {
          for (int i=0;i<selected.getClipData().getItemCount();i++) files.put(copyUri(selected.getClipData().getItemAt(i).getUri()));
        } else if (selected.getData() != null) files.put(copyUri(selected.getData()));
        call.resolve(new JSObject().put("files", files));
      } catch(Exception e) { call.reject("Не удалось прочитать выбранные аудиофайлы", e); }
    });
  }

  @PluginMethod public void pickMusicFolder(PluginCall call) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    startActivityForResult(call, intent, "folderResult");
  }
  @ActivityCallback private void folderResult(PluginCall call, ActivityResult result) {
    if (call == null) return;
    Intent data = result.getData();
    if (data == null || result.getResultCode() != android.app.Activity.RESULT_OK || data.getData() == null) { call.resolve(new JSObject().put("files", new JSArray())); return; }
    Uri tree = data.getData();
    io.execute(() -> {
      try {
        getContext().getContentResolver().takePersistableUriPermission(tree, Intent.FLAG_GRANT_READ_URI_PERMISSION);
        JSArray files = new JSArray();
        walk(DocumentFile.fromTreeUri(getContext(), tree), files, 0);
        call.resolve(new JSObject().put("folder", tree.toString()).put("files", files));
      } catch(Exception e) { call.reject("Не удалось прочитать музыкальную папку", e); }
    });
  }
  private void walk(DocumentFile dir, JSArray out, int depth) throws Exception {
    if (dir == null || depth > 12 || out.length() >= 2000) return;
    for (DocumentFile f : dir.listFiles()) {
      if (f.isDirectory()) walk(f, out, depth+1);
      else if (f.isFile() && (f.getType() == null || f.getType().startsWith("audio/") || f.getName().matches("(?i).+\\.(mp3|flac|wav|m4a|aac|ogg|opus|wma|aiff|ape|alac|weba)$"))) {
        JSObject item = copyUri(f.getUri()); out.put(item);
      }
      if (out.length() >= 2000) break;
    }
  }
  private JSObject copyUri(Uri uri) throws Exception {
    String name = "audio-" + System.currentTimeMillis();
    Cursor c = getContext().getContentResolver().query(uri, null, null, null, null);
    if (c != null) { try { if (c.moveToFirst()) { int i=c.getColumnIndex(OpenableColumns.DISPLAY_NAME); if(i>=0) name=c.getString(i); } } finally { c.close(); } }
    File dir = new File(getContext().getFilesDir(), "musplay-imports"); dir.mkdirs();
    File target = new File(dir, UUID.randomUUID().toString()+"-"+name.replaceAll("[^a-zA-Z0-9._ -]", "_"));
    try { getContext().getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
    try (InputStream in=getContext().getContentResolver().openInputStream(uri); OutputStream out=new FileOutputStream(target)) {
      if (in == null) throw new IOException("Empty input"); byte[] b=new byte[65536]; int n; while((n=in.read(b))>0) out.write(b,0,n);
    }
    JSObject o=new JSObject(); o.put("name",name); o.put("path",target.getAbsolutePath()); o.put("mime",getContext().getContentResolver().getType(uri)); o.put("size",target.length()); o.put("uri",uri.toString()); o.put("nativePath",target.getAbsolutePath()); return o;
  }

  @PluginMethod public void getPendingIncomingUri(PluginCall call) {
    String uri = getContext().getSharedPreferences("musplay", 0).getString("pending_audio_uri", "");
    getContext().getSharedPreferences("musplay", 0).edit().remove("pending_audio_uri").apply();
    call.resolve(new JSObject().put("uri", uri));
  }

  @PluginMethod public void importIncomingUri(PluginCall call) {
    String value = call.getString("uri", "");
    io.execute(() -> {
      try { call.resolve(new JSObject().put("file", copyUri(Uri.parse(value)))); }
      catch(Exception e) { call.reject("Не удалось импортировать переданный аудиофайл", e); }
    });
  }

  @PluginMethod public void saveTextFile(PluginCall call) {
    pendingTextName = call.getString("fileName", "lyrics.lrc"); pendingTextContent = call.getString("content", "");
    startActivityForResult(call, new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("text/plain").putExtra(Intent.EXTRA_TITLE, pendingTextName), "saveResult");
  }
  @ActivityCallback private void saveResult(PluginCall call, ActivityResult result) {
    if (call == null) return; Uri uri=result.getData()==null?null:result.getData().getData();
    if(uri==null || result.getResultCode()!=android.app.Activity.RESULT_OK){ call.resolve(new JSObject().put("canceled",true)); return; }
    try(OutputStream out=getContext().getContentResolver().openOutputStream(uri)){ out.write((pendingTextContent==null?"":pendingTextContent).getBytes("UTF-8")); call.resolve(new JSObject().put("uri",uri.toString()).put("saved",true)); }
    catch(Exception e){call.reject("Не удалось сохранить файл",e);}
  }


  @PluginMethod public void playNative(PluginCall call) {
    String path = call.getString("path", "");
    if (path.isEmpty()) { call.reject("Не найден локальный файл для фонового воспроизведения"); return; }
    Intent intent = new Intent(getContext(), MusPlayPlaybackService.class)
      .setAction(MusPlayPlaybackService.ACTION_PLAY)
      .putExtra("path", path)
      .putExtra("trackId", call.getString("trackId", ""))
      .putExtra("title", call.getString("title", "MusPlay"))
      .putExtra("artist", call.getString("artist", ""))
      .putExtra("album", call.getString("album", ""))
      .putExtra("cover", call.getString("cover", ""));
    JSArray queue = call.getArray("queue");
    if (queue != null) intent.putExtra("queue", queue.toString());
    try {
      // The app is in the foreground here; Media3 promotes the service to foreground itself once playback
      // starts. startForegroundService() would crash if no startForeground() follows within ~5 seconds.
      getContext().startService(intent);
    } catch (IllegalStateException backgroundRestricted) {
      androidx.core.content.ContextCompat.startForegroundService(getContext(), intent);
    }
    call.resolve();
  }
  @PluginMethod public void controlNative(PluginCall call) {
    String action = call.getString("action", "pause");
    long position = call.getData().optLong("position", 0L);
    Intent intent = new Intent(getContext(), MusPlayPlaybackService.class).setAction(action).putExtra("position", position);
    try { getContext().startService(intent); } catch (IllegalStateException ignored) { /* service not running and app in background */ }
    call.resolve();
  }
  @PluginMethod public void writeMp3Tags(PluginCall call) {
    String uriText = call.getString("uri", "");
    String path = call.getString("path", "");
    JSObject fieldsValue = call.getObject("fields");
    final JSObject fields = fieldsValue == null ? new JSObject() : fieldsValue;
    io.execute(() -> {
      File input = null, output = null;
      try {
        Uri uri = uriText.isEmpty() ? null : Uri.parse(uriText);
        if (uri != null && "content".equals(uri.getScheme())) {
          input = new File(getContext().getCacheDir(), "tag-input-" + UUID.randomUUID() + ".mp3");
          try (InputStream in = getContext().getContentResolver().openInputStream(uri); OutputStream out = new FileOutputStream(input)) {
            if (in == null) throw new IOException("Файл недоступен");
            byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
          }
        } else {
          input = new File(path);
          if (!input.isFile()) throw new IOException("MP3-файл не найден");
        }
        com.mpatric.mp3agic.Mp3File mp3 = new com.mpatric.mp3agic.Mp3File(input.getAbsolutePath());
        com.mpatric.mp3agic.ID3v2 tag = mp3.hasId3v2Tag() ? mp3.getId3v2Tag() : new com.mpatric.mp3agic.ID3v24Tag();
        tag.setTitle(fields.optString("title", ""));
        tag.setArtist(fields.optString("artist", ""));
        tag.setAlbum(fields.optString("album", ""));
        tag.setYear(fields.optString("year", ""));
        tag.setGenreDescription(fields.optString("genre", ""));
        mp3.setId3v2Tag(tag);
        output = new File(getContext().getCacheDir(), "tag-output-" + UUID.randomUUID() + ".mp3");
        mp3.save(output.getAbsolutePath());
        if (uri != null) {
          try (InputStream in = new FileInputStream(output); OutputStream out = getContext().getContentResolver().openOutputStream(uri, "wt")) {
            if (out == null) throw new IOException("Хранилище не разрешило перезапись файла");
            byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
          }
          // Keep MusPlay's private playback copy in sync with the original SAF document.
          if (!path.isEmpty()) {
            File privateCopy = new File(path);
            if (privateCopy.isFile()) try (InputStream in = new FileInputStream(output); OutputStream out = new FileOutputStream(privateCopy, false)) {
              byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
            }
          }
        } else {
          try (InputStream in = new FileInputStream(output); OutputStream out = new FileOutputStream(input, false)) {
            byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) > 0) out.write(buffer, 0, n);
          }
        }
        call.resolve(new JSObject().put("saved", true));
      } catch (Exception e) { call.reject("Не удалось записать ID3-теги. Возможно, хранилище запрещает изменение файла.", e); }
      finally { if (input != null && input.getParentFile() != null && input.getParentFile().equals(getContext().getCacheDir())) input.delete(); if (output != null) output.delete(); }
    });
  }

  @PluginMethod public void cancelNotification(PluginCall call) {
    NotificationManagerCompat.from(getContext()).cancel(call.getData().optInt("id", 1001));
    call.resolve(new JSObject().put("canceled", true));
  }
  @PluginMethod public void getNativeState(PluginCall call) {
    // Media3 ExoPlayer is application-thread confined. Capacitor invokes plugins on
    // CapacitorPlugins, so collect every Player property on Android's main thread.
    new Handler(Looper.getMainLooper()).post(() -> {
      int state = MusPlayPlaybackService.playbackState();
      JSObject result = new JSObject();
      result.put("position", MusPlayPlaybackService.currentPosition());
      result.put("duration", MusPlayPlaybackService.currentDuration());
      result.put("isPlaying", MusPlayPlaybackService.isPlayingNow());
      result.put("playWhenReady", MusPlayPlaybackService.playWhenReady());
      result.put("trackId", MusPlayPlaybackService.currentTrackId());
      result.put("playbackState", state);
      result.put("lastError", MusPlayPlaybackService.lastError());
      call.resolve(result);
    });
  }

  @PluginMethod public void requestNotificationPermission(PluginCall call) {
    if (Build.VERSION.SDK_INT < 33 || getPermissionState("notifications") == PermissionState.GRANTED) {
      call.resolve(new JSObject().put("granted", true));
    } else {
      requestPermissionForAlias("notifications", call, "notificationPermissionResult");
    }
  }
  @PermissionCallback private void notificationPermissionResult(PluginCall call) {
    call.resolve(new JSObject().put("granted", getPermissionState("notifications") == PermissionState.GRANTED));
  }
  @PluginMethod public void showNotification(PluginCall call) {
    String channelId = "musplay_general";
    NotificationManager manager = (NotificationManager) getContext().getSystemService(android.content.Context.NOTIFICATION_SERVICE);
    if (Build.VERSION.SDK_INT >= 26 && manager.getNotificationChannel(channelId) == null) {
      manager.createNotificationChannel(new NotificationChannel(channelId, "Уведомления MusPlay", NotificationManager.IMPORTANCE_DEFAULT));
    }
    android.content.Intent launch = getContext().getPackageManager().getLaunchIntentForPackage(getContext().getPackageName());
    android.app.PendingIntent pending = launch == null ? null : android.app.PendingIntent.getActivity(getContext(), 0, launch, android.app.PendingIntent.FLAG_UPDATE_CURRENT | android.app.PendingIntent.FLAG_IMMUTABLE);
    NotificationCompat.Builder builder = new NotificationCompat.Builder(getContext(), channelId)
      .setSmallIcon(getContext().getApplicationInfo().icon).setContentTitle(call.getString("title", "MusPlay"))
      .setContentText(call.getString("body", "")).setAutoCancel(true).setPriority(NotificationCompat.PRIORITY_DEFAULT);
    if (pending != null) builder.setContentIntent(pending);
    try { NotificationManagerCompat.from(getContext()).notify(call.getData().optInt("id", 1001), builder.build()); call.resolve(new JSObject().put("shown", true)); }
    catch (SecurityException e) { call.reject("Разрешите MusPlay показывать уведомления в настройках Android.", e); }
  }
}
