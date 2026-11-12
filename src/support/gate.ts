export const SUPPORT_KEY = 'silvercam-support';
const AUTOSTART_KEY = 'silvercam-autostart';

export const WECHAT_PAY = 'wxp://f2f1fet-2WiM3dHXoNfvUwH8Rc48iKwE0_bB9vt9hGmRH4hRXFjEM48TYNnzi0D50Tck';
export const ALIPAY_PAY = 'https://qr.alipay.com/fkx16502aqncntlqz5etm0c';

export type SupportState = 'awaiting' | 'skipped' | 'returned';

interface StorageLike {
  getItem(key: string): string | null;
  setItem(key: string, value: string): void;
  removeItem(key: string): void;
}

function storage(): StorageLike | null {
  try {
    return sessionStorage;
  } catch {
    return null;
  }
}

export function readSupport(store: StorageLike | null = storage()): string | null {
  try {
    return store?.getItem(SUPPORT_KEY) ?? null;
  } catch {
    return null;
  }
}

export function writeSupport(value: SupportState, store: StorageLike | null = storage()) {
  try {
    store?.setItem(SUPPORT_KEY, value);
    return true;
  } catch {
    return false;
  }
}

export function canDownload(store: StorageLike | null = storage()) {
  const value = readSupport(store);
  return value === 'skipped' || value === 'returned';
}

export function armDownload(value: SupportState, store: StorageLike | null = storage()) {
  const saved = writeSupport(value, store);
  try {
    store?.setItem(AUTOSTART_KEY, '1');
  } catch {
    /* The download page still shows the direct file link. */
  }
  return saved;
}

export function consumeAutostart(store: StorageLike | null = storage()) {
  try {
    const armed = store?.getItem(AUTOSTART_KEY) === '1';
    if (armed) store?.removeItem(AUTOSTART_KEY);
    return Boolean(armed);
  } catch {
    return false;
  }
}

export interface ApkChoice {
  href: string | null;
  /** True only when a real file exists. A missing APK must not be sent back to the coffee page. */
  available: boolean;
  /** Storage can fail closed; the file link still works without remembering the choice. */
  remembered: boolean;
}

export function resolveApk(href: string | null | undefined, store: StorageLike | null = storage()): ApkChoice {
  if (!href) return { href: null, available: false, remembered: canDownload(store) };
  return { href, available: true, remembered: canDownload(store) };
}
