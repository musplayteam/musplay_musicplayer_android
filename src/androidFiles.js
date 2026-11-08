const isAndroid = () => {
  const cap = window.Capacitor;
  const platform = cap?.getPlatform?.() || cap?.platform;
  return platform === 'android' || Boolean(cap?.isNativePlatform?.() && /Android/i.test(navigator.userAgent));
};
const plugin = () => {
  const value = window.Capacitor?.Plugins?.MusPlayFiles;
  if (!value) throw new Error('Нативный модуль MusPlayFiles не зарегистрирован. Выполните npm run android:sync и переустановите APK.');
  return value;
};

async function materialize(item) {
  const path = item?.path;
  if (!path) throw new Error('Android не вернул путь к выбранному файлу.');
  const url = window.Capacitor.convertFileSrc(path);
  const response = await fetch(url);
  if (!response.ok) throw new Error(`Не удалось прочитать ${item.name || 'аудиофайл'}.`);
  const blob = await response.blob();
  const file = new File([blob], item.name || 'audio-file', { type: item.mime || blob.type || 'audio/*', lastModified: Date.now() });
  // Keep a stable app-private native path and the original SAF URI for native playback/tag writes.
  Object.defineProperties(file, { androidNativePath: { value: item.nativePath || item.path, enumerable: true }, androidContentUri: { value: item.uri || '', enumerable: true } });
  return file;
}

export async function pickAndroidAudioFiles() {
  if (!isAndroid()) return null;
  const result = await plugin().pickAudioFiles();
  const files = [];
  // Avoid loading many large audio files into WebView memory simultaneously.
  for (const item of (result.files || [])) files.push(await materialize(item));
  return files;
}

export async function pickAndroidMusicFolder() {
  if (!isAndroid()) return null;
  const result = await plugin().pickMusicFolder();
  const files = [];
  // Sequential conversion prevents a large folder import from exhausting WebView RAM.
  for (const item of (result.files || [])) files.push(await materialize(item));
  return { folder: result.folder, files };
}

export async function importAndroidUri(uri) {
  if (!isAndroid() || !uri) return [];
  const result = await plugin().importIncomingUri({ uri });
  return [await materialize(result.file)];
}

export async function writeAndroidMp3Tags(track, fields) {
  if (!isAndroid()) return null;
  return plugin().writeMp3Tags({ path: track?.sourcePath || '', uri: track?.androidContentUri || '', fields });
}

export async function playAndroidTrack(track, queue = []) {
  if (!isAndroid() || !track) return null;
  // Media-session notifications are exempt from Android 13+ POST_NOTIFICATIONS.
  // Do not block playback on this permission: Media3 publishes the system media card.
  const path = track.sourcePath || track.androidNativePath;
  if (!path) throw new Error('Для фонового воспроизведения не найден локальный путь к файлу. Импортируйте трек заново.');
  return plugin().playNative({ path, trackId: track.id || '', title: track.title || track.fileName || 'MusPlay', artist: track.artist || '', album: track.album || '', cover: typeof track.cover === 'string' ? track.cover : '', queue: queue.map(item => ({ id: item.id, path: item.sourcePath || '', title: item.title || item.fileName || 'MusPlay', artist: item.artist || '', album: item.album || '' })).filter(item => item.path) });
}

export async function getAndroidPlaybackState() {
  if (!isAndroid()) return null;
  return plugin().getNativeState();
}


export async function controlAndroidPlayback(action, position = 0) {
  if (!isAndroid()) return null;
  return plugin().controlNative({ action, position: Math.round(position) });
}

export async function showAndroidNotification(title, body, id = 1001) {
  if (!isAndroid()) return null;
  const permission = await plugin().requestNotificationPermission();
  if (!permission?.granted) return { skipped: true, reason: 'notification-permission-denied' };
  return plugin().showNotification({ title, body, id });
}

export async function cancelAndroidNotification(id = 1001) {
  if (!isAndroid()) return null;
  return plugin().cancelNotification({ id });
}

export async function saveAndroidText(fileName, content) {
  if (!isAndroid()) return null;
  return plugin().saveTextFile({ fileName, content });
}

export { isAndroid };
