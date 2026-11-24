interface NativeBridge {
  postMessage(message: string): void;
}

function getBridge(): NativeBridge | undefined {
  return (window as Window & { AisteeNative?: NativeBridge }).AisteeNative;
}

export function syncNativePrivacy(enabled: boolean): void {
  getBridge()?.postMessage(JSON.stringify({ type: 'privacy', enabled }));
}

const MAX_EXPORT_BYTES = 8 * 1024 * 1024;

export function downloadBlob(blob: Blob, name: string, notify: (message: string) => void): void {
  if (window.location.hostname === 'appassets.androidplatform.net') {
    const bridge = getBridge();
    if (!bridge) { notify('Native export unavailable: update Android WebView.'); return; }
    if (blob.size > MAX_EXPORT_BYTES) { notify('Export exceeds the 8 MiB native limit.'); return; }
    const reader = new FileReader();
    reader.onerror = () => notify('Export failed.');
    reader.onload = () => {
      try {
        const base64 = String(reader.result).split(',', 2)[1] ?? '';
        bridge.postMessage(JSON.stringify({ type: 'export', name, base64 }));
      } catch {
        notify('Native export failed.');
      }
    };
    reader.readAsDataURL(blob);
    return;
  }
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement('a');
  anchor.href = url;
  anchor.download = name;
  anchor.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
  notify('Download started.');
}
