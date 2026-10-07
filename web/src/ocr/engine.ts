// 端侧 OCR 引擎边界：默认 Tesseract.js（WASM），图像不出浏览器。

export type OcrEngine = (blob: Blob) => Promise<string>

let injected: OcrEngine | null = null
let lazyDefault: OcrEngine | null = null

/** 测试或替代实现可注入 Fake 引擎。 */
export function setOcrEngine(engine: OcrEngine | null): void {
  injected = engine
}

async function defaultEngine(blob: Blob): Promise<string> {
  if (!lazyDefault) {
    const { createWorker } = await import('tesseract.js')
    lazyDefault = async (image: Blob) => {
      const worker = await createWorker('chi_sim+eng')
      const { data } = await worker.recognize(image)
      await worker.terminate()
      return data.text
    }
  }
  return lazyDefault(blob)
}

/** 对本地图片 Blob 做 OCR，返回原始多行文本（调用方再走纯函数解析）。 */
export async function recognizeTextFromImage(blob: Blob): Promise<string> {
  const engine = injected ?? defaultEngine
  return engine(blob)
}
