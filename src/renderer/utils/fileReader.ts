// Resolve the result of electronAPI.readFile. Since the C++ side serves every
// format via the virtual host (http://particlebook.app/_pb_files/…), the result
// may be either a plain byte array or a { _pb_url } object that must be fetched.
// This helper normalizes both into a Uint8Array (or null on failure).
function isVirtualHostRef(c: PbFileContent): c is PbVirtualHostRef {
  return typeof c === 'object' && c !== null && '_pb_url' in c
}

export async function readBookFile(filePath: string): Promise<Uint8Array | null> {
  const content = await window.electronAPI.readFile(filePath)
  if (isVirtualHostRef(content)) {
    try {
      const res = await fetch(content._pb_url)
      if (res.ok) return new Uint8Array(await res.arrayBuffer())
      console.error('readBookFile: virtual-host fetch failed', res.status)
      return null
    } catch (e) {
      console.error('Failed to fetch file via virtual host:', e)
      return null
    }
  }
  return new Uint8Array(content)
}
