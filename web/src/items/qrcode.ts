// ============================================================================
// 物品模块 —— 二维码 payload 与渲染（stage5-items / T2 / TR-2.2）
// ============================================================================
//
// qrPayloadForItem 为纯函数（仅 UUID 字符串，零知识：无密文/无明文字段名）。
// renderQrPng / renderQrSvg 按需 dynamic import `qrcode` 包，避免单测强依赖安装。
// ============================================================================

/** 二维码编码内容 = 物品 id（客户端 UUID），原样透传 */
export function qrPayloadForItem(itemId: string): string {
  return itemId
}

type QrCodeModule = {
  toDataURL: (text: string, opts?: { width?: number; margin?: number }) => Promise<string>
  toString: (
    text: string,
    opts?: { type?: 'svg'; width?: number; margin?: number },
  ) => Promise<string>
}

async function loadQrCode(): Promise<QrCodeModule> {
  const mod = await import('qrcode')
  return mod.default as QrCodeModule
}

/** 生成 PNG Data URL（256 或 512 像素宽） */
export async function renderQrPng(payload: string, size: 256 | 512): Promise<string> {
  const QRCode = await loadQrCode()
  return QRCode.toDataURL(payload, { width: size, margin: 1 })
}

/** 生成 SVG 字符串（矢量打印） */
export async function renderQrSvg(payload: string): Promise<string> {
  const QRCode = await loadQrCode()
  return QRCode.toString(payload, { type: 'svg', width: 256, margin: 1 })
}
