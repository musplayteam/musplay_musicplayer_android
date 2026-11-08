package com.musplayteam.musplay;

import android.app.PendingIntent;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.Nullable;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;
import org.json.JSONObject;

/** Dedicated Android playback engine. The WebView never decodes local Android tracks. */
public final class MusPlayPlaybackService extends MediaSessionService {
  public static final String ACTION_PLAY = "com.musplayteam.musplay.PLAY";
  public static final String ACTION_PAUSE = "com.musplayteam.musplay.PAUSE";
  public static final String ACTION_RESUME = "com.musplayteam.musplay.RESUME";
  public static final String ACTION_NEXT = "com.musplayteam.musplay.NEXT";
  public static final String ACTION_PREVIOUS = "com.musplayteam.musplay.PREVIOUS";
  public static final String ACTION_STOP = "com.musplayteam.musplay.STOP";
  public static final String ACTION_SEEK = "com.musplayteam.musplay.SEEK";

  private static volatile MusPlayPlaybackService instance;
  private ExoPlayer player;
  private MediaSession mediaSession;
  private volatile String lastError = "";
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService artworkExecutor = Executors.newSingleThreadExecutor();
  private final Set<String> artworkTried = Collections.synchronizedSet(new HashSet<String>());

  @Override public void onCreate() {
    super.onCreate();
    instance = this;
    DefaultRenderersFactory renderers = new DefaultRenderersFactory(this)
      .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER);
    player = new ExoPlayer.Builder(this, renderers).build();
    player.setAudioAttributes(new AudioAttributes.Builder()
      .setUsage(C.USAGE_MEDIA)
      .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
      .build(), true); // Let Media3 request/release Android audio focus.
    player.setHandleAudioBecomingNoisy(true);
    player.addListener(new Player.Listener() {
      @Override public void onPlayerError(PlaybackException error) {
        lastError = "PlaybackException code=" + error.errorCode + ": " + error.getMessage();
      }
      @Override public void onPlaybackStateChanged(int state) {
        if (state == Player.STATE_READY) lastError = "";
      }
      @Override public void onMediaItemTransition(@Nullable MediaItem item, int reason) {
        prefetchArtworkAround();
      }
    });

    // Small status-bar icon for the media notification.
    DefaultMediaNotificationProvider provider = new DefaultMediaNotificationProvider.Builder(this).build();
    provider.setSmallIcon(R.drawable.ic_stat_musplay);
    setMediaNotificationProvider(provider);

    MediaSession.Builder sessionBuilder = new MediaSession.Builder(this, player);
    Intent launch = getPackageManager().getLaunchIntentForPackage(getPackageName());
    if (launch != null) {
      sessionBuilder.setSessionActivity(PendingIntent.getActivity(
        this, 0, launch, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE));
    }
    mediaSession = sessionBuilder.build();
    // IMPORTANT: this service is started with startService()/startForegroundService(), not bound by a
    // MediaController, so Media3 never learns about the session on its own. Without addSession() it never
    // builds the media notification nor calls startForeground(), and the shade shows no player card.
    addSession(mediaSession);
  }

  /** Embedded cover art for the current track and its neighbours (shown in the notification / lock screen). */
  private void prefetchArtworkAround() {
    if (player == null) return;
    int current = player.getCurrentMediaItemIndex();
    ensureArtwork(current);
    ensureArtwork(current + 1);
    ensureArtwork(current - 1);
  }

  private void ensureArtwork(int index) {
    if (player == null || index < 0 || index >= player.getMediaItemCount()) return;
    MediaItem item = player.getMediaItemAt(index);
    if (item.mediaMetadata.artworkData != null || item.localConfiguration == null) return;
    final String mediaId = item.mediaId;
    final Uri uri = item.localConfiguration.uri;
    if (!artworkTried.add(mediaId)) return;
    artworkExecutor.execute(() -> {
      final byte[] art = readEmbeddedArtwork(uri);
      if (art == null) return;
      main.post(() -> {
        if (player == null) return;
        for (int i = 0; i < player.getMediaItemCount(); i++) {
          MediaItem existing = player.getMediaItemAt(i);
          if (!mediaId.equals(existing.mediaId)) continue;
          MediaMetadata metadata = existing.mediaMetadata.buildUpon()
            .setArtworkData(art, MediaMetadata.PICTURE_TYPE_FRONT_COVER).build();
          player.replaceMediaItem(i, existing.buildUpon().setMediaMetadata(metadata).build());
          break;
        }
      });
    });
  }

  private byte[] readEmbeddedArtwork(Uri uri) {
    MediaMetadataRetriever retriever = new MediaMetadataRetriever();
    try {
      if ("file".equals(uri.getScheme())) retriever.setDataSource(uri.getPath());
      else retriever.setDataSource(this, uri);
      byte[] raw = retriever.getEmbeddedPicture();
      if (raw == null || raw.length == 0) return null;
      BitmapFactory.Options bounds = new BitmapFactory.Options();
      bounds.inJustDecodeBounds = true;
      BitmapFactory.decodeByteArray(raw, 0, raw.length, bounds);
      if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null;
      int sample = 1;
      while (bounds.outWidth / sample > 1024 || bounds.outHeight / sample > 1024) sample *= 2;
      if (sample == 1 && raw.length < 1500000) return raw;
      BitmapFactory.Options decode = new BitmapFactory.Options();
      decode.inSampleSize = sample;
      Bitmap bitmap = BitmapFactory.decodeByteArray(raw, 0, raw.length, decode);
      if (bitmap == null) return null;
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out);
      bitmap.recycle();
      return out.toByteArray();
    } catch (Exception ignored) {
      return null;
    } finally {
      try { retriever.release(); } catch (Exception ignored) {}
    }
  }


  @Nullable @Override public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
    return mediaSession;
  }

  @Override public int onStartCommand(Intent intent, int flags, int startId) {
    if (intent != null) {
      String action = intent.getAction();
      if (ACTION_PLAY.equals(action)) startTrack(intent);
      else if (ACTION_PAUSE.equals(action)) player.pause();
      else if (ACTION_RESUME.equals(action)) { lastError = ""; player.play(); }
      else if (ACTION_NEXT.equals(action)) { if (player.hasNextMediaItem()) player.seekToNextMediaItem(); }
      else if (ACTION_PREVIOUS.equals(action)) {
        if (player.hasPreviousMediaItem()) player.seekToPreviousMediaItem(); else player.seekTo(0);
      }
      else if (ACTION_SEEK.equals(action)) player.seekTo(Math.max(0L, intent.getLongExtra("position", 0L)));
      else if (ACTION_STOP.equals(action)) { player.stop(); player.clearMediaItems(); stopSelf(); }
    }
    return super.onStartCommand(intent, flags, startId);
  }

  private MediaItem buildItem(JSONObject entry) throws Exception {
    String path = entry.optString("path", "");
    if (path.isEmpty()) throw new IllegalArgumentException("Пустой путь к аудиофайлу");
    Uri uri;
    if (path.startsWith("content://")) {
      uri = Uri.parse(path);
    } else {
      File file = new File(path);
      if (!file.isFile() || !file.canRead() || file.length() <= 0) {
        throw new IllegalArgumentException("Аудиофайл не найден или недоступен: " + file.getName());
      }
      uri = Uri.fromFile(file);
    }
    MediaMetadata metadata = new MediaMetadata.Builder()
      .setTitle(entry.optString("title", "MusPlay"))
      .setArtist(entry.optString("artist", ""))
      .setAlbumTitle(entry.optString("album", ""))
      .build();
    return new MediaItem.Builder()
      .setMediaId(entry.optString("id", path))
      .setUri(uri)
      .setMediaMetadata(metadata)
      .build();
  }

  private void startTrack(Intent intent) {
    lastError = "";
    String selectedPath = intent.getStringExtra("path");
    if (selectedPath == null || selectedPath.trim().isEmpty()) {
      lastError = "Не передан путь к аудиофайлу";
      return;
    }
    try {
      ArrayList<MediaItem> items = new ArrayList<>();
      int selectedIndex = -1;
      String queueJson = intent.getStringExtra("queue");
      if (queueJson != null && !queueJson.isEmpty()) {
        JSONArray queue = new JSONArray(queueJson);
        for (int i = 0; i < queue.length(); i++) {
          JSONObject entry = queue.getJSONObject(i);
          String path = entry.optString("path", "");
          if (path.isEmpty()) continue;
          try {
            MediaItem item = buildItem(entry);
            if (path.equals(selectedPath)) selectedIndex = items.size();
            items.add(item);
          } catch (Exception unavailableQueueItem) {
            // A stale item in the saved queue must not prevent the selected track from playing.
          }
        }
      }
      if (items.isEmpty() || selectedIndex < 0) {
        JSONObject current = new JSONObject();
        current.put("path", selectedPath);
        current.put("id", intent.getStringExtra("trackId") == null ? selectedPath : intent.getStringExtra("trackId"));
        current.put("title", intent.getStringExtra("title") == null ? "MusPlay" : intent.getStringExtra("title"));
        current.put("artist", intent.getStringExtra("artist") == null ? "" : intent.getStringExtra("artist"));
        current.put("album", intent.getStringExtra("album") == null ? "" : intent.getStringExtra("album"));
        MediaItem selected = buildItem(current);
        if (items.isEmpty()) items.add(selected); else items.add(0, selected);
        selectedIndex = 0;
      }
      player.setMediaItems(items, selectedIndex, C.TIME_UNSET);
      player.prepare();
      player.play(); // playWhenReady stays true while the file is BUFFERING.
      prefetchArtworkAround();
    } catch (Exception error) {
      lastError = error.getClass().getSimpleName() + ": " + error.getMessage();
      player.stop();
    }
  }

  @Override public void onTaskRemoved(@Nullable Intent rootIntent) {
    // MediaSessionService keeps playback alive while the player intends to play.
    if (player == null || (!player.getPlayWhenReady() && !player.isPlaying())) stopSelf();
  }

  public static long currentPosition() { return instance == null || instance.player == null ? 0L : instance.player.getCurrentPosition(); }
  public static long currentDuration() { return instance == null || instance.player == null || instance.player.getDuration() < 0 ? 0L : instance.player.getDuration(); }
  public static boolean isPlayingNow() { return instance != null && instance.player != null && instance.player.isPlaying(); }
  public static boolean playWhenReady() { return instance != null && instance.player != null && instance.player.getPlayWhenReady(); }
  public static int playbackState() { return instance == null || instance.player == null ? Player.STATE_IDLE : instance.player.getPlaybackState(); }
  public static String currentTrackId() {
    return instance == null || instance.player == null || instance.player.getCurrentMediaItem() == null ? "" : instance.player.getCurrentMediaItem().mediaId;
  }
  public static String lastError() { return instance == null ? "" : instance.lastError; }

  @Override public void onDestroy() {
    artworkExecutor.shutdownNow();
    if (mediaSession != null) { removeSession(mediaSession); mediaSession.release(); mediaSession = null; }
    if (player != null) { player.release(); player = null; }
    instance = null;
    super.onDestroy();
  }
}
