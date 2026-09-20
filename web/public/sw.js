/*
 * ============================================================================
 * eve-sw：全站唯一 Service Worker（单实例）
 * ============================================================================
 *
 * 背景说明：
 *   - 本文件随 stage5-finance-v2 / B7 批次（Task 7 / FR-V2-G）首次建立；
 *     v1 版本没有任何 Service Worker，本文件是 Web 端通知基建的起点。
 *   - 全应用只注册并持有这一个 Service Worker，承载两类通知通道：
 *       1. finance：财务提醒（本期实现，订阅续费、保单到期、借款到期）；
 *       2. events：事件类通知（本期仅占位，未来在本文件内扩展消息路由）。
 *   - 未来 events 通知也统一走这里的消息路由，不再新建第二个 Service Worker。
 *
 * 设计纪律（零知识红线）：
 *   - Service Worker 自身不拼接任何业务文案，标题与正文一律由页面端传入，
 *     这里只做透传，因此金额、日期、卡号、对手方、保单号等敏感信息永远
 *     不会出现在本文件，也不会由本文件落盘。
 *
 * 本期范围：
 *   - install 直接接管，activate 立即 claim 并清理历史缓存；
 *   - 不做静态资源预缓存（CACHE 常量仅为命名预留）；
 *   - 不拦截 fetch 请求，不订阅 push，不使用 sync 事件。
 *
 * 语法基线：ES2017，浏览器直接可解析，不使用 import。
 * ============================================================================
 */

// Service Worker 名称与版本：全站固定单实例名 eve-sw，当前版本 v1。
var SW_NAME = 'eve-sw'
var SW_VERSION = 'v1'

// 缓存名前缀常量：本期不预缓存任何静态资源，仅用于 activate 时识别并清理旧缓存。
var CACHE = SW_NAME + '-cache-' + SW_VERSION

// ————————————————————————————————————————————————————————————————————————————
// install：跳过等待阶段，让新版本 Service Worker 激活后立刻接管所有页面。
// ————————————————————————————————————————————————————————————————————————————
self.addEventListener('install', function (event) {
  // 不做静态资源预缓存，仅强制进入 activate。
  event.waitUntil(self.skipWaiting())
})

// ————————————————————————————————————————————————————————————————————————————
// activate：立即接管全部客户端，并清理与当前缓存名不一致的历史缓存。
// ————————————————————————————————————————————————————————————————————————————
self.addEventListener('activate', function (event) {
  event.waitUntil(
    Promise.resolve()
      .then(function () {
        // caches 可能在极少数环境缺失，做存在性判断，保证不抛错。
        if (typeof caches === 'undefined' || !caches || !caches.keys) {
          return []
        }
        return caches.keys()
      })
      .then(function (keys) {
        return Promise.all(
          keys.map(function (key) {
            // 仅保留当前版本缓存，其余名称一律删除（本期实际无缓存）。
            if (key !== CACHE) {
              return caches.delete(key)
            }
            return undefined
          }),
        )
      })
      // claim 让新 SW 立刻对已打开的页面生效，无需用户刷新。
      .then(function () {
        return self.clients.claim()
      }),
  )
})

// ————————————————————————————————————————————————————————————————————————————
// message：统一消息路由。
//
// 约定消息形状（event.data）：
//   {
//     channel: 'finance' | 'events',   // 通知通道
//     type:    'show' | 'cancel' | ..., // 该通道下的动作类型
//     payload: { ... }                  // 动作参数，结构随 type 而定
//   }
//
// 对任何无法识别的消息（data 缺失、非对象、未知通道、未知类型、
// payload 缺失或非对象）一律静默忽略，绝不抛错，避免影响页面脚本。
// ————————————————————————————————————————————————————————————————————————————
self.addEventListener('message', function (event) {
  var data = event && event.data
  if (!data || typeof data !== 'object') {
    return
  }

  var channel = data.channel
  var type = data.type
  var payload = data.payload

  if (channel === 'finance') {
    if (type === 'show') {
      // payload 形状：{ kind, id, title, body, tag? }
      if (!payload || typeof payload !== 'object') {
        return
      }
      var kind = payload.kind
      var id = payload.id
      var title = payload.title
      var body = payload.body
      // 页面端未显式给 tag 时，按「通道 类型 id」生成稳定 tag，
      // 同一提醒重复下发会互相替换而不是叠加多条通知。
      var tag = payload.tag || ('finance-' + kind + '-' + id)
      // 文案完全由页面端提供，SW 只透传；data 供点击事件回读路由信息。
      event.waitUntil(
        self.registration.showNotification(title, {
          body: body,
          tag: tag,
          // 本期通知为替换式提醒，不要求重复响铃震动。
          renotify: false,
          data: { channel: 'finance', kind: kind, id: id },
        }),
      )
      return
    }

    if (type === 'cancel') {
      // payload 形状：{ tag }
      if (!payload || typeof payload !== 'object') {
        return
      }
      var cancelTag = payload.tag
      event.waitUntil(
        self.registration
          .getNotifications({ tag: cancelTag })
          .then(function (list) {
            // 枚举命中的通知并逐条关闭。
            list.forEach(function (notification) {
              notification.close()
            })
          })
          // 关闭失败也静默处理，通知是增强能力，不允许反向影响页面。
          .catch(function () {}),
      )
      return
    }

    // finance 通道下的未知 type：静默忽略。
    return
  }

  if (channel === 'events') {
    // v1 events 通知未来在此扩展：届时按 type 分发 show 与 cancel。
    // 当前 events 通道不实现任何具体业务，收到消息一律静默忽略。
    return
  }

  // 未知通道：静默忽略。
})

// ————————————————————————————————————————————————————————————————————————————
// notificationclick：先关闭通知，再聚焦已打开的 finance 页面；
// 若没有已打开的 finance 页面，则新开一个指向 hash 路由 #/finance 的窗口。
// ————————————————————————————————————————————————————————————————————————————
self.addEventListener('notificationclick', function (event) {
  // 无论通知来自哪个通道，点击后先把系统通知消掉。
  event.notification.close()

  event.waitUntil(
    self.clients
      .matchAll({ type: 'window', includeUncontrolled: true })
      .then(function (list) {
        var target = null
        // 查找一个 URL 指向 finance 页面的客户端；同时兼容 hash 路由
        // （例如 https://host/#/finance）与历史路径形态（/finance）。
        for (var i = 0; i < list.length; i += 1) {
          var client = list[i]
          var inHash = false
          try {
            inHash = new URL(client.url).hash.includes('/finance')
          } catch (err) {
            // URL 解析失败时退回字符串包含判断兜底。
            inHash = false
          }
          if (inHash || client.url.includes('/finance')) {
            target = client
            break
          }
        }

        if (target) {
          // 已有 finance 页面：聚焦即可。
          return target.focus()
        }
        // 未打开：新窗口落到默认根作用域下的 hash 路由。
        return self.clients.openWindow('./#/finance')
      })
      // 聚焦或开窗失败时静默处理，不向页面抛错。
      .catch(function () {}),
  )
})

// 说明：本期不订阅 push 事件、不注册 sync 事件，也不添加 fetch 监听，
// 因此本 Service Worker 不会劫持任何网络请求。
