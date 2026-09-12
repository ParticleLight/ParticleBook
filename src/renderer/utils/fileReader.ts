// Resolve the result of electronAPI.readFile. Since the C++ side serves every
// format via the virtual host (http://particlebook.app/_pb_files/…), the result
// may be either a plain byte array or a { _pb_url } object that must be fetched.
// This helper normalizes both into a Uint8Array (or null on failure).
function isVirtualHostRef(c: PbFileContent): c is PbVirtualHostRef {
  return typeof c === 'object' && c !== null && '_pb_url' in c
}

export async function readBookFile(filePath: string): Promise<Uint8Array | null> {
  const content = await window.electronAPI.readFile(filePath)
  // 必须先判空：C++ 读不到文件时返回 null，而 new Uint8Array(null) 不会抛错、
  // 只会得到长度 0 的数组 —— 且空数组是 truthy，调用方的 if (!content) 拦不住，
  // 于是"文件读不到"会被当成"这本书是空的"（ReaderView 的失败恢复分支因此失效）。
  if (content === null || content === undefined) {
    console.error('readBookFile: bridge returned null for', filePath)
    return null
  }
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
