import { readBookFile } from './fileReader'

export async function generatePdfPreview(filePath: string): Promise<string | null> {
  try {
    const pdfjsLib = await import('pdfjs-dist')
    pdfjsLib.GlobalWorkerOptions.workerSrc = new URL('pdf.worker.min.mjs', window.location.href).href

    const content = await readBookFile(filePath)
    if (!content) return null
    const pdf = await pdfjsLib.getDocument({ data: content }).promise
    const page = await pdf.getPage(1)
    const viewport = page.getViewport({ scale: 1.5 })

    const canvas = document.createElement('canvas')
    canvas.width = viewport.width
    canvas.height = viewport.height
    const ctx = canvas.getContext('2d')!

    await page.render({ canvasContext: ctx, viewport }).promise
    return canvas.toDataURL('image/jpeg', 0.7)
  } catch {
    return null
  }
}

export async function generateCbzPreview(filePath: string): Promise<string | null> {
  try {
    const JSZip = (await import('jszip')).default
    const content = await readBookFile(filePath)
    if (!content) return null
    const zip = await JSZip.loadAsync(content)

    const imageFiles: string[] = []
    zip.forEach((path) => {
      if (/\.(jpg|jpeg|png|gif|webp)$/i.test(path)) imageFiles.push(path)
    })
    imageFiles.sort()

    if (imageFiles.length === 0) return null
    const blob = await zip.file(imageFiles[0])!.async('blob')
    return URL.createObjectURL(blob)
  } catch {
    return null
  }
}
