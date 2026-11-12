export const SUPPORT_KEY = 'silvercam-support';

export const WECHAT_PAY = 'wxp://f2f0PRfG5sR5_YaX8oLNV5e116MN7pM3aH0cJPmHVtDM0JlipryfATZ_HLURmc7EVSX0';
export const ALIPAY_PAY = 'https://qr.alipay.com/fkx158903vjfflc8oq5ae7a';

export type SupportState = 'awaiting' | 'skipped' | 'returned';

export function readSupport(): string | null {
  try {
    return sessionStorage.getItem(SUPPORT_KEY);
  } catch {
    return null;
  }
}

export function writeSupport(value: SupportState) {
  try {
    sessionStorage.setItem(SUPPORT_KEY, value);
  } catch {
    /* Storage may be disabled. The download page then sends the visitor back here. */
  }
}

export function canDownload() {
  const value = readSupport();
  return value === 'skipped' || value === 'returned';
}
