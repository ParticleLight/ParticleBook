// ── PB-018: E2E fixtures ─────────────────────────────────────────
// 生成最小可用的 EPUB（store-only ZIP，零依赖）。C++ 侧用 tinyxml2 解析
// container.xml / OPF，渲染层用 epubjs 解析同一份文件，所以 fixture 必须
// 同时满足两边：
//   * mimetype 必须是第一个条目且不压缩（EPUB 规范）；
//   * META-INF/container.xml 里 rootfile full-path 指向 .opf；
//   * OPF 2.0 + NCX（epubjs 对 EPUB2 的兼容性最好）。
// 另外导出 FNV-1a 64 帮助函数：应用打开书时会把文件复制到
// renderer/_pb_files/<fnv1a64(path)>（FileHandlers.cpp），E2E 结束后可以
// 只删本次 fixture 对应的缓存文件，不碰开发者既有的缓存。

import fs from 'node:fs/promises'
import path from 'node:path'

// ── CRC32（ZIP 需要）─────────────────────────────────────────────
const CRC_TABLE = (() => {
  const table = new Uint32Array(256)
  for (let n = 0; n < 256; n++) {
    let c = n
    for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1)
    table[n] = c >>> 0
  }
  return table
})()

export function crc32(buffer) {
  let c = 0xffffffff
  for (let i = 0; i < buffer.length; i++) c = CRC_TABLE[(c ^ buffer[i]) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}

// ── 最小 ZIP writer（全部 store，不压缩）────────────────────────
function dosDateTime(date = new Date(2020, 0, 1, 0, 0, 0)) {
  const year = Math.max(1980, date.getFullYear())
  return {
    time: (date.getHours() << 11) | (date.getMinutes() << 5) | (date.getSeconds() >> 1),
    date: ((year - 1980) << 9) | ((date.getMonth() + 1) << 5) | date.getDate(),
  }
}

export function zipStore(entries) {
  const chunks = []
  const central = []
  const { time, date } = dosDateTime()
  let offset = 0

  for (const entry of entries) {
    const name = Buffer.from(entry.name, 'utf8')
    const data = Buffer.isBuffer(entry.data) ? entry.data : Buffer.from(entry.data, 'utf8')
    const crc = crc32(data)

    const local = Buffer.alloc(30)
    local.writeUInt32LE(0x04034b50, 0)      // local file header signature
    local.writeUInt16LE(20, 4)              // version needed
    local.writeUInt16LE(0, 6)               // flags (names are ASCII)
    local.writeUInt16LE(0, 8)               // method 0 = store
    local.writeUInt16LE(time, 10)
    local.writeUInt16LE(date, 12)
    local.writeUInt32LE(crc, 14)
    local.writeUInt32LE(data.length, 18)
    local.writeUInt32LE(data.length, 22)
    local.writeUInt16LE(name.length, 26)
    local.writeUInt16LE(0, 28)              // extra length
    chunks.push(local, name, data)

    const cd = Buffer.alloc(46)
    cd.writeUInt32LE(0x02014b50, 0)         // central directory signature
    cd.writeUInt16LE(20, 4)                 // version made by
    cd.writeUInt16LE(20, 6)                 // version needed
    cd.writeUInt16LE(0, 8)                  // flags
    cd.writeUInt16LE(0, 10)                 // method
    cd.writeUInt16LE(time, 12)
    cd.writeUInt16LE(date, 14)
    cd.writeUInt32LE(crc, 16)
    cd.writeUInt32LE(data.length, 20)
    cd.writeUInt32LE(data.length, 24)
    cd.writeUInt16LE(name.length, 28)
    cd.writeUInt16LE(0, 30)                 // extra
    cd.writeUInt16LE(0, 32)                 // comment
    cd.writeUInt16LE(0, 34)                 // disk number
    cd.writeUInt16LE(0, 36)                 // internal attrs
    cd.writeUInt32LE(0, 38)                 // external attrs
    cd.writeUInt32LE(offset, 42)            // local header offset
    central.push(cd, name)

    offset += local.length + name.length + data.length
  }

  const centralStart = offset
  const centralSize = central.reduce((sum, b) => sum + b.length, 0)
  const eocd = Buffer.alloc(22)
  eocd.writeUInt32LE(0x06054b50, 0)
  eocd.writeUInt16LE(0, 4)
  eocd.writeUInt16LE(0, 6)
  eocd.writeUInt16LE(entries.length, 8)
  eocd.writeUInt16LE(entries.length, 10)
  eocd.writeUInt32LE(centralSize, 12)
  eocd.writeUInt32LE(centralStart, 16)
  eocd.writeUInt16LE(0, 20)

  return Buffer.concat([...chunks, ...central, eocd])
}

// ── FNV-1a 64（与应用 FileHandlers 的缓存文件名一致）───────────
export function fnv1a64Hex(text) {
  let hash = 0xcbf29ce484222325n
  const prime = 0x100000001b3n
  const mask = 0xffffffffffffffffn
  for (const byte of Buffer.from(text, 'utf8')) {
    hash ^= BigInt(byte)
    hash = (hash * prime) & mask
  }
  return hash.toString(16).padStart(16, '0')
}

// ── EPUB fixture ─────────────────────────────────────────────────
const xmlEscape = (text) => String(text)
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')

function chapterXhtml(title, paragraphs) {
  const body = paragraphs.map((paragraph) => `    <p>${xmlEscape(paragraph)}</p>`).join('\n')
  return `<?xml version="1.0" encoding="utf-8"?>
<!DOCTYPE html>
<html xmlns="http://www.w3.org/1999/xhtml">
  <head><title>${xmlEscape(title)}</title></head>
  <body>
    <h1>${xmlEscape(title)}</h1>
${body}
  </body>
</html>
`
}

/**
 * 生成一个最小 EPUB 2.0。
 * @param {{title: string, id?: string, chapter1: string, chapter2: string}} options
 *   chapter1/chapter2 是「正文段落」，会原样出现在渲染文本里，E2E 用它断言
 *   「已经读到这一页」。
 * @returns {Buffer}
 */
export function makeSampleEpub(options) {
  const title = options.title
  const bookId = options.id || `urn:uuid:${options.title.replace(/[^a-z0-9]+/gi, '-').toLowerCase()}`
  const chapter1 = options.chapter1
  const chapter2 = options.chapter2

  const container = `<?xml version="1.0" encoding="UTF-8"?>
<container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
  <rootfiles>
    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
  </rootfiles>
</container>
`

  const opf = `<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="bookid">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:opf="http://www.idpf.org/2007/opf">
    <dc:identifier id="bookid">${xmlEscape(bookId)}</dc:identifier>
    <dc:title>${xmlEscape(title)}</dc:title>
    <dc:creator>ParticleBook E2E</dc:creator>
    <dc:language>en</dc:language>
  </metadata>
  <manifest>
    <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
    <item id="ch1" href="Text/chapter1.xhtml" media-type="application/xhtml+xml"/>
    <item id="ch2" href="Text/chapter2.xhtml" media-type="application/xhtml+xml"/>
  </manifest>
  <spine toc="ncx">
    <itemref idref="ch1"/>
    <itemref idref="ch2"/>
  </spine>
  <guide>
    <reference type="text" title="Chapter 1" href="Text/chapter1.xhtml"/>
  </guide>
</package>
`

  const ncx = `<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1">
  <head>
    <meta name="dtb:uid" content="${xmlEscape(bookId)}"/>
    <meta name="dtb:depth" content="1"/>
  </head>
  <docTitle><text>${xmlEscape(title)}</text></docTitle>
  <navMap>
    <navPoint id="np1" playOrder="1">
      <navLabel><text>Chapter 1</text></navLabel>
      <content src="Text/chapter1.xhtml"/>
    </navPoint>
    <navPoint id="np2" playOrder="2">
      <navLabel><text>Chapter 2</text></navLabel>
      <content src="Text/chapter2.xhtml"/>
    </navPoint>
  </navMap>
</ncx>
`

  return zipStore([
    // mimetype 必须第一个且不压缩
    { name: 'mimetype', data: 'application/epub+zip' },
    { name: 'META-INF/container.xml', data: container },
    { name: 'OEBPS/content.opf', data: opf },
    { name: 'OEBPS/toc.ncx', data: ncx },
    { name: 'OEBPS/Text/chapter1.xhtml', data: chapterXhtml('Chapter 1', [chapter1]) },
    { name: 'OEBPS/Text/chapter2.xhtml', data: chapterXhtml('Chapter 2', [chapter2]) },
  ])
}

/**
 * 在 dir 下写一个 fixture EPUB，返回 E2E 断言需要的所有信息。
 * @param {string} dir
 * @param {string} fileName
 * @param {{title: string, ch1: string, ch2: string}} options
 */
export async function writeSampleEpub(dir, fileName, options) {
  await fs.mkdir(dir, { recursive: true })
  const filePath = path.join(dir, fileName)
  const buffer = makeSampleEpub({
    title: options.title,
    chapter1: options.ch1,
    chapter2: options.ch2,
  })
  await fs.writeFile(filePath, buffer)
  return { path: filePath, title: options.title, ch1: options.ch1, ch2: options.ch2 }
}

// ── 最小 1 页文本 PDF（零依赖；mutool / PdfService 可解析）─────────────
// PDF 全 ASCII（latin1），xref 偏移按字节精确计算；/Title 让导入元数据可断言。
const PDF_BACKSLASH = String.fromCharCode(92)
const pdfEscape = (text) => String(text)
  .split(PDF_BACKSLASH).join(PDF_BACKSLASH + PDF_BACKSLASH)
  .split('(').join(PDF_BACKSLASH + '(')
  .split(')').join(PDF_BACKSLASH + ')')

export function makeSamplePdf(options) {
  const title = options.title || 'ParticleBook E2E PDF'
  const text = options.text || 'ParticleBook E2E PDF text'
  const stream = 'BT /F1 18 Tf 72 700 Td (' + pdfEscape(text) + ') Tj ET'
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    '<< /Type /Pages /Kids [3 0 R] /Count 1 >>',
    '<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 4 0 R >>',
    '<< /Length ' + Buffer.byteLength(stream, 'latin1') + ' >>\nstream\n' + stream + '\nendstream',
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>',
    '<< /Title (' + pdfEscape(title) + ') /Author (ParticleBook E2E) /Producer (ParticleBook E2E) >>',
  ]
  let pdf = '%PDF-1.4\n'
  const offsets = [0]
  for (let i = 1; i <= objects.length; i++) {
    offsets[i] = Buffer.byteLength(pdf, 'latin1')
    pdf += i + ' 0 obj\n' + objects[i - 1] + '\nendobj\n'
  }
  const xrefOffset = Buffer.byteLength(pdf, 'latin1')
  pdf += 'xref\n0 ' + (objects.length + 1) + '\n'
  pdf += '0000000000 65535 f \n'
  for (let i = 1; i <= objects.length; i++) {
    pdf += String(offsets[i]).padStart(10, '0') + ' 00000 n \n'
  }
  pdf += 'trailer\n<< /Size ' + (objects.length + 1) + ' /Root 1 0 R /Info 6 0 R >>\nstartxref\n' + xrefOffset + '\n%%EOF\n'
  return Buffer.from(pdf, 'latin1')
}

export async function writeSamplePdf(dir, fileName, options) {
  await fs.mkdir(dir, { recursive: true })
  const filePath = path.join(dir, fileName)
  await fs.writeFile(filePath, makeSamplePdf(options))
  return { path: filePath, title: options.title, text: options.text }
}
