import { isAndroid, showAndroidNotification, cancelAndroidNotification } from './androidFiles';

/** Reusable app notification facade. IDs can be reused to update/replace a notification. */
export async function notify(title, body = '', id = 1001) {
  if (isAndroid()) return showAndroidNotification(title, body, id);
  if (typeof Notification !== 'undefined' && Notification.permission === 'granted') {
    return new Notification(title, { body });
  }
  return { skipped: true, reason: 'notification-permission-not-granted' };
}

export async function dismissNotification(id = 1001) {
  if (isAndroid()) return cancelAndroidNotification(id);
  return { canceled: true };
}
