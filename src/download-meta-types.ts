export interface AndroidDownloadMetadata {
  filename: string;
  href: string;
  bytes: number;
  size: string;
  sha256: string;
  version: string;
  minAndroid: string;
}

export interface ArchiveDownloadMetadata {
  filename: string;
  href: string;
  bytes: number;
  size: string;
  sha256: string;
  fileCount: number;
}

export interface DownloadMetadata {
  android: AndroidDownloadMetadata | null;
  source: ArchiveDownloadMetadata;
  prototype: ArchiveDownloadMetadata;
}
