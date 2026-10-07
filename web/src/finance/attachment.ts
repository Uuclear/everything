// ============================================================================
// 财务附件链路（兼容层 —— 实现已迁至 vault/attachment.ts）
// ============================================================================
//
// 保持既有 import 路径 `web/src/finance/attachment` 不变；默认 channel
// 仍绑定 module=finance。
// ============================================================================

import type { Sodium } from '../crypto/envelope'
import { FINANCE_MODULE } from './types'
import {
  defaultAttachmentChannel as vaultDefaultAttachmentChannel,
  type AttachmentChannel,
  type AttachmentRecord,
} from '../vault/attachment'

export type {
  AttachmentChannel,
  AttachmentRecord,
  AttachmentResult,
  AttachmentRef,
} from '../vault/attachment'

export {
  ATTACHMENT_MAX_SIZE_BYTES,
  ATTACHMENT_RECORD_TYPE,
  attachmentMetadataJson,
  cryptoRandomId,
  deleteAttachment,
  downloadFile,
  isAllowedMimeType,
  listAttachments,
  sha256HexOf,
  uploadFile,
} from '../vault/attachment'

/**
 * 财务模块默认 AttachmentChannel（parentModule=finance）。
 */
export function defaultAttachmentChannel(
  sodium: Sodium,
  masterKey: Uint8Array,
  remote: Map<string, AttachmentRecord>,
): AttachmentChannel {
  return vaultDefaultAttachmentChannel(sodium, masterKey, FINANCE_MODULE, remote)
}
