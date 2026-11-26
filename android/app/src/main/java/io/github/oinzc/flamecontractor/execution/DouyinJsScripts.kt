package io.github.oinzc.flamecontractor.execution

import io.github.oinzc.flamecontractor.core.selectors.SelectorBundle
import java.util.Locale

object DouyinJsScripts {

    const val DEFAULT_BRIDGE_NAME = "FlameBridge"

    /**
     * 抖音 Slate 编辑器发送后或空状态常残留空白与零宽字符（U+200B/200C/200D/FEFF），
     * 写入前空检、写入后强校验与气泡文本比对统一先经本正则清洗。
     */
    const val ZERO_WIDTH_REGEX_PATTERN = """[\s\u200B\u200C\u200D\uFEFF]+"""
    val ZERO_WIDTH_REGEX = Regex(ZERO_WIDTH_REGEX_PATTERN)

    fun stripBlankAndZeroWidth(text: String?): String =
        if (text.isNullOrEmpty()) "" else ZERO_WIDTH_REGEX.replace(text, "")

    internal fun jsStringLiteral(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (ch in value) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '<' -> sb.append("\\u003C")
                '>' -> sb.append("\\u003E")
                '\u2028' -> sb.append("\\u2028")
                '\u2029' -> sb.append("\\u2029")
                else -> if (ch.code < 0x20) {
                    sb.append(String.format(Locale.ROOT, "\\u%04x", ch.code))
                } else {
                    sb.append(ch)
                }
            }
        }
        sb.append('"')
        return sb.toString()
    }

    internal fun jsStringArrayLiteral(values: List<String>): String =
        values.joinToString(prefix = "[", postfix = "]", separator = ",") { jsStringLiteral(it) }

    private fun editorSelectors(bundle: SelectorBundle): List<String> =
        (listOf(bundle.chatEditor) + bundle.chatEditorSelectors).distinct()

    private fun searchInputSelectors(bundle: SelectorBundle): List<String> =
        (bundle.searchInputs + listOf(bundle.searchInput)).distinct()

    private const val DOM_HELPERS_JS = """
        function __flameIsVisible(el) {
          if (!el) return false;
          var r = el.getBoundingClientRect ? el.getBoundingClientRect() : null;
          if (!r || r.width <= 0 || r.height <= 0) return false;
          var st = window.getComputedStyle ? window.getComputedStyle(el) : null;
          if (st && (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0')) return false;
          return true;
        }
        function __flameResolveClickTarget(el, preferImgChild) {
          if (!el) return null;
          try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (_) {}
          var anchor = el;
          if (preferImgChild && el.querySelectorAll) {
            try {
              var imgs = el.querySelectorAll('img');
              for (var i = 0; i < imgs.length; i++) {
                if (__flameIsVisible(imgs[i])) {
                  anchor = imgs[i];
                  break;
                }
              }
            } catch (_) {}
          }
          try {
            var rect = anchor.getBoundingClientRect ? anchor.getBoundingClientRect() : null;
            if (rect && rect.width > 0 && rect.height > 0 && document.elementFromPoint) {
              var hit = document.elementFromPoint(rect.left + rect.width / 2, rect.top + rect.height / 2);
              if (hit && el.contains(hit)) return hit;
            }
          } catch (_) {}
          return anchor;
        }
        function __flameDispatchClick(el) {
          if (!el) return false;
          try { el.scrollIntoView({ block: 'nearest' }); } catch (_) {}
          var rect = el.getBoundingClientRect ? el.getBoundingClientRect() : { left: 0, top: 0, width: 0, height: 0 };
          var cx = rect.left + rect.width / 2;
          var cy = rect.top + rect.height / 2;
          var downOpts = {
            bubbles: true, cancelable: true, composed: true, view: window,
            button: 0, buttons: 1, detail: 1, clientX: cx, clientY: cy,
            pointerId: 1, pointerType: 'mouse', isPrimary: true
          };
          var upOpts = {
            bubbles: true, cancelable: true, composed: true, view: window,
            button: 0, buttons: 0, detail: 1, clientX: cx, clientY: cy,
            pointerId: 1, pointerType: 'mouse', isPrimary: true
          };
          try { el.dispatchEvent(new PointerEvent('pointerdown', downOpts)); } catch (_) {}
          try { el.dispatchEvent(new MouseEvent('mousedown', downOpts)); } catch (_) {}
          try { el.dispatchEvent(new PointerEvent('pointerup', upOpts)); } catch (_) {}
          try { el.dispatchEvent(new MouseEvent('mouseup', upOpts)); } catch (_) {}
          var clickSent = false;
          try {
            el.dispatchEvent(new MouseEvent('click', upOpts));
            clickSent = true;
          } catch (_) {}
          if (!clickSent) {
            try { if (typeof el.click === 'function') el.click(); } catch (_) {}
          }
          return true;
        }
        function __flameFirstVisible(selectors) {
          for (var i = 0; i < selectors.length; i++) {
            try {
              var list = document.querySelectorAll(selectors[i]);
              for (var j = 0; j < list.length; j++) {
                if (__flameIsVisible(list[j])) return { selector: selectors[i], el: list[j] };
              }
            } catch (_) {}
          }
          return null;
        }
        function __flameFirstNonEmptyLine(text) {
          var lines = String(text || '').split(/\r?\n/);
          for (var i = 0; i < lines.length; i++) {
            var trimmed = lines[i].trim();
            if (trimmed) return trimmed;
          }
          return null;
        }
        function __flameIsFailureMarkerHit(selectors) {
          for (var i = 0; i < selectors.length; i++) {
            try {
              var els = document.querySelectorAll(selectors[i]);
              for (var j = 0; j < els.length; j++) {
                var st = window.getComputedStyle ? window.getComputedStyle(els[j]) : null;
                if (!st || (st.display !== 'none' && st.visibility !== 'hidden')) {
                  return true;
                }
              }
            } catch (_) {}
          }
            return false;
        }
    """

    private const val IMAGE_MODAL_DISCOVERY_HELPERS_JS = """
        function __flameIsModalTrulyVisible(el) {
          if (!__flameIsVisible(el)) return false;
          var cur = el;
          var depth = 0;
          while (cur && cur !== document.documentElement && depth < 12) {
            if (cur.getAttribute && cur.getAttribute('aria-hidden') === 'true') return false;
            var st = window.getComputedStyle ? window.getComputedStyle(cur) : null;
            if (st && (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0')) return false;
            cur = cur.parentElement;
            depth++;
          }
          return true;
        }
        function __flameModalZIndex(el) {
          var maxZ = 0;
          var cur = el;
          var depth = 0;
          while (cur && cur !== document.body && cur !== document.documentElement && depth < 6) {
            var st = window.getComputedStyle ? window.getComputedStyle(cur) : null;
            if (st && st.zIndex && st.zIndex !== 'auto') {
              var parsed = parseInt(st.zIndex, 10);
              if (!isNaN(parsed) && parsed > maxZ) maxZ = parsed;
            }
            cur = cur.parentElement;
            depth++;
          }
          return maxZ;
        }
        function __flameFindTopVisibleModal() {
          var all = [];
          try {
            all = Array.from(document.querySelectorAll('.semi-modal, [role="dialog"]'));
          } catch (_) {}
          var visible = [];
          for (var i = 0; i < all.length; i++) {
            if (__flameIsModalTrulyVisible(all[i])) {
              visible.push({ el: all[i], idx: i, z: __flameModalZIndex(all[i]) });
            }
          }
          if (!visible.length) {
            return { topModal: null, modalCount: all.length, visibleModalCount: 0 };
          }
          var best = visible[0];
          for (var v = 1; v < visible.length; v++) {
            var cand = visible[v];
            if (cand.z > best.z || (cand.z === best.z && cand.idx >= best.idx)) {
              best = cand;
            }
          }
          return { topModal: best.el, modalCount: all.length, visibleModalCount: visible.length };
        }
        function __flameNormButtonText(el) {
          return String((el && (el.innerText || el.textContent)) || '')
            .replace(/[\s\u200B\u200C\u200D\uFEFF]+/g, ' ')
            .trim();
        }
    """

    private const val IMAGE_MODAL_SEND_HELPERS_JS = """
        function __flameIsDisabledButton(btn) {
          if (!btn) return true;
          if (btn.disabled) return true;
          if (btn.getAttribute && btn.getAttribute('aria-disabled') === 'true') return true;
          var cls = String(btn.className || '');
          return cls.indexOf('semi-button-disabled') !== -1;
        }
        function __flameFindImageModalSendButton(topModal, sendSelectors) {
          if (!topModal) return null;
          var sendRegex = /^发送(?:\s*[（(]\d+[)）])?$/;
          for (var i = 0; i < sendSelectors.length; i++) {
            var sel = sendSelectors[i];
            try {
              var candidates = document.querySelectorAll(sel);
              for (var j = 0; j < candidates.length; j++) {
                var c = candidates[j];
                if (!topModal.contains(c) || !__flameIsVisible(c)) continue;
                var txt = __flameNormButtonText(c);
                if (txt === '取消') continue;
                return {
                  el: c,
                  method: 'selector',
                  selector: sel,
                  text: txt,
                  enabled: !__flameIsDisabledButton(c)
                };
              }
            } catch (_) {}
          }
          var buttons = [];
          try {
            buttons = Array.from(topModal.querySelectorAll('button, [role="button"]'));
          } catch (_) {}
          for (var b = 0; b < buttons.length; b++) {
            var btn = buttons[b];
            if (!__flameIsVisible(btn)) continue;
            var btnText = __flameNormButtonText(btn);
            if (sendRegex.test(btnText)) {
              return {
                el: btn,
                method: 'text_regex',
                selector: null,
                text: btnText,
                enabled: !__flameIsDisabledButton(btn)
              };
            }
          }
          return null;
        }
    """

    fun buildIdentityCaptureDocumentStartJs(bridgeName: String = DEFAULT_BRIDGE_NAME): String =
        buildDocumentStartJs(bridgeName = bridgeName, captureLoginQr = false)

    fun buildLoginCaptureDocumentStartJs(bridgeName: String = DEFAULT_BRIDGE_NAME): String =
        buildDocumentStartJs(bridgeName = bridgeName, captureLoginQr = true)

    private fun buildDocumentStartJs(
        bridgeName: String,
        captureLoginQr: Boolean,
    ): String {
        val bridgeExpr = jsStringLiteral(bridgeName)
        val qrHookDecl = if (captureLoginQr) {
            """
                var QR_CODE_PATHS = ['/passport/web/get_qrcode'];
                var QR_STATUS_PATHS = ['/passport/web/check_qrconnect'];
                var __flameLastQrPollAt = 0;
                function emitQrCodePayload(rawText) {
                  try {
                    if (typeof rawText !== 'string' || !rawText) return;
                    var bridge = getBridge();
                    if (bridge && typeof bridge.onQrCodePayload === 'function') {
                      bridge.onQrCodePayload(rawText);
                    }
                  } catch (_) {}
                }
                function emitQrStatusPayload(rawText) {
                  try {
                    if (typeof rawText !== 'string' || !rawText) return;
                    if (rawText.indexOf('confirmed') === -1 && rawText.indexOf('expired') === -1) {
                      __flameLastQrPollAt = Date.now();
                    } else {
                      __flameLastQrPollAt = 0;
                    }
                    var bridge = getBridge();
                    if (bridge && typeof bridge.onQrStatusPayload === 'function') {
                      bridge.onQrStatusPayload(rawText);
                    }
                  } catch (_) {}
                }
            """.trimIndent()
        } else {
            """
                var QR_CODE_PATHS = [];
                var QR_STATUS_PATHS = [];
                function emitQrCodePayload() {}
                function emitQrStatusPayload() {}
            """.trimIndent()
        }
        val visibilityKeepAliveJs = if (captureLoginQr) {
            """
                try {
                  Object.defineProperty(Document.prototype, 'hidden', {
                    get: function () { return false; },
                    configurable: true
                  });
                  Object.defineProperty(Document.prototype, 'visibilityState', {
                    get: function () { return 'visible'; },
                    configurable: true
                  });
                  Document.prototype.hasFocus = function () { return true; };
                } catch (_) {}
                try {
                  if (!window.__flameKeepAliveGuardsInstalled) {
                    window.__flameKeepAliveGuardsInstalled = true;
                    window.__flameKeepAliveActive = false;
                    var suppressBgEvent = function (ev) {
                      if (window.__flameKeepAliveActive && ev && typeof ev.stopImmediatePropagation === 'function') {
                        ev.stopImmediatePropagation();
                      }
                    };
                    document.addEventListener('visibilitychange', suppressBgEvent, true);
                    window.addEventListener('blur', suppressBgEvent, true);
                    window.addEventListener('pagehide', suppressBgEvent, true);

                    var origSetTimeout = window.setTimeout;
                    if (typeof origSetTimeout === 'function') {
                      window.setTimeout = function (fn, delay) {
                        var nextDelay = delay;
                        try {
                          if (window.__flameKeepAliveActive &&
                              typeof __flameLastQrPollAt === 'number' &&
                              __flameLastQrPollAt > 0 &&
                              (Date.now() - __flameLastQrPollAt) <= 220 &&
                              typeof delay === 'number' &&
                              delay >= 1100 &&
                              delay <= 12000) {
                            nextDelay = 850;
                          }
                        } catch (_) {}
                        var args = Array.prototype.slice.call(arguments);
                        args[1] = nextDelay;
                        return origSetTimeout.apply(this, args);
                      };
                    }
                  }
                } catch (_) {}
            """.trimIndent()
        } else {
            ""
        }
        return """
            (function () {
              'use strict';
              try {
                try {
                  Object.defineProperty(Navigator.prototype, 'platform', {
                    get: function () { return 'Win32'; },
                    configurable: true
                  });
                } catch (_) {}

                $visibilityKeepAliveJs

                try {
                  var ua = String(navigator.userAgent || '');
                  var m = /Chrome\/(\d+(?:\.\d+)*)/.exec(ua);
                  var fullVer = (m && m[1]) ? m[1] : '135.0.0.0';
                  var major = fullVer.split('.')[0] || '135';
                  var fakeUAData = {
                    brands: [
                      { brand: 'Not-A.Brand', version: '99' },
                      { brand: 'Chromium', version: major },
                      { brand: 'Google Chrome', version: major }
                    ],
                    mobile: false,
                    platform: 'Windows',
                    getHighEntropyValues: function (hints) {
                      var res = {
                        brands: fakeUAData.brands,
                        mobile: false,
                        platform: 'Windows',
                        platformVersion: '15.0.0',
                        architecture: 'x86',
                        bitness: '64',
                        model: '',
                        uaFullVersion: fullVer
                      };
                      if (Array.isArray(hints) && hints.length) {
                        var picked = { brands: fakeUAData.brands, mobile: false, platform: 'Windows' };
                        for (var i = 0; i < hints.length; i++) {
                          var h = hints[i];
                          if (Object.prototype.hasOwnProperty.call(res, h)) {
                            picked[h] = res[h];
                          }
                        }
                        return Promise.resolve(picked);
                      }
                      return Promise.resolve(res);
                    },
                    toJSON: function () {
                      return { brands: fakeUAData.brands, mobile: false, platform: 'Windows' };
                    }
                  };
                  Object.defineProperty(Navigator.prototype, 'userAgentData', {
                    get: function () { return fakeUAData; },
                    configurable: true
                  });
                } catch (_) {}

                if (window.__flameBridgeCaptureInstalled) {
                  return;
                }
                window.__flameBridgeCaptureInstalled = true;

                var BRIDGE_NAME = $bridgeExpr;
                var IDENTITY_PATHS = [
                  '/aweme/v1/web/im/user/info',
                  '/aweme/v1/web/user/profile/other',
                  '/aweme/v1/web/user/profile/scene'
                ];
                var PROTO_PATHS = [
                  '/v1/message/get_message_by_init',
                  '/v2/message/get_by_user_init',
                  '/v1/message/get_message_by_user',
                  '/v1/conversation/get_info_v2',
                  '/v1/conversation/get_info_list_v2',
                  '/v2/conversation/get_info_list',
                  '/v1/conversation/get_user_conversation_list',
                  '/v1/conversation/get_info'
                ];
                $qrHookDecl

                function getBridge() {
                  try {
                    return window[BRIDGE_NAME] || null;
                  } catch (_) {
                    return null;
                  }
                }

                function extractPath(rawUrl) {
                  try {
                    var u = new URL(String(rawUrl || ''), window.location.origin);
                    return u.pathname || '';
                  } catch (_) {
                    return '';
                  }
                }

                function matchPath(path, candidates) {
                  if (!path) return null;
                  for (var i = 0; i < candidates.length; i++) {
                    if (path.indexOf(candidates[i]) !== -1) return candidates[i];
                  }
                  return null;
                }

                function pickStr(obj, keys) {
                  for (var i = 0; i < keys.length; i++) {
                    var val = obj[keys[i]];
                    if (typeof val === 'string' && val.trim()) return val.trim();
                    if (typeof val === 'number' && isFinite(val)) return String(val);
                  }
                  return null;
                }

                function extractMinimalIdentityRecords(root) {
                  if (!root || typeof root !== 'object') return [];
                  var out = [];
                  var stack = [root];
                  var visited = 0;
                  var seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
                  while (stack.length > 0 && visited < 4000) {
                    var cur = stack.pop();
                    if (!cur || typeof cur !== 'object') continue;
                    if (seen) {
                      if (seen.has(cur)) continue;
                      seen.add(cur);
                    }
                    visited++;
                    if (Array.isArray(cur)) {
                      for (var i = 0; i < cur.length; i++) {
                        if (cur[i] && typeof cur[i] === 'object') stack.push(cur[i]);
                      }
                      continue;
                    }
                    var secUid = pickStr(cur, ['sec_uid', 'sec_user_id', 'secUid', 'secUserId']);
                    var uid = pickStr(cur, ['uid', 'user_id', 'userId']);
                    if (secUid || uid) {
                      out.push({
                        sec_uid: secUid,
                        uid: uid,
                        unique_id: pickStr(cur, ['unique_id', 'uniqueId']),
                        short_id: pickStr(cur, ['short_id', 'shortId']),
                        nickname: pickStr(cur, ['nickname', 'nickName']),
                        remark_name: pickStr(cur, ['remark_name', 'remarkName'])
                      });
                    }
                    var keys = Object.keys(cur);
                    for (var k = 0; k < keys.length; k++) {
                      var child = cur[keys[k]];
                      if (child && typeof child === 'object') stack.push(child);
                    }
                  }
                  return out;
                }

                function emitIdentityPayload(payload) {
                  try {
                    var bridge = getBridge();
                    if (!bridge || typeof bridge.onIdentityRecords !== 'function') return;
                    var records = extractMinimalIdentityRecords(payload);
                    if (records.length > 0) {
                      bridge.onIdentityRecords(JSON.stringify(records));
                    }
                  } catch (_) {}
                }

                function arrayBufferToBase64Chunked(buffer) {
                  if (!buffer) return '';
                  var bytes = null;
                  if (buffer instanceof ArrayBuffer) {
                    bytes = new Uint8Array(buffer);
                  } else if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(buffer)) {
                    bytes = new Uint8Array(buffer.buffer, buffer.byteOffset, buffer.byteLength);
                  }
                  if (!bytes || !bytes.length) return '';
                  var chunkSize = 0x8000;
                  var chunks = [];
                  for (var i = 0; i < bytes.length; i += chunkSize) {
                    var sub = bytes.subarray(i, Math.min(i + chunkSize, bytes.length));
                    chunks.push(String.fromCharCode.apply(null, sub));
                  }
                  return btoa(chunks.join(''));
                }

                var PROTO_BRIDGE_CHUNK_SIZE = 96 * 1024;
                var protoTxSeq = 0;
                window.__flameProtoInFlight = 0;
                window.__flameLastProtoAt = 0;

                function markProtoStart() {
                  window.__flameProtoInFlight = (window.__flameProtoInFlight || 0) + 1;
                }
                function markProtoEnd() {
                  window.__flameProtoInFlight = Math.max(0, (window.__flameProtoInFlight || 1) - 1);
                  window.__flameLastProtoAt = Date.now();
                }

                function emitProtoBuffer(buffer) {
                  try {
                    var bridge = getBridge();
                    if (!bridge) return;
                    var b64 = arrayBufferToBase64Chunked(buffer);
                    if (!b64) return;
                    if (b64.length > PROTO_BRIDGE_CHUNK_SIZE && typeof bridge.onInitProtoChunk === 'function') {
                      protoTxSeq += 1;
                      var txId = String(Date.now()) + '_' + String(protoTxSeq);
                      var total = Math.ceil(b64.length / PROTO_BRIDGE_CHUNK_SIZE);
                      for (var idx = 0; idx < total; idx++) {
                        var part = b64.slice(idx * PROTO_BRIDGE_CHUNK_SIZE, (idx + 1) * PROTO_BRIDGE_CHUNK_SIZE);
                        bridge.onInitProtoChunk(txId, idx, total, part);
                      }
                    } else if (typeof bridge.onInitProtoBase64 === 'function') {
                      bridge.onInitProtoBase64(b64);
                    }
                  } catch (_) {}
                }

                var NET_EVENTS_LIMIT = 40;
                var netEvents = window.__flameNetEvents;
                if (!Array.isArray(netEvents)) {
                  netEvents = [];
                  window.__flameNetEvents = netEvents;
                }

                function sanitizeNetTarget(rawUrl) {
                  try {
                    var u = new URL(String(rawUrl || ''), window.location.origin);
                    var host = String(u.host || '').toLowerCase();
                    var rawPath = String(u.pathname || '');
                    var lower = (host + rawPath).toLowerCase();
                    var interesting = lower.indexOf('upload') !== -1 ||
                      lower.indexOf('tos') !== -1 ||
                      lower.indexOf('imagex') !== -1 ||
                      lower.indexOf('/im/') !== -1 ||
                      lower.indexOf('imapi') !== -1 ||
                      lower.indexOf('conversation') !== -1 ||
                      lower.indexOf('message') !== -1 ||
                      lower.indexOf('send') !== -1 ||
                      lower.indexOf('media') !== -1;
                    if (!interesting) return null;
                    var segs = rawPath.split('/').filter(Boolean).slice(0, 4).map(function (seg) {
                      if (seg.length > 24 || /^\d{6,}$/.test(seg)) return '<id>';
                      return seg;
                    });
                    return { host: host, path: '/' + segs.join('/') };
                  } catch (_) {
                    return null;
                  }
                }

                function recordNetStart(kind, method, rawUrl) {
                  var target = sanitizeNetTarget(rawUrl);
                  if (!target) return null;
                  var entry = {
                    t: Date.now(),
                    kind: kind,
                    method: String(method || 'GET').toUpperCase().slice(0, 8),
                    host: target.host,
                    path: target.path,
                    status: 0
                  };
                  netEvents.push(entry);
                  if (netEvents.length > NET_EVENTS_LIMIT) {
                    netEvents.splice(0, netEvents.length - NET_EVENTS_LIMIT);
                  }
                  return entry;
                }

                if (typeof window.fetch === 'function') {
                  var origFetch = window.fetch;
                  window.fetch = function (input, init) {
                    var rawUrl = '';
                    var method = 'GET';
                    try {
                      rawUrl = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                      method = (init && init.method) || (input && input.method) || 'GET';
                    } catch (_) {}
                    var path = extractPath(rawUrl);
                    var idMatch = matchPath(path, IDENTITY_PATHS);
                    var protoMatch = matchPath(path, PROTO_PATHS);
                    var qrCodeMatch = matchPath(path, QR_CODE_PATHS);
                    var qrStatusMatch = matchPath(path, QR_STATUS_PATHS);
                    var netEntry = recordNetStart('fetch', method, rawUrl);
                    if (protoMatch) markProtoStart();
                    var promise = origFetch.apply(this, arguments);
                    if (protoMatch) {
                      try {
                        promise.then(function () { markProtoEnd(); }, function () { markProtoEnd(); });
                      } catch (_) {
                        markProtoEnd();
                      }
                    }
                    if (netEntry) {
                      try {
                        promise.then(function (resp) {
                          try { netEntry.status = (resp && typeof resp.status === 'number') ? resp.status : -2; } catch (_) {}
                        }).catch(function () {
                          try { netEntry.status = -1; } catch (_) {}
                        });
                      } catch (_) {}
                    }
                    if (idMatch || protoMatch || qrCodeMatch || qrStatusMatch) {
                      try {
                        promise.then(function (resp) {
                          try {
                            if (!resp || typeof resp.clone !== 'function') return;
                            var cloned = resp.clone();
                            if (idMatch) {
                              cloned.json().then(function (data) {
                                emitIdentityPayload(data);
                              }).catch(function () {});
                            } else if (protoMatch) {
                              cloned.arrayBuffer().then(function (buf) {
                                emitProtoBuffer(buf);
                              }).catch(function () {});
                            } else if (qrCodeMatch) {
                              cloned.text().then(function (txt) {
                                emitQrCodePayload(txt);
                              }).catch(function () {});
                            } else if (qrStatusMatch) {
                              cloned.text().then(function (txt) {
                                emitQrStatusPayload(txt);
                              }).catch(function () {});
                            }
                          } catch (_) {}
                        }).catch(function () {});
                      } catch (_) {}
                    }
                    return promise;
                  };
                }

                if (typeof XMLHttpRequest !== 'undefined' && XMLHttpRequest.prototype) {
                  var origOpen = XMLHttpRequest.prototype.open;
                  var origSend = XMLHttpRequest.prototype.send;
                  XMLHttpRequest.prototype.open = function (method, url) {
                    try {
                      this.__flameMethod = method ? String(method) : 'GET';
                      this.__flameUrl = url ? String(url) : '';
                    } catch (_) {}
                    return origOpen.apply(this, arguments);
                  };
                  XMLHttpRequest.prototype.send = function () {
                    try {
                      var rawUrl = this.__flameUrl || '';
                      var path = extractPath(rawUrl);
                      var idMatch = matchPath(path, IDENTITY_PATHS);
                      var protoMatch = matchPath(path, PROTO_PATHS);
                      var qrCodeMatch = matchPath(path, QR_CODE_PATHS);
                      var qrStatusMatch = matchPath(path, QR_STATUS_PATHS);
                      var netEntry = recordNetStart('xhr', this.__flameMethod || 'GET', rawUrl);
                      if (netEntry) {
                        this.addEventListener('load', function () {
                          try { netEntry.status = typeof this.status === 'number' ? this.status : -2; } catch (_) {}
                        });
                        this.addEventListener('error', function () {
                          try { netEntry.status = -1; } catch (_) {}
                        });
                        this.addEventListener('abort', function () {
                          try { netEntry.status = -3; } catch (_) {}
                        });
                      }
                      if ((idMatch || protoMatch || qrCodeMatch || qrStatusMatch) && !this.__flameBridgeHooked) {
                        this.__flameBridgeHooked = true;
                        if (protoMatch) {
                          markProtoStart();
                          var protoDone = false;
                          var finishProtoXhr = function () {
                            if (!protoDone) {
                              protoDone = true;
                              markProtoEnd();
                            }
                          };
                          this.addEventListener('loadend', finishProtoXhr);
                          this.addEventListener('error', finishProtoXhr);
                          this.addEventListener('abort', finishProtoXhr);
                        }
                        this.addEventListener('load', function () {
                          try {
                            if (idMatch) {
                              if (this.responseType === 'json' && this.response) {
                                emitIdentityPayload(this.response);
                              } else if (typeof this.responseText === 'string' && this.responseText) {
                                emitIdentityPayload(JSON.parse(this.responseText));
                              }
                            } else if (protoMatch) {
                              if (this.response instanceof ArrayBuffer) {
                                emitProtoBuffer(this.response);
                              } else if (typeof ArrayBuffer !== 'undefined' && ArrayBuffer.isView && ArrayBuffer.isView(this.response)) {
                                emitProtoBuffer(this.response);
                              } else if (this.responseType === 'blob' && this.response && typeof this.response.arrayBuffer === 'function') {
                                this.response.arrayBuffer().then(function (buf) {
                                  emitProtoBuffer(buf);
                                }).catch(function () {});
                              }
                            } else if (qrCodeMatch || qrStatusMatch) {
                              var rawBody = null;
                              if (this.responseType === 'json' && this.response) {
                                rawBody = JSON.stringify(this.response);
                              } else if ((!this.responseType || this.responseType === 'text') && typeof this.responseText === 'string') {
                                rawBody = this.responseText;
                              }
                              if (rawBody) {
                                if (qrCodeMatch) emitQrCodePayload(rawBody);
                                else emitQrStatusPayload(rawBody);
                              }
                            }
                          } catch (_) {}
                        });
                      }
                    } catch (_) {}
                    return origSend.apply(this, arguments);
                  };
                }
              } catch (_) {}
            })();
        """.trimIndent()
    }

    /**
     * 只读读取本次会话自 [sinceEpochMs] 起记录的脱敏上传/IM 网络事件摘要（不含 query、body 或用户标识）。
     */
    fun buildReadRecentUploadNetEventsJs(sinceEpochMs: Long): String = """
        (function () {
          var raw = Array.isArray(window.__flameNetEvents) ? window.__flameNetEvents : [];
          var since = $sinceEpochMs;
          var events = [];
          for (var i = 0; i < raw.length; i++) {
            var e = raw[i];
            if (!e || typeof e.t !== 'number' || e.t < since) continue;
            events.push(
              String(e.kind || '') + ':' +
              String(e.method || '') + ':' +
              String(e.host || '') +
              String(e.path || '') + '=' +
              String(typeof e.status === 'number' ? e.status : 0)
            );
          }
          return { count: events.length, events: events.slice(-16) };
        })();
    """.trimIndent()

    /**
     * 只读读取浏览器 IndexedDB 中缓存的用户身份与群聊会话记录（每个 store 独立闭包与 `ev.target.result`，
     * 彻底避免在循环内共享句柄导致 `InvalidStateError`）。
     * 返回 `{ users: [...], groups: [...] }`，同时通过 bridge 回调通知用户与群聊记录。
     */
    fun buildReadIndexedDbIdentitiesJs(
        bridgeName: String = DEFAULT_BRIDGE_NAME,
        callId: String? = null,
    ): String {
        val bridgeLiteral = jsStringLiteral(bridgeName)
        val callIdLiteral = if (callId != null) jsStringLiteral(callId) else "null"
        return """
            (function () {
              const BRIDGE_NAME = $bridgeLiteral;
              const CALL_ID = $callIdLiteral;

              function notifyResult(payload) {
                const safePayload = payload && typeof payload === 'object'
                  ? {
                      users: Array.isArray(payload.users) ? payload.users : [],
                      groups: Array.isArray(payload.groups) ? payload.groups : []
                    }
                  : { users: [], groups: [] };
                let json = '{"users":[],"groups":[]}';
                try { json = JSON.stringify(safePayload); } catch (_) {}
                try {
                  const bridge = window[BRIDGE_NAME];
                  if (bridge && typeof bridge.onIdentityRecords === 'function' && safePayload.users.length > 0) {
                    bridge.onIdentityRecords(JSON.stringify(safePayload.users));
                  }
                  if (bridge && typeof bridge.onGroupRecords === 'function' && safePayload.groups.length > 0) {
                    bridge.onGroupRecords(JSON.stringify(safePayload.groups));
                  }
                  if (CALL_ID && bridge && typeof bridge.onAsyncResult === 'function') {
                    bridge.onAsyncResult(CALL_ID, json);
                  }
                } catch (_) {}
                return safePayload;
              }

              if (typeof indexedDB === 'undefined' || typeof indexedDB.databases !== 'function') {
                return Promise.resolve(notifyResult({ users: [], groups: [] }));
              }

              function pickStr(v, keys) {
                if (!v || typeof v !== 'object') return null;
                for (let i = 0; i < keys.length; i++) {
                  const val = v[keys[i]];
                  if (typeof val === 'string' && val.trim()) return val.trim();
                  if (typeof val === 'number' && isFinite(val)) return String(val);
                  if (typeof val === 'bigint') return val.toString();
                  if (val && typeof val === 'object' && typeof val.toString === 'function') {
                    try {
                      const s = String(val.toString()).trim();
                      if (s && s !== '[object Object]') return s;
                    } catch (_) {}
                  }
                }
                return null;
              }

              function pickRawField(v, keys) {
                if (!v || typeof v !== 'object') return undefined;
                for (let i = 0; i < keys.length; i++) {
                  if (v[keys[i]] !== undefined && v[keys[i]] !== null) return v[keys[i]];
                }
                return undefined;
              }

              function isValidTitle(raw) {
                if (typeof raw !== 'string') return false;
                const t = raw.trim();
                if (!t || t.length > 80) return false;
                return !/[\x00-\x1F\x7F]/.test(t);
              }

              function extractGroupConversationId(rawId, isExplicitGroup, isExplicitSingle) {
                if (typeof rawId !== 'string') return null;
                const s = rawId.trim();
                if (!s) return null;
                if (isExplicitSingle) return null;
                if (/^\d{15,25}$/.test(s)) {
                  return isExplicitGroup ? s : null;
                }
                const colonMatch = s.match(/^\d{1,4}:(\d+):(\d{15,25})$/);
                if (colonMatch && colonMatch[1] === '2') {
                  return colonMatch[2];
                }
                return null;
              }

              function tryParseJsonObject(raw) {
                if (typeof raw !== 'string') return null;
                const trimmed = raw.trim();
                if (trimmed.length < 2 || trimmed.length > 16384 || trimmed.charAt(0) !== '{') return null;
                try {
                  const parsed = JSON.parse(trimmed);
                  return parsed && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null;
                } catch (_) {
                  return null;
                }
              }

              function pickUserRecord(item) {
                if (!item || typeof item !== 'object') return null;
                const v = (item.value && typeof item.value === 'object') ? item.value : item;
                if (!v || typeof v !== 'object') return null;
                const secUid = pickStr(v, ['sec_uid', 'sec_user_id', 'secUid', 'secUserId']);
                const uid = pickStr(v, ['uid', 'user_id', 'userId']);
                if (!secUid && !uid) return null;
                return {
                  sec_uid: secUid,
                  uid: uid,
                  unique_id: pickStr(v, ['unique_id', 'uniqueId']),
                  short_id: pickStr(v, ['short_id', 'shortId']),
                  nickname: pickStr(v, ['nickname', 'nickName']),
                  remark_name: pickStr(v, ['remark_name', 'remarkName'])
                };
              }

              function inspectNodeForGroup(node) {
                if (!node || typeof node !== 'object' || Array.isArray(node)) return null;
                const coreRaw = node.coreInfo || node.core_info || node.core || null;
                const core = (typeof coreRaw === 'string') ? tryParseJsonObject(coreRaw) : ((coreRaw && typeof coreRaw === 'object') ? coreRaw : null);
                const typeVal = pickRawField(node, ['type', 'conversationType', 'conversation_type', 'convType', 'conv_type', 'chatType', 'chat_type']);
                const coreTypeVal = core ? pickRawField(core, ['type', 'conversationType', 'conversation_type', 'convType', 'conv_type']) : undefined;
                const effType = typeVal !== undefined ? typeVal : coreTypeVal;
                const typeStr = effType !== undefined && effType !== null ? String(effType).trim().toLowerCase() : '';
                const isExplicitSingle = (effType === 1 || typeStr === '1' || typeStr === 'single' || typeStr === 'one_to_one' || typeStr === 'friend' || typeStr === 'private');
                const isExplicitGroup = !isExplicitSingle && (
                  effType === 2 ||
                  typeStr === '2' ||
                  typeStr === 'group' ||
                  node.isGroup === true ||
                  node.is_group === true ||
                  (core && (core.isGroup === true || core.is_group === true))
                );

                const idKeys = [
                  'conversationShortId', 'conversation_short_id',
                  'shortId', 'short_id',
                  'convShortId', 'conv_short_id',
                  'conversationId', 'conversation_id',
                  'convId', 'conv_id',
                  'id'
                ];
                let convId = null;
                const idSources = core ? [node, core] : [node];
                for (let sIdx = 0; sIdx < idSources.length && !convId; sIdx++) {
                  const src = idSources[sIdx];
                  for (let kIdx = 0; kIdx < idKeys.length; kIdx++) {
                    const candidate = pickStr(src, [idKeys[kIdx]]);
                    if (!candidate) continue;
                    const extracted = extractGroupConversationId(candidate, isExplicitGroup, isExplicitSingle);
                    if (extracted) {
                      convId = extracted;
                      break;
                    }
                  }
                }
                if (!convId) return null;

                const titleKeys = [
                  'name', 'title',
                  'groupName', 'group_name',
                  'convName', 'conv_name',
                  'conversationName', 'conversation_name',
                  'displayName', 'display_name'
                ];
                const rawTitle = (core ? pickStr(core, titleKeys) : null) || pickStr(node, titleKeys);
                if (!isValidTitle(rawTitle)) return null;
                return {
                  platform_conversation_id: convId,
                  title: rawTitle.trim(),
                  conversation_type: 'group'
                };
              }

              function collectGroupsFromRow(row, outGroups) {
                if (!row) return;
                const root = (typeof row === 'string') ? tryParseJsonObject(row) : row;
                if (!root || typeof root !== 'object') return;
                const queue = [{ obj: root, depth: 0 }];
                let visited = 0;
                const seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
                const nestedKeys = ['value', 'data', 'conversation', 'conv', 'info', 'coreInfo', 'core_info', 'item', 'session', 'record', 'conversations', 'list', 'items'];
                while (queue.length > 0 && visited < 40) {
                  const cur = queue.shift();
                  if (!cur || !cur.obj || typeof cur.obj !== 'object') continue;
                  if (seen) {
                    if (seen.has(cur.obj)) continue;
                    seen.add(cur.obj);
                  }
                  visited += 1;
                  if (Array.isArray(cur.obj)) {
                    if (cur.depth < 3) {
                      const lim = Math.min(cur.obj.length, 30);
                      for (let i = 0; i < lim; i++) {
                        if (cur.obj[i] && typeof cur.obj[i] === 'object') {
                          queue.push({ obj: cur.obj[i], depth: cur.depth + 1 });
                        }
                      }
                    }
                    continue;
                  }
                  const hit = inspectNodeForGroup(cur.obj);
                  if (hit) {
                    outGroups.push(hit);
                    continue;
                  }
                  if (cur.depth < 3) {
                    for (let k = 0; k < nestedKeys.length; k++) {
                      const childRaw = cur.obj[nestedKeys[k]];
                      if (!childRaw) continue;
                      const childObj = (typeof childRaw === 'string') ? tryParseJsonObject(childRaw) : childRaw;
                      if (childObj && typeof childObj === 'object') {
                        queue.push({ obj: childObj, depth: cur.depth + 1 });
                      }
                    }
                  }
                }
              }

              function shouldSkipDb(dbName) {
                if (typeof dbName !== 'string' || !dbName.trim()) return true;
                const lower = dbName.toLowerCase();
                return lower.indexOf('workbox') === 0 ||
                  lower.indexOf('firebase') === 0 ||
                  lower.indexOf('sentry') === 0 ||
                  lower.indexOf('slardar') === 0 ||
                  lower.indexOf('alog') !== -1 ||
                  lower.indexOf('bd_log') !== -1;
              }

              function classifyStoreLimit(storeName, isUserDb) {
                if (isUserDb) return 2500;
                const lower = String(storeName || '').toLowerCase();
                const priorityTokens = ['conv', 'group', 'session', 'room', 'chat', 'user', 'profile', 'member', 'contact', 'friend', 'core', 'info'];
                for (let i = 0; i < priorityTokens.length; i++) {
                  if (lower.indexOf(priorityTokens[i]) !== -1) return 2000;
                }
                const skipTokens = ['message', 'msg', 'log', 'event', 'metric', 'stat', 'report', 'media', 'blob', 'file', 'chunk', 'image', 'video', 'audio', 'sticker', 'emoji', 'draft', 'ack', 'cursor', 'sync'];
                for (let j = 0; j < skipTokens.length; j++) {
                  if (lower.indexOf(skipTokens[j]) !== -1) return 0;
                }
                return 400;
              }

              function readOneDb(dbName) {
                return new Promise(function (resolve) {
                  let settled = false;
                  const finish = function (res) {
                    if (!settled) {
                      settled = true;
                      resolve(res || { users: [], groups: [] });
                    }
                  };
                  const timer = setTimeout(function () { finish({ users: [], groups: [] }); }, 2000);
                  const isUserDb = dbName.toLowerCase().indexOf('user') !== -1;
                  try {
                    const openReq = indexedDB.open(dbName);
                    openReq.onerror = function () { clearTimeout(timer); finish({ users: [], groups: [] }); };
                    openReq.onblocked = function () { clearTimeout(timer); finish({ users: [], groups: [] }); };
                    openReq.onsuccess = function (openEv) {
                      const db = (openEv && openEv.target && openEv.target.result) ? openEv.target.result : openReq.result;
                      const stores = Array.from((db && db.objectStoreNames) ? db.objectStoreNames : []);
                      if (!stores.length) {
                        clearTimeout(timer);
                        try { db.close(); } catch (_) {}
                        finish({ users: [], groups: [] });
                        return;
                      }
                      const outUsers = [];
                      const outGroups = [];
                      let pending = stores.length;
                      const finishStore = function () {
                        pending -= 1;
                        if (pending <= 0) {
                          clearTimeout(timer);
                          try { db.close(); } catch (_) {}
                          finish({ users: outUsers, groups: outGroups });
                        }
                      };
                      stores.forEach(function (storeName) {
                        const limit = classifyStoreLimit(storeName, isUserDb);
                        if (limit <= 0) {
                          finishStore();
                          return;
                        }
                        try {
                          const tx = db.transaction(storeName, 'readonly');
                          const store = tx.objectStore(storeName);
                          const storeReq = store.getAll(null, limit);
                          storeReq.onsuccess = function (ev) {
                            try {
                              const rows = (ev && ev.target && ev.target.result) ? ev.target.result : [];
                              rows.forEach(function (row) {
                                if (isUserDb) {
                                  const pickedUser = pickUserRecord(row);
                                  if (pickedUser) outUsers.push(pickedUser);
                                } else if (row && typeof row === 'object') {
                                  const pickedUser = pickUserRecord(row);
                                  if (pickedUser) outUsers.push(pickedUser);
                                }
                                collectGroupsFromRow(row, outGroups);
                              });
                            } catch (_) {}
                            finishStore();
                          };
                          storeReq.onerror = function () { finishStore(); };
                        } catch (_) {
                          finishStore();
                        }
                      });
                    };
                  } catch (_) {
                    clearTimeout(timer);
                    finish({ users: [], groups: [] });
                  }
                });
              }

              return Promise.race([
                indexedDB.databases().then(function (dbs) {
                  const candidateDbs = (dbs || [])
                    .map(function (d) { return d && d.name; })
                    .filter(function (name) { return !shouldSkipDb(name); });
                  return Promise.all(candidateDbs.map(readOneDb)).then(function (chunks) {
                    const mergedUsers = [];
                    const mergedGroups = [];
                    const seenUsers = Object.create(null);
                    const seenGroups = Object.create(null);
                    chunks.forEach(function (chunk) {
                      (chunk.users || []).forEach(function (u) {
                        const uk = (u.sec_uid || u.uid || '') + '|' + (u.remark_name || '') + '|' + (u.nickname || '');
                        if (uk && !seenUsers[uk]) {
                          seenUsers[uk] = true;
                          mergedUsers.push(u);
                        }
                      });
                      (chunk.groups || []).forEach(function (g) {
                        const gk = (g.platform_conversation_id || '') + '|' + (g.title || '');
                        if (gk && !seenGroups[gk]) {
                          seenGroups[gk] = true;
                          mergedGroups.push(g);
                        }
                      });
                    });
                    return notifyResult({ users: mergedUsers, groups: mergedGroups });
                  });
                }).catch(function () {
                  return notifyResult({ users: [], groups: [] });
                }),
                new Promise(function (resolve) {
                  setTimeout(function () { resolve(notifyResult({ users: [], groups: [] })); }, 3500);
                })
              ]);
            })();
        """.trimIndent()
    }

    fun buildAuthAndSelfIdentityProbeJs(bundle: SelectorBundle): String {
        val convListLit = jsStringLiteral(bundle.conversationList)
        val convItemLit = jsStringLiteral(bundle.conversationItem)
        return """
            (function () {
              $DOM_HELPERS_JS
              var href = String(window.location && window.location.href ? window.location.href : '');
              var host = String(window.location && window.location.hostname ? window.location.hostname : '').toLowerCase();
              var pathname = String(window.location && window.location.pathname ? window.location.pathname : '');
              var isBlankPage = !href || href === 'about:blank';
              var isErrorPage = href.indexOf('chrome-error://') === 0;
              var isChatRoute = pathname.indexOf('/chat') === 0;
              var pageTitleLen = String(document.title || '').trim().length;
              var bodyText = '';
              try { bodyText = String(document.body ? (document.body.innerText || '') : ''); } catch (_) {}

              var loggedIn = false;
              var conversationCount = 0;
              try {
                loggedIn = !!document.querySelector($convListLit);
                conversationCount = document.querySelectorAll($convItemLit).length;
              } catch (_) {}

              var verifyIframeSelectors = [
                'iframe[src*="verify"]',
                'iframe[src*="captcha"]',
                'iframe[src*="bdturing"]',
                'iframe[src*="rmc.bytedance"]'
              ];
              var verifyDomSelectors = [
                '[id*="captcha"]',
                '[class*="captcha_verify"]',
                '[class*="secsdk-captcha"]',
                '[class*="bdturing"]'
              ];
              var hasVisibleVerifyIframe = false;
              for (var vi = 0; vi < verifyIframeSelectors.length; vi++) {
                try {
                  var iframes = document.querySelectorAll(verifyIframeSelectors[vi]);
                  for (var vj = 0; vj < iframes.length; vj++) {
                    var ifr = iframes[vj];
                    if (!__flameIsVisible(ifr)) continue;
                    var ir = ifr.getBoundingClientRect ? ifr.getBoundingClientRect() : null;
                    if (ir && ir.width >= 40 && ir.height >= 40) {
                      hasVisibleVerifyIframe = true;
                      break;
                    }
                  }
                  if (hasVisibleVerifyIframe) break;
                } catch (_) {}
              }
              var hasVisibleVerifyDom = false;
              if (!hasVisibleVerifyIframe) {
                for (var vd = 0; vd < verifyDomSelectors.length; vd++) {
                  try {
                    var nodes = document.querySelectorAll(verifyDomSelectors[vd]);
                    for (var vn = 0; vn < nodes.length; vn++) {
                      var node = nodes[vn];
                      if (!__flameIsVisible(node)) continue;
                      var nr = node.getBoundingClientRect ? node.getBoundingClientRect() : null;
                      if (nr && nr.width >= 80 && nr.height >= 80) {
                        hasVisibleVerifyDom = true;
                        break;
                      }
                    }
                    if (hasVisibleVerifyDom) break;
                  } catch (_) {}
                }
              }
              var hasVerifyText = bodyText.indexOf('身份验证') !== -1 ||
                bodyText.indexOf('安全验证') !== -1 ||
                bodyText.indexOf('请完成下列验证') !== -1 ||
                bodyText.indexOf('向右滑动') !== -1 ||
                bodyText.indexOf('拖动滑块') !== -1 ||
                bodyText.indexOf('请先完成验证') !== -1 ||
                bodyText.indexOf('滑动拼图') !== -1 ||
                bodyText.indexOf('按顺序点击') !== -1;

              var verificationSignal = hasVisibleVerifyIframe
                ? 'iframe_verify'
                : (hasVisibleVerifyDom ? 'dom_captcha' : (hasVerifyText ? 'text_verify' : 'none'));
              var verificationRequired = verificationSignal !== 'none';

              var qrFound = false;
              var qrImageDataUrl = null;
              try {
                var imgs = document.querySelectorAll('img');
                for (var i = 0; i < imgs.length; i++) {
                  var src = imgs[i].src || '';
                  if (src.indexOf('data:image/png;base64,') !== 0) continue;
                  var r = imgs[i].getBoundingClientRect();
                  if (r.width >= 120 && r.width <= 260 && r.height >= 120 && r.height <= 260) {
                    qrFound = true;
                    qrImageDataUrl = src;
                    break;
                  }
                }
              } catch (_) {}

              var qrDomExpired = bodyText.indexOf('二维码已失效') !== -1 ||
                bodyText.indexOf('二维码已过期') !== -1 ||
                bodyText.indexOf('点击刷新') !== -1;

              var hasQrOrLoginSignal = qrFound ||
                bodyText.indexOf('扫码登录') !== -1 ||
                bodyText.indexOf('验证码登录') !== -1 ||
                bodyText.indexOf('密码登录') !== -1;

              var selfUid = null;
              var douyinNickname = null;
              var hasLocalStorageUserInfo = false;
              try {
                var rawUser = localStorage.getItem('user_info');
                if (rawUser) {
                  var parsed = JSON.parse(rawUser);
                  if (parsed && typeof parsed === 'object') {
                    hasLocalStorageUserInfo = true;
                    var uidCandidates = [
                      parsed.sec_uid,
                      parsed.sec_user_id,
                      parsed.secUid,
                      parsed.secUserId,
                      parsed.uid,
                      parsed.user_id,
                      parsed.userId,
                      parsed.unique_id,
                      parsed.uniqueId
                    ];
                    for (var u = 0; u < uidCandidates.length; u++) {
                      var candidate = uidCandidates[u];
                      if (typeof candidate === 'string' && candidate.trim()) {
                        selfUid = candidate.trim();
                        break;
                      }
                      if (typeof candidate === 'number' && isFinite(candidate)) {
                        selfUid = String(candidate);
                        break;
                      }
                    }
                    var nick = parsed.nickname || parsed.nickName || null;
                    if (typeof nick === 'string' && nick.trim()) {
                      douyinNickname = nick.trim();
                    }
                  }
                }
              } catch (_) {}

              return {
                originPath: (host ? (String(window.location.protocol || '') + '//' + host + pathname) : pathname),
                isDouyinHost: host.indexOf('douyin.com') !== -1,
                isBlankPage: isBlankPage,
                isErrorPage: isErrorPage,
                isChatRoute: isChatRoute,
                pageTitleLen: pageTitleLen,
                readyState: String(document.readyState || ''),
                loggedIn: loggedIn,
                verificationRequired: verificationRequired,
                verificationSignal: verificationSignal,
                hasQrOrLoginSignal: hasQrOrLoginSignal,
                qrImageDataUrl: qrImageDataUrl,
                qrDomExpired: qrDomExpired,
                hasLocalStorageUserInfo: hasLocalStorageUserInfo,
                conversationCount: conversationCount,
                selfUid: selfUid,
                douyinNickname: douyinNickname
              };
            })();
        """.trimIndent()
    }

    fun buildRefreshLoginQrCodeJs(): String = """
        (function () {
          $DOM_HELPERS_JS
          var root = document.querySelector('#douyin-login-new-id, [class*="login-panel"], [class*="web-login"], [role="dialog"]') || document.body;
          if (!root) return { clicked: false };
          var candidates = root.querySelectorAll('button, [role="button"], span, div, p, a');
          var labels = ['点击刷新', '刷新二维码', '刷新', '重新获取'];
          for (var l = 0; l < labels.length; l++) {
            var targetLabel = labels[l];
            for (var i = 0; i < candidates.length; i++) {
              var el = candidates[i];
              if (!__flameIsVisible(el)) continue;
              var txt = String(el.innerText || el.textContent || '').replace(/\s+/g, '').trim();
              if (txt === targetLabel) {
                var clickTarget = __flameResolveClickTarget(el, false);
                if (__flameDispatchClick(clickTarget)) {
                  return { clicked: true, via: targetLabel };
                }
              }
            }
          }
          var imgs = root.querySelectorAll('img');
          for (var j = 0; j < imgs.length; j++) {
            var src = imgs[j].src || '';
            if (src.indexOf('data:image') !== 0) continue;
            var r = imgs[j].getBoundingClientRect();
            if (r.width >= 120 && r.width <= 260 && r.height >= 120 && r.height <= 260) {
              var overlay = document.elementFromPoint
                ? document.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2)
                : imgs[j];
              if (overlay && overlay !== imgs[j] && __flameDispatchClick(overlay)) {
                return { clicked: true, via: 'qr_overlay' };
              }
            }
          }
          return { clicked: false };
        })();
    """.trimIndent()

    fun buildSwitchToSmsLoginTabJs(): String = """
        (function () {
          $DOM_HELPERS_JS
          var root = document.querySelector('#douyin-login-new-id, [class*="login-panel"], [class*="web-login"], [role="dialog"]') || document.body;
          if (!root) return { clicked: false };
          var nodes = root.querySelectorAll('[role="tab"], li, span, div');
          for (var i = 0; i < nodes.length; i++) {
            var el = nodes[i];
            if (!__flameIsVisible(el)) continue;
            var txt = String(el.innerText || el.textContent || '').replace(/\s+/g, '').trim();
            if (txt === '验证码登录') {
              var clickTarget = __flameResolveClickTarget(el, false);
              if (__flameDispatchClick(clickTarget)) {
                return { clicked: true };
              }
            }
          }
          return { clicked: false };
        })();
    """.trimIndent()

    /**
     * 只读检查单个会话 DOM 行关联的 React Props/Fiber（有界遍历），提取经严格校验的群聊元数据：
     * - 仅当明确观察到群会话类型（`type === 2` / `conversationType === 2` / `:2:` 前缀 / `isGroup === true`）
     *   且会话 ID 为 15..25 位 ASCII 数字（非 `:1:` 单聊 ID）且群名非空合法时，才返回 `domGroupId` + `domGroupTitle`；
     * - 仅作身份池补充输入，后续仍统一经 `IdentityDisambiguator.matchGroup` 做同名冲突校验，绝不猜测绑定。
     */
    private const val DOM_REACT_GROUP_INSPECTOR_JS = """
        function __flamePickIdStr(obj, keys) {
          if (!obj || typeof obj !== 'object') return null;
          for (var i = 0; i < keys.length; i++) {
            var val = obj[keys[i]];
            if (typeof val === 'string' && val.trim()) return val.trim();
            if (typeof val === 'number' && isFinite(val)) return String(val);
            if (typeof val === 'bigint') return val.toString();
            if (val && typeof val === 'object' && typeof val.toString === 'function') {
              try {
                var s = String(val.toString()).trim();
                if (s && s !== '[object Object]') return s;
              } catch (_) {}
            }
          }
          return null;
        }
        function __flamePickRawField(obj, keys) {
          if (!obj || typeof obj !== 'object') return undefined;
          for (var i = 0; i < keys.length; i++) {
            if (obj[keys[i]] !== undefined && obj[keys[i]] !== null) return obj[keys[i]];
          }
          return undefined;
        }
        function __flameNormTitle(s) {
          return String(s || '').replace(/\s+/g, ' ').trim();
        }
        function __flameInspectConvObject(node) {
          if (!node || typeof node !== 'object' || Array.isArray(node)) return null;
          if (typeof Node !== 'undefined' && node instanceof Node) return null;
          var core = (node.coreInfo && typeof node.coreInfo === 'object')
            ? node.coreInfo
            : ((node.core_info && typeof node.core_info === 'object') ? node.core_info : null);
          var typeVal = __flamePickRawField(node, ['type', 'conversationType', 'conversation_type', 'convType', 'conv_type', 'chatType', 'chat_type']);
          var coreTypeVal = core ? __flamePickRawField(core, ['type', 'conversationType', 'conversation_type', 'convType', 'conv_type']) : undefined;
          var effType = typeVal !== undefined ? typeVal : coreTypeVal;
          var typeStr = effType !== undefined && effType !== null ? String(effType).trim().toLowerCase() : '';
          var isSingle = (effType === 1 || typeStr === '1' || typeStr === 'single' || typeStr === 'one_to_one' || typeStr === 'friend' || typeStr === 'private');
          var isGroup = !isSingle && (
            effType === 2 ||
            typeStr === '2' ||
            typeStr === 'group' ||
            node.isGroup === true ||
            node.is_group === true ||
            (core && (core.isGroup === true || core.is_group === true))
          );

          var idKeys = [
            'conversationShortId', 'conversation_short_id',
            'shortId', 'short_id',
            'convShortId', 'conv_short_id',
            'conversationId', 'conversation_id',
            'convId', 'conv_id',
            'id'
          ];
          var groupId = null;
          var sawSingleColonId = false;
          var sources = core ? [node, core] : [node];
          for (var sIdx = 0; sIdx < sources.length; sIdx++) {
            var src = sources[sIdx];
            for (var kIdx = 0; kIdx < idKeys.length; kIdx++) {
              var rawId = __flamePickIdStr(src, [idKeys[kIdx]]);
              if (!rawId) continue;
              if (/^\d{1,4}:1:/.test(rawId)) {
                sawSingleColonId = true;
                continue;
              }
              if (/^\d{15,25}$/.test(rawId) && isGroup && !groupId) {
                groupId = rawId;
              } else {
                var cm = rawId.match(/^\d{1,4}:2:(\d{15,25})$/);
                if (cm && !isSingle && !groupId) {
                  groupId = cm[1];
                  isGroup = true;
                }
              }
            }
          }
          if (sawSingleColonId) {
            isSingle = true;
            isGroup = false;
            groupId = null;
          }

          var titleKeys = [
            'name', 'title',
            'groupName', 'group_name',
            'convName', 'conv_name',
            'conversationName', 'conversation_name',
            'displayName', 'display_name'
          ];
          var rawTitle = (core ? __flamePickIdStr(core, titleKeys) : null) || __flamePickIdStr(node, titleKeys);
          var cleanTitle = rawTitle ? rawTitle.trim() : null;
          if (cleanTitle && (cleanTitle.length > 80 || /[\x00-\x1F\x7F]/.test(cleanTitle))) {
            cleanTitle = null;
          }

          if (groupId && cleanTitle) {
            return { convType: 'group', groupId: groupId, groupTitle: cleanTitle };
          }
          if (isGroup) {
            return { convType: 'group', groupId: null, groupTitle: cleanTitle };
          }
          if (isSingle) {
            return { convType: 'friend', groupId: null, groupTitle: cleanTitle };
          }
          return null;
        }
        function __flameInspectRowReactGroup(row, rowTitle) {
          var result = { domConversationType: null, domGroupId: null, domGroupTitle: null };
          if (!row) return result;
          var normRowTitle = __flameNormTitle(rowTitle);
          var roots = [];
          try {
            var targets = [row];
            if (row.firstElementChild) targets.push(row.firstElementChild);
            if (row.parentElement) targets.push(row.parentElement);
            for (var t = 0; t < targets.length; t++) {
              var el = targets[t];
              if (!el) continue;
              var ownKeys = Object.keys(el);
              for (var k = 0; k < ownKeys.length; k++) {
                var key = ownKeys[k];
                if (key.indexOf('__reactProps$') === 0 && el[key]) {
                  roots.push(el[key]);
                } else if (key.indexOf('__reactFiber$') === 0 && el[key]) {
                  var fib = el[key];
                  for (var hop = 0; hop < 10 && fib; hop++) {
                    if (fib.memoizedProps && typeof fib.memoizedProps === 'object') {
                      roots.push(fib.memoizedProps);
                    }
                    fib = fib.return;
                  }
                }
              }
            }
          } catch (_) {}

          var queue = [];
          for (var r = 0; r < roots.length; r++) {
            queue.push({ obj: roots[r], depth: 0 });
          }
          var visited = 0;
          var seen = typeof WeakSet !== 'undefined' ? new WeakSet() : null;
          var skipProp = {
            children: true, _owner: true, _store: true, ref: true,
            style: true, target: true, currentTarget: true, view: true,
            return: true, child: true, sibling: true, stateNode: true, alternate: true
          };
          while (queue.length > 0 && visited < 80) {
            var cur = queue.shift();
            if (!cur || !cur.obj || typeof cur.obj !== 'object') continue;
            if (typeof Node !== 'undefined' && cur.obj instanceof Node) continue;
            if (typeof Window !== 'undefined' && cur.obj instanceof Window) continue;
            if (seen) {
              if (seen.has(cur.obj)) continue;
              seen.add(cur.obj);
            }
            visited += 1;

            var hit = __flameInspectConvObject(cur.obj);
            if (hit) {
              var hitNorm = hit.groupTitle ? __flameNormTitle(hit.groupTitle) : '';
              var titleMatches = !normRowTitle || !hitNorm || hitNorm === normRowTitle;
              if (hit.convType === 'group' && hit.groupId && hit.groupTitle && (!normRowTitle || hitNorm === normRowTitle)) {
                result.domConversationType = 'group';
                result.domGroupId = hit.groupId;
                result.domGroupTitle = hit.groupTitle;
                return result;
              }
              if (hit.convType === 'group' && titleMatches && !result.domConversationType) {
                result.domConversationType = 'group';
              } else if (hit.convType === 'friend' && titleMatches && !result.domConversationType) {
                result.domConversationType = 'friend';
              }
            }

            if (cur.depth < 3 && !Array.isArray(cur.obj)) {
              var keys;
              try { keys = Object.keys(cur.obj); } catch (_) { continue; }
              var maxK = Math.min(keys.length, 24);
              for (var ki = 0; ki < maxK; ki++) {
                var propName = keys[ki];
                if (skipProp[propName] || propName.indexOf('__react') === 0 || propName.indexOf('on') === 0) continue;
                var val;
                try { val = cur.obj[propName]; } catch (_) { continue; }
                if (val && typeof val === 'object') {
                  queue.push({ obj: val, depth: cur.depth + 1 });
                }
              }
            }
          }
          return result;
        }
    """

    fun buildCollectVisibleGroupIdentitiesJs(bundle: SelectorBundle): String {
        val itemLit = jsStringLiteral(bundle.conversationItem)
        val titleLit = jsStringLiteral(bundle.conversationTitle)
        return """
            (function () {
              $DOM_HELPERS_JS
              $DOM_REACT_GROUP_INSPECTOR_JS
              var rows = document.querySelectorAll($itemLit);
              var groups = [];
              var seen = Object.create(null);
              for (var i = 0; i < rows.length; i++) {
                var row = rows[i];
                var titleEl = row.querySelector($titleLit);
                var title = titleEl ? __flameFirstNonEmptyLine(titleEl.innerText || titleEl.textContent || '') : '';
                var reactMeta = __flameInspectRowReactGroup(row, title);
                if (reactMeta && reactMeta.domGroupId && reactMeta.domGroupTitle) {
                  var key = reactMeta.domGroupId + '|' + reactMeta.domGroupTitle;
                  if (!seen[key]) {
                    seen[key] = true;
                    groups.push({
                      platform_conversation_id: reactMeta.domGroupId,
                      title: reactMeta.domGroupTitle,
                      conversation_type: 'group'
                    });
                  }
                }
              }
              return { groups: groups };
            })();
        """.trimIndent()
    }

    private const val CONVERSATION_SCROLLER_HELPER_JS = """
        function __flameIsScrollableY(el) {
          if (!el) return false;
          var ch = el.clientHeight || 0;
          var sh = el.scrollHeight || 0;
          if (ch <= 0 || sh <= ch + 5) return false;
          try {
            var st = window.getComputedStyle ? window.getComputedStyle(el) : null;
            var oy = st ? String(st.overflowY || '') : '';
            if (oy === 'hidden' || oy === 'clip') return false;
          } catch (_) {}
          return true;
        }
        function __flameResolveConversationScroller(listEl) {
          if (!listEl) return { el: null, source: 'none' };
          if (__flameIsScrollableY(listEl)) {
            return { el: listEl, source: 'list' };
          }
          try {
            var best = null;
            var bestOverflow = 0;
            var nodes = listEl.querySelectorAll('div, ul, section');
            var limit = Math.min(nodes.length, 60);
            for (var i = 0; i < limit; i++) {
              var cand = nodes[i];
              if (!__flameIsScrollableY(cand)) continue;
              var overflow = (cand.scrollHeight || 0) - (cand.clientHeight || 0);
              if (overflow > bestOverflow) {
                bestOverflow = overflow;
                best = cand;
              }
            }
            if (best) return { el: best, source: 'descendant' };
          } catch (_) {}
          try {
            var parent = listEl.parentElement;
            for (var p = 0; p < 4 && parent && parent !== document.body; p++) {
              if (__flameIsScrollableY(parent)) {
                return { el: parent, source: 'ancestor' };
              }
              parent = parent.parentElement;
            }
          } catch (_) {}
          return { el: listEl, source: 'fallback' };
        }
        function __flameDispatchScrollSignals(scroller, listEl, wheelDeltaY) {
          try { scroller.dispatchEvent(new Event('scroll', { bubbles: false })); } catch (_) {}
          if (listEl && listEl !== scroller) {
            try { listEl.dispatchEvent(new Event('scroll', { bubbles: false })); } catch (_) {}
          }
          if (wheelDeltaY && wheelDeltaY !== 0) {
            try {
              var rect = scroller.getBoundingClientRect ? scroller.getBoundingClientRect() : null;
              var cx = rect ? (rect.left + rect.width / 2) : 160;
              var cy = rect ? (rect.top + rect.height / 2) : 300;
              var target = (document.elementFromPoint && rect && rect.width > 0 && rect.height > 0)
                ? (document.elementFromPoint(cx, cy) || scroller)
                : scroller;
              var wheelOpts = {
                bubbles: true,
                cancelable: true,
                view: window,
                deltaX: 0,
                deltaY: wheelDeltaY,
                deltaMode: 0,
                clientX: cx,
                clientY: cy
              };
              target.dispatchEvent(new WheelEvent('wheel', wheelOpts));
              if (target !== scroller) {
                scroller.dispatchEvent(new WheelEvent('wheel', wheelOpts));
              }
            } catch (_) {}
          }
        }
    """

    fun buildScrollAndCollectChatsJs(bundle: SelectorBundle): String {
        val listLit = jsStringLiteral(bundle.conversationList)
        val itemLit = jsStringLiteral(bundle.conversationItem)
        val titleLit = jsStringLiteral(bundle.conversationTitle)
        val streakLit = jsStringLiteral(bundle.conversationStreak)
        val streakDaysLit = jsStringLiteral(bundle.conversationStreakDays)
        return """
            (function () {
              $DOM_HELPERS_JS
              $DOM_REACT_GROUP_INSPECTOR_JS
              $CONVERSATION_SCROLLER_HELPER_JS
              var listEl = document.querySelector($listLit);
              if (!listEl) {
                return { listFound: false, atBottom: true, items: [] };
              }
              var resolvedScroller = __flameResolveConversationScroller(listEl);
              var scroller = resolvedScroller.el || listEl;
              var rows = document.querySelectorAll($itemLit);
              var items = [];
              for (var i = 0; i < rows.length; i++) {
                var row = rows[i];
                var titleEl = row.querySelector($titleLit);
                if (!titleEl) continue;
                var title = __flameFirstNonEmptyLine(titleEl.innerText || titleEl.textContent || '');
                if (!title) continue;

                var streakDays = null;
                var streakKind = null;
                var streakLabel = null;
                var streakIconVariant = null;
                var streakLitToday = null;
                var hasStreakIcon = false;
                var streakEl = row.querySelector($streakLit);
                if (streakEl) {
                  var daysEl = streakEl.querySelector($streakDaysLit);
                  var rawStreakText = daysEl
                    ? String(daysEl.innerText || daysEl.textContent || '')
                    : String(streakEl.innerText || streakEl.textContent || '');
                  rawStreakText = rawStreakText.replace(/\s+/g, ' ').trim();
                  if (!rawStreakText && daysEl) {
                    rawStreakText = String(streakEl.innerText || streakEl.textContent || '').replace(/\s+/g, ' ').trim();
                  }
                  if (/^\d+$/.test(rawStreakText)) {
                    var parsedDays = parseInt(rawStreakText, 10);
                    if (parsedDays > 0) {
                      streakDays = parsedDays;
                    }
                  } else if (rawStreakText) {
                    streakLabel = rawStreakText;
                  }
                  var icon = streakEl.querySelector("img[src*='flame_icon']");
                  var src = icon ? String(icon.getAttribute('src') || '').trim() : '';
                  if (src) {
                    hasStreakIcon = true;
                    var lowerSrc = src.toLowerCase();
                    if (lowerSrc.indexOf('/couple/') !== -1) streakKind = 'couple';
                    else if (lowerSrc.indexOf('/family/') !== -1) streakKind = 'family';
                    else if (lowerSrc.indexOf('/soulmate/') !== -1) streakKind = 'soulmate';
                    else if (lowerSrc.indexOf('/mate/') !== -1) streakKind = 'mate';
                    else if (lowerSrc.indexOf('/bosom/') !== -1) streakKind = 'bosom';
                    else if (lowerSrc.indexOf('/normal/') !== -1) streakKind = 'normal';
                    try {
                      var cleanSrc = src.split(/[?#]/, 1)[0];
                      var marker = 'flame_icon/';
                      var mIdx = cleanSrc.toLowerCase().indexOf(marker);
                      if (mIdx !== -1) {
                        var tail = cleanSrc.slice(mIdx + marker.length).replace(/^\/+/, '');
                        if (tail && tail.length <= 80 && tail.indexOf('..') === -1 && /^[a-zA-Z0-9._\/-]+$/.test(tail)) {
                          streakIconVariant = tail;
                        }
                      }
                    } catch (_) {}
                  }
                  if (streakIconVariant) {
                    var slashIdx = streakIconVariant.lastIndexOf('/');
                    var iconFileName = slashIdx !== -1 ? streakIconVariant.slice(slashIdx + 1) : streakIconVariant;
                    var lowerFileName = String(iconFileName || '').toLowerCase();
                    if (lowerFileName.indexOf('gray_') !== -1) {
                      streakLitToday = false;
                    } else if (iconFileName &&
                        typeof streakDays === 'number' && streakDays > 0 &&
                        (!streakLabel || (streakLabel.indexOf('重燃') === -1 && streakLabel.indexOf('消失') === -1 && streakLabel.indexOf('点亮中') === -1))) {
                      streakLitToday = true;
                    }
                  }
                }

                var reactMeta = __flameInspectRowReactGroup(row, title);

                items.push({
                  title: title,
                  streakDays: streakDays,
                  streakKind: streakKind,
                  streakLabel: streakLabel,
                  streakIconVariant: streakIconVariant,
                  streakLitToday: streakLitToday,
                  hasStreakIcon: hasStreakIcon,
                  domConversationType: reactMeta ? reactMeta.domConversationType : null,
                  domGroupId: reactMeta ? reactMeta.domGroupId : null,
                  domGroupTitle: reactMeta ? reactMeta.domGroupTitle : null
                });
              }

              var sTop = Math.round(scroller.scrollTop || 0);
              var sHeight = Math.round(scroller.scrollHeight || 0);
              var cHeight = Math.round(scroller.clientHeight || 0);
              var atBottom = (sTop + cHeight >= sHeight - 5);
              var protoInFlight = typeof window.__flameProtoInFlight === 'number' ? Math.max(0, window.__flameProtoInFlight) : 0;
              var lastProtoAt = typeof window.__flameLastProtoAt === 'number' ? window.__flameLastProtoAt : 0;
              var protoQuietMs = lastProtoAt > 0 ? Math.max(0, Date.now() - lastProtoAt) : -1;
              return {
                listFound: true,
                atBottom: atBottom,
                scrollerSource: resolvedScroller.source,
                visibleRowCount: rows.length,
                scrollTop: sTop,
                scrollHeight: sHeight,
                clientHeight: cHeight,
                protoInFlight: protoInFlight,
                protoQuietMs: protoQuietMs,
                items: items
              };
            })();
        """.trimIndent()
    }

    fun buildReadConversationSyncStateJs(bundle: SelectorBundle): String {
        val listLit = jsStringLiteral(bundle.conversationList)
        val itemLit = jsStringLiteral(bundle.conversationItem)
        return """
            (function () {
              $CONVERSATION_SCROLLER_HELPER_JS
              var listEl = document.querySelector($listLit);
              if (!listEl) {
                return { listFound: false, scrollHeight: 0, clientHeight: 0, visibleRowCount: 0, protoInFlight: 0, protoQuietMs: -1 };
              }
              var resolvedScroller = __flameResolveConversationScroller(listEl);
              var scroller = resolvedScroller.el || listEl;
              var protoInFlight = typeof window.__flameProtoInFlight === 'number' ? Math.max(0, window.__flameProtoInFlight) : 0;
              var lastProtoAt = typeof window.__flameLastProtoAt === 'number' ? window.__flameLastProtoAt : 0;
              var protoQuietMs = lastProtoAt > 0 ? Math.max(0, Date.now() - lastProtoAt) : -1;
              return {
                listFound: true,
                scrollHeight: Math.round(scroller.scrollHeight || 0),
                clientHeight: Math.round(scroller.clientHeight || 0),
                visibleRowCount: document.querySelectorAll($itemLit).length,
                protoInFlight: protoInFlight,
                protoQuietMs: protoQuietMs
              };
            })();
        """.trimIndent()
    }

    fun buildScrollConversationListStepJs(
        bundle: SelectorBundle,
        deltaPx: Int = 900,
        nudgeBottom: Boolean = false,
    ): String {
        val listLit = jsStringLiteral(bundle.conversationList)
        val nudgeExpr = if (nudgeBottom) "true" else "false"
        return """
            (function () {
              $CONVERSATION_SCROLLER_HELPER_JS
              var listEl = document.querySelector($listLit);
              if (!listEl) return { listFound: false, atBottom: true };
              var resolvedScroller = __flameResolveConversationScroller(listEl);
              var scroller = resolvedScroller.el || listEl;
              var delta = $deltaPx;
              var nudge = $nudgeExpr;
              var beforeTop = scroller.scrollTop || 0;
              var maxTop = Math.max(0, (scroller.scrollHeight || 0) - (scroller.clientHeight || 0));
              if (delta === 0) {
                scroller.scrollTop = 0;
                __flameDispatchScrollSignals(scroller, listEl, 0);
              } else if (nudge) {
                var pullBack = Math.max(0, maxTop - Math.min(96, Math.max(32, Math.floor((scroller.clientHeight || 400) * 0.12))));
                if (maxTop > 0 && pullBack < maxTop) {
                  scroller.scrollTop = pullBack;
                  __flameDispatchScrollSignals(scroller, listEl, 0);
                }
                scroller.scrollTop = scroller.scrollHeight || (beforeTop + delta);
                __flameDispatchScrollSignals(scroller, listEl, Math.max(240, delta));
              } else {
                var nextTop = Math.max(0, beforeTop + delta);
                scroller.scrollTop = nextTop;
                __flameDispatchScrollSignals(scroller, listEl, delta);
              }
              var afterTop = Math.round(scroller.scrollTop || 0);
              var sHeight = Math.round(scroller.scrollHeight || 0);
              var cHeight = Math.round(scroller.clientHeight || 0);
              return {
                listFound: true,
                scrollerSource: resolvedScroller.source,
                atBottom: (afterTop + cHeight >= sHeight - 5),
                scrollTop: afterTop,
                beforeScrollTop: Math.round(beforeTop),
                scrollHeight: sHeight,
                clientHeight: cHeight
              };
            })();
        """.trimIndent()
    }

    private const val TITLE_MATCH_HELPER_JS = """
        function __flameNormalizeMatchTitle(raw) {
          var s = String(raw || '')
            .replace(/[\u200B\u200C\u200D\uFEFF]/g, '')
            .replace(/\s+/g, ' ')
            .trim();
          s = s.replace(/\s*[(（]\d+\+?[)）]$/, '').trim();
          return s;
        }
    """

    fun buildClearSearchInputIfNonEmptyJs(bundle: SelectorBundle): String {
        val selectorsLit = jsStringArrayLiteral(searchInputSelectors(bundle))
        return """
            (function () {
              $DOM_HELPERS_JS
              var hit = __flameFirstVisible($selectorsLit);
              if (!hit) {
                var all = $selectorsLit;
                for (var i = 0; i < all.length; i++) {
                  var fallback = document.querySelector(all[i]);
                  if (fallback) { hit = { selector: all[i], el: fallback }; break; }
                }
              }
              if (!hit || !hit.el) return { found: false, cleared: false };
              var input = hit.el;
              var curVal = String(input.value || '');
              var hasSearchRows = false;
              try { hasSearchRows = document.querySelectorAll('[class*="SearchPanelitem"]').length > 0; } catch (_) {}
              if (!curVal && !hasSearchRows) {
                return { found: true, cleared: false };
              }
              try {
                var wrap = input.closest ? (input.closest('.semi-input-wrapper') || input.parentElement) : input.parentElement;
                if (wrap) {
                  var clearBtn = wrap.querySelector('.semi-input-clearbtn, [class*="clearBtn"], [aria-label*="清除"], [aria-label*="清空"]');
                  if (clearBtn && __flameIsVisible(clearBtn)) {
                    __flameDispatchClick(clearBtn);
                  }
                }
              } catch (_) {}
              try { input.focus(); } catch (_) {}
              try {
                var prev = String(input.value || '');
                var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                if (desc && typeof desc.set === 'function') {
                  desc.set.call(input, '');
                } else {
                  input.value = '';
                }
                var tracker = input._valueTracker;
                if (tracker && typeof tracker.setValue === 'function') {
                  tracker.setValue(prev || ' ');
                }
              } catch (_) {
                input.value = '';
              }
              try {
                input.dispatchEvent(new InputEvent('input', { bubbles: true, cancelable: true, inputType: 'deleteContentBackward', data: null }));
              } catch (_) {
                try { input.dispatchEvent(new Event('input', { bubbles: true })); } catch (__) {}
              }
              try { input.dispatchEvent(new Event('change', { bubbles: true })); } catch (_) {}
              try { input.blur(); } catch (_) {}
              return { found: true, cleared: true };
            })();
        """.trimIndent()
    }

    fun buildFillSearchInputJs(bundle: SelectorBundle, query: String): String {
        val selectorsLit = jsStringArrayLiteral(searchInputSelectors(bundle))
        val queryLit = jsStringLiteral(query)
        return """
            (function () {
              $DOM_HELPERS_JS
              var hit = __flameFirstVisible($selectorsLit);
              if (!hit) {
                var all = $selectorsLit;
                for (var i = 0; i < all.length; i++) {
                  var fallback = document.querySelector(all[i]);
                  if (fallback) { hit = { selector: all[i], el: fallback }; break; }
                }
              }
              if (!hit || !hit.el) return { found: false };
              var input = hit.el;
              var nextVal = $queryLit;
              if (!nextVal) {
                try {
                  var wrap = input.closest ? (input.closest('.semi-input-wrapper') || input.parentElement) : input.parentElement;
                  if (wrap) {
                    var clearBtn = wrap.querySelector('.semi-input-clearbtn, [class*="clearBtn"], [aria-label*="清除"], [aria-label*="清空"]');
                    if (clearBtn && __flameIsVisible(clearBtn)) {
                      __flameDispatchClick(clearBtn);
                    }
                  }
                } catch (_) {}
              } else {
                try { __flameDispatchClick(input); } catch (_) {}
              }
              try { input.focus(); } catch (_) {}
              try {
                var prev = String(input.value || '');
                var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                if (desc && typeof desc.set === 'function') {
                  desc.set.call(input, nextVal);
                } else {
                  input.value = nextVal;
                }
                var tracker = input._valueTracker;
                if (tracker && typeof tracker.setValue === 'function') {
                  tracker.setValue(prev === nextVal ? (nextVal ? '' : ' ') : prev);
                }
              } catch (_) {
                input.value = nextVal;
              }
              try {
                input.dispatchEvent(new InputEvent('input', {
                  bubbles: true,
                  cancelable: true,
                  inputType: nextVal ? 'insertText' : 'deleteContentBackward',
                  data: nextVal || null
                }));
              } catch (_) {
                try { input.dispatchEvent(new Event('input', { bubbles: true })); } catch (__) {}
              }
              try { input.dispatchEvent(new Event('change', { bubbles: true })); } catch (_) {}
              if (!nextVal) {
                try { input.blur(); } catch (_) {}
              }
              return { found: true, selector: hit.selector };
            })();
        """.trimIndent()
    }

    fun buildSearchAndOpenJs(
        bundle: SelectorBundle,
        targetName: String,
        candidateIndex: Int = 0,
    ): String {
        val searchResultItemLit = jsStringLiteral(bundle.searchResultItem)
        val convItemLit = jsStringLiteral(bundle.conversationItem)
        val exactNameSelectorsLit = jsStringArrayLiteral(bundle.searchNameExactSelectors)
        val nameSelectorsLit = jsStringArrayLiteral(bundle.searchNameSelectors)
        val convTitleLit = jsStringLiteral(bundle.conversationTitle)
        val searchChatBtnLit = jsStringLiteral(bundle.searchChatBtn)
        val targetLit = jsStringLiteral(targetName)
        return """
            (function () {
              $DOM_HELPERS_JS
              $TITLE_MATCH_HELPER_JS
              var targetName = $targetLit;
              var normTarget = __flameNormalizeMatchTitle(targetName);
              var wantIdx = $candidateIndex;
              var exactNameSels = $exactNameSelectorsLit;
              var nameSels = $nameSelectorsLit;

              function titleOf(item) {
                var allSels = exactNameSels.concat(nameSels);
                for (var i = 0; i < allSels.length; i++) {
                  try {
                    var el = item.querySelector(allSels[i]);
                    if (el) {
                      var t = __flameFirstNonEmptyLine(el.innerText || el.textContent || '');
                      if (t) return t;
                    }
                  } catch (_) {}
                }
                try {
                  var titleEl = item.querySelector($convTitleLit);
                  if (titleEl) {
                    var ct = __flameFirstNonEmptyLine(titleEl.innerText || titleEl.textContent || '');
                    if (ct) return ct;
                  }
                } catch (_) {}
                return __flameFirstNonEmptyLine(item.innerText || item.textContent || '');
              }

              function isTitleMatch(rawTitle) {
                if (!rawTitle) return false;
                if (rawTitle === targetName) return true;
                var norm = __flameNormalizeMatchTitle(rawTitle);
                return !!norm && !!normTarget && norm === normTarget;
              }

              var matches = [];
              var visibleRows = 0;
              var searchPanelRows = 0;
              var searchRows = [];
              try { searchRows = Array.from(document.querySelectorAll($searchResultItemLit)); } catch (_) {}
              for (var s = 0; s < searchRows.length; s++) {
                var sr = searchRows[s];
                if (!__flameIsVisible(sr)) continue;
                visibleRows++;
                searchPanelRows++;
                if (isTitleMatch(titleOf(sr))) {
                  var btn = null;
                  try { btn = sr.querySelector($searchChatBtnLit); } catch (_) {}
                  matches.push(btn || sr);
                }
              }

              if (!matches.length) {
                var convRows = [];
                try { convRows = Array.from(document.querySelectorAll($convItemLit)); } catch (_) {}
                for (var c = 0; c < convRows.length; c++) {
                  var cr = convRows[c];
                  if (!__flameIsVisible(cr)) continue;
                  visibleRows++;
                  if (isTitleMatch(titleOf(cr))) {
                    matches.push(cr);
                  }
                }
              }

              if (!matches.length) {
                var divs = document.querySelectorAll('div');
                for (var d = 0; d < divs.length; d++) {
                  var el = divs[d];
                  var txt = String(el.textContent || '').trim();
                  var firstLine = __flameFirstNonEmptyLine(txt);
                  if (!isTitleMatch(firstLine) && txt.indexOf(targetName + '\n') !== 0) continue;
                  if (!__flameIsVisible(el)) continue;
                  var row = el.closest('[class*="Search"], [class*="search"]') || el;
                  var sendMsgBtn = Array.from(row.querySelectorAll('*')).find(function (n) {
                    return String(n.textContent || '').trim() === '发消息';
                  });
                  matches.push(sendMsgBtn || row);
                  break;
                }
              }

              var searchNoResult = false;
              if (!matches.length && searchPanelRows === 0) {
                try {
                  var panels = document.querySelectorAll('[class*="SearchPanel"], [class*="searchPanel"], [class*="search-panel"]');
                  for (var p = 0; p < panels.length; p++) {
                    if (!__flameIsVisible(panels[p])) continue;
                    var pText = String(panels[p].innerText || panels[p].textContent || '');
                    if (pText.indexOf('无搜索结果') !== -1 || pText.indexOf('暂无相关结果') !== -1 || pText.indexOf('没有找到') !== -1 || pText.indexOf('暂无搜索结果') !== -1) {
                      searchNoResult = true;
                      break;
                    }
                  }
                } catch (_) {}
              }

              if (wantIdx >= 0 && wantIdx < matches.length) {
                __flameDispatchClick(matches[wantIdx]);
                return {
                  clicked: true,
                  totalMatches: matches.length,
                  visibleRows: visibleRows,
                  searchPanelRows: searchPanelRows,
                  searchNoResult: searchNoResult,
                  candidateIndex: wantIdx
                };
              }
              return {
                clicked: false,
                totalMatches: matches.length,
                visibleRows: visibleRows,
                searchPanelRows: searchPanelRows,
                searchNoResult: searchNoResult,
                candidateIndex: wantIdx
              };
            })();
        """.trimIndent()
    }

    fun buildClickListItemByTitleJs(
        bundle: SelectorBundle,
        targetName: String,
        candidateIndex: Int = 0,
    ): String {
        val listLit = jsStringLiteral(bundle.conversationList)
        val convItemLit = jsStringLiteral(bundle.conversationItem)
        val convTitleLit = jsStringLiteral(bundle.conversationTitle)
        val targetLit = jsStringLiteral(targetName)
        return """
            (function () {
              $DOM_HELPERS_JS
              $CONVERSATION_SCROLLER_HELPER_JS
              $TITLE_MATCH_HELPER_JS
              var targetName = $targetLit;
              var normTarget = __flameNormalizeMatchTitle(targetName);
              var wantIdx = $candidateIndex;
              var listEl = document.querySelector($listLit);
              var resolvedScroller = listEl ? __flameResolveConversationScroller(listEl) : { el: null, source: 'none' };
              var scroller = resolvedScroller.el || listEl;
              var sTop = scroller ? Math.round(scroller.scrollTop || 0) : 0;
              var cHeight = scroller ? Math.round(scroller.clientHeight || 0) : 0;
              var sHeight = scroller ? Math.round(scroller.scrollHeight || 0) : 0;
              var atBottom = scroller ? (sTop + cHeight >= sHeight - 5) : true;

              var rows = Array.from(document.querySelectorAll($convItemLit));
              var matches = [];
              var visibleRows = 0;
              for (var i = 0; i < rows.length; i++) {
                var row = rows[i];
                if (!__flameIsVisible(row)) continue;
                visibleRows++;
                var titleEl = row.querySelector($convTitleLit);
                var t = titleEl
                  ? __flameFirstNonEmptyLine(titleEl.innerText || titleEl.textContent || '')
                  : __flameFirstNonEmptyLine(row.innerText || row.textContent || '');
                var norm = __flameNormalizeMatchTitle(t);
                if (t === targetName || (!!norm && !!normTarget && norm === normTarget)) {
                  matches.push(row);
                }
              }
              if (wantIdx >= 0 && wantIdx < matches.length) {
                var targetRow = matches[wantIdx];
                var clickNode = targetRow.querySelector($convTitleLit) || targetRow;
                __flameDispatchClick(clickNode);
                return {
                  clicked: true,
                  totalMatches: matches.length,
                  visibleRows: visibleRows,
                  listFound: !!listEl,
                  atBottom: atBottom,
                  scrollTop: sTop,
                  clientHeight: cHeight,
                  scrollHeight: sHeight
                };
              }
              return {
                clicked: false,
                totalMatches: matches.length,
                visibleRows: visibleRows,
                listFound: !!listEl,
                atBottom: atBottom,
                scrollTop: sTop,
                clientHeight: cHeight,
                scrollHeight: sHeight
              };
            })();
        """.trimIndent()
    }

    fun buildReadRightTitleJs(bundle: SelectorBundle): String {
        val rightTitleLit = jsStringLiteral(bundle.rightTitle)
        return """
            (function () {
              var el = document.querySelector($rightTitleLit);
              if (!el) return { found: false, rawTitle: null };
              var text = String(el.innerText || el.textContent || '').trim();
              return { found: true, rawTitle: text || null };
            })();
        """.trimIndent()
    }

    fun buildCheckEditorEmptyJs(bundle: SelectorBundle): String {
        val selectorsLit = jsStringArrayLiteral(editorSelectors(bundle))
        return """
            (function () {
              $DOM_HELPERS_JS
              var hit = __flameFirstVisible($selectorsLit);
              if (!hit || !hit.el) {
                return { editorFound: false, editorVisible: false, empty: false, strippedLength: 0 };
              }
              var raw = String(hit.el.innerText || hit.el.textContent || '');
              var stripped = raw.replace(/[\s\u200B\u200C\u200D\uFEFF]+/g, '');
              return {
                editorFound: true,
                editorVisible: true,
                empty: stripped.length === 0,
                strippedLength: stripped.length
              };
            })();
        """.trimIndent()
    }

    fun buildInsertTextExecCommandJs(bundle: SelectorBundle, text: String): String {
        val selectorsLit = jsStringArrayLiteral(editorSelectors(bundle))
        val textLit = jsStringLiteral(text)
        return """
            (function () {
              $DOM_HELPERS_JS
              var hit = __flameFirstVisible($selectorsLit);
              if (!hit || !hit.el) {
                return { ok: false, reason: 'editor_not_found' };
              }
              var editor = hit.el;
              var existing = String(editor.innerText || editor.textContent || '').replace(/[\s\u200B\u200C\u200D\uFEFF]+/g, '');
              if (existing.length > 0) {
                return { ok: false, reason: 'editor_not_empty' };
              }

              editor.focus();
              try {
                var sel = window.getSelection();
                var range = document.createRange();
                range.selectNodeContents(editor);
                range.collapse(false);
                sel.removeAllRanges();
                sel.addRange(range);
              } catch (_) {}

              var lines = String($textLit).split('\n');
              var allExecOk = true;
              for (var i = 0; i < lines.length; i++) {
                if (lines[i].length > 0) {
                  var ok = false;
                  try {
                    ok = document.execCommand('insertText', false, lines[i]);
                  } catch (_) {
                    ok = false;
                  }
                  if (!ok) allExecOk = false;
                }
                if (i < lines.length - 1) {
                  var brOk = false;
                  try {
                    brOk = document.execCommand('insertLineBreak', false, null);
                  } catch (_) {
                    brOk = false;
                  }
                  if (!brOk) {
                    try {
                      brOk = document.execCommand('insertText', false, '\n');
                    } catch (_) {}
                  }
                }
              }

              return { ok: true, execReturn: allExecOk };
            })();
        """.trimIndent()
    }

    fun buildVerifyEditorTextAndSendReadyJs(
        bundle: SelectorBundle,
        expectedText: String,
    ): String {
        val editorSelectorsLit = jsStringArrayLiteral(editorSelectors(bundle))
        val sendButtonsLit = jsStringArrayLiteral(bundle.sendButtons)
        val normalizedExpectedLit = jsStringLiteral(stripBlankAndZeroWidth(expectedText))
        return """
            (function () {
              $DOM_HELPERS_JS
              var hit = __flameFirstVisible($editorSelectorsLit);
              if (!hit || !hit.el) {
                return { editorFound: false, textMatched: false, sendButtonReady: false };
              }
              var actualStripped = String(hit.el.innerText || hit.el.textContent || '')
                .replace(/[\s\u200B\u200C\u200D\uFEFF]+/g, '');
              var expectedStripped = $normalizedExpectedLit;
              var textMatched = expectedStripped.length > 0 && actualStripped.indexOf(expectedStripped) !== -1;

              var sendHit = __flameFirstVisible($sendButtonsLit);
              var sendReady = !!(
                sendHit &&
                sendHit.el &&
                !sendHit.el.disabled &&
                sendHit.el.getAttribute('aria-disabled') !== 'true'
              );
              return {
                editorFound: true,
                textMatched: textMatched,
                sendButtonReady: sendReady
              };
            })();
        """.trimIndent()
    }

    fun buildClickSendButtonJs(bundle: SelectorBundle): String {
        val sendButtonsLit = jsStringArrayLiteral(bundle.sendButtons)
        return """
            (function () {
              $DOM_HELPERS_JS
              var sendHit = __flameFirstVisible($sendButtonsLit);
              if (!sendHit || !sendHit.el) {
                return { clicked: false, reason: 'send_button_not_visible' };
              }
              var btn = sendHit.el;
              if (btn.disabled || btn.getAttribute('aria-disabled') === 'true') {
                return { clicked: false, reason: 'send_button_disabled' };
              }
              __flameDispatchClick(btn);
              return { clicked: true, selector: sendHit.selector };
            })();
        """.trimIndent()
    }

    fun buildSampleTextConfirmJs(
        bundle: SelectorBundle,
        normalizedTargetText: String,
    ): String {
        val failureMarkersLit = jsStringArrayLiteral(bundle.sendFailureMarkers)
        val editorSelectorsLit = jsStringArrayLiteral(editorSelectors(bundle))
        val boxesLit = jsStringLiteral(bundle.mediaMessageBoxes)
        val contentLit = jsStringLiteral(bundle.mediaMessageContent)
        val timeLit = jsStringLiteral(bundle.messageTime)
        val targetLit = jsStringLiteral(stripBlankAndZeroWidth(normalizedTargetText))
        return """
            (function () {
              $DOM_HELPERS_JS
              var failureMarkerHit = __flameIsFailureMarkerHit($failureMarkersLit);

              var editorHit = __flameFirstVisible($editorSelectorsLit);
              var editorFound = !!(editorHit && editorHit.el);
              var editorEmpty = false;
              if (editorFound) {
                var strippedEditor = String(editorHit.el.innerText || editorHit.el.textContent || '')
                  .replace(/[\s\u200B\u200C\u200D\uFEFF]+/g, '');
                editorEmpty = strippedEditor.length === 0;
              }

              var expected = $targetLit;
              var outgoingSignatures = [];
              if (expected.length > 0) {
                var boxes = document.querySelectorAll($boxesLit);
                for (var i = 0; i < boxes.length; i++) {
                  var box = boxes[i];
                  var contents = box.querySelectorAll($contentLit);
                  var ownContent = null;
                  for (var c = 0; c < contents.length; c++) {
                    if (String(contents[c].className || '').indexOf('isFromMe') !== -1) {
                      ownContent = contents[c];
                      break;
                    }
                  }
                  if (!ownContent && String(box.className || '').indexOf('isFromMe') !== -1) {
                    ownContent = contents.length ? contents[0] : box;
                  }
                  if (!ownContent) continue;
                  var normBubble = String(ownContent.innerText || ownContent.textContent || '')
                    .replace(/[\s\u200B\u200C\u200D\uFEFF]+/g, '');
                  if (normBubble.indexOf(expected) === -1) continue;
                  var timeEl = box.querySelector($timeLit);
                  var timeNorm = String(timeEl ? timeEl.textContent : '')
                    .replace(/[\s\u200B\u200C\u200D\uFEFF]+/g, '');
                  outgoingSignatures.push(timeNorm + '\u001F' + normBubble);
                }
              }

              return {
                failureMarkerHit: failureMarkerHit,
                editorFound: editorFound,
                editorEmpty: editorEmpty,
                outgoingSignatures: outgoingSignatures
              };
            })();
        """.trimIndent()
    }

    fun buildSampleOutgoingMediaMultisetJs(
        bundle: SelectorBundle,
        netSinceEpochMs: Long = 0L,
    ): String {
        val failureMarkersLit = jsStringArrayLiteral(bundle.sendFailureMarkers)
        val boxesLit = jsStringLiteral(bundle.mediaMessageBoxes)
        val contentLit = jsStringLiteral(bundle.mediaMessageContent)
        val timeLit = jsStringLiteral(bundle.messageTime)
        return """
            (function () {
              $DOM_HELPERS_JS
              $IMAGE_MODAL_DISCOVERY_HELPERS_JS
              var failureMarkerHit = __flameIsFailureMarkerHit($failureMarkersLit);
              var modalState = __flameFindTopVisibleModal();
              var modalVisible = !!(modalState && modalState.topModal);
              var norm = function (value) {
                return String(value || '')
                  .replace(/[\s\u200B\u200C\u200D\uFEFF]+/g, ' ')
                  .trim();
              };
              var isLocalPreviewSrc = function (src) {
                var lower = String(src || '').trim().toLowerCase();
                return lower.indexOf('blob:') === 0 || lower.indexOf('data:') === 0;
              };
              var isTempUploadImg = function (cls) {
                return String(cls || '').toLowerCase().indexOf('temp') !== -1;
              };
              var sanitizeImgTag = function (cls, rawSrc) {
                var shortCls = String(cls || '').trim().split(/\s+/).slice(0, 2).join('.').slice(0, 40);
                var lower = String(rawSrc || '').trim().toLowerCase();
                var originTag = 'other';
                if (lower.indexOf('blob:') === 0) {
                  originTag = 'blob:';
                } else if (lower.indexOf('data:') === 0) {
                  originTag = 'data:';
                } else {
                  try {
                    var u = new URL(rawSrc, window.location.origin);
                    originTag = String(u.protocol || '') + '//' + String(u.host || '').toLowerCase();
                  } catch (_) {
                    originTag = 'rel';
                  }
                }
                return originTag + (shortCls ? '[' + shortCls + ']' : '');
              };
              var sendingSelector = '[class*="sending"], [class*="Sending"], [class*="uploading"], [class*="Uploading"], [class*="loading"], [class*="Loading"], [role="progressbar"], .semi-spin';
              var signatures = [];
              var remoteSignatures = [];
              var localPreviewSignatures = [];
              var settledLocalSignatures = [];
              var tempPreviewSignatures = [];
              var sanitizedImgSummary = [];
              var pendingImgCount = 0;
              var hasSendingIndicator = false;
              var boxes = document.querySelectorAll($boxesLit);
              for (var i = 0; i < boxes.length; i++) {
                var box = boxes[i];
                var contents = box.querySelectorAll($contentLit);
                var ownContent = null;
                for (var c = 0; c < contents.length; c++) {
                  if (String(contents[c].className || '').indexOf('isFromMe') !== -1) {
                    ownContent = contents[c];
                    break;
                  }
                }
                if (!ownContent && String(box.className || '').indexOf('isFromMe') !== -1) {
                  ownContent = contents.length ? contents[0] : box;
                }
                if (!ownContent) continue;
                try {
                  var indicators = box.querySelectorAll(sendingSelector);
                  for (var s = 0; s < indicators.length; s++) {
                    if (__flameIsVisible(indicators[s])) {
                      hasSendingIndicator = true;
                      break;
                    }
                  }
                } catch (_) {}
                var imgs = ownContent.querySelectorAll('img');
                var nonAvatar = [];
                var remoteNonAvatar = [];
                var previewNonAvatar = [];
                var settledLocalNonAvatar = [];
                var tempPreviewNonAvatar = [];
                for (var k = 0; k < imgs.length; k++) {
                  var rawSrc = String(imgs[k].getAttribute('src') || imgs[k].currentSrc || imgs[k].src || '').trim();
                  if (!rawSrc) {
                    pendingImgCount++;
                    continue;
                  }
                  if (rawSrc.toLowerCase().indexOf('avatar') === -1) {
                    var clsName = String(imgs[k].className || '');
                    var entry = clsName + ':' + rawSrc.split(/[?#]/, 1)[0];
                    nonAvatar.push(entry);
                    if (isLocalPreviewSrc(rawSrc)) {
                      previewNonAvatar.push(entry);
                      if (isTempUploadImg(clsName)) {
                        tempPreviewNonAvatar.push(entry);
                      } else {
                        settledLocalNonAvatar.push(entry);
                      }
                    } else {
                      remoteNonAvatar.push(entry);
                    }
                    if (sanitizedImgSummary.length < 12) {
                      sanitizedImgSummary.push(sanitizeImgTag(clsName, rawSrc));
                    }
                  }
                }
                if (!nonAvatar.length) continue;
                var timeEl = box.querySelector($timeLit);
                var timeText = timeEl ? timeEl.textContent : '';
                var normTime = norm(timeText);
                var normText = norm(ownContent.innerText);
                signatures.push(normTime + '\u001F' + nonAvatar.join('|') + '\u001F' + normText);
                if (remoteNonAvatar.length) {
                  remoteSignatures.push(normTime + '\u001F' + remoteNonAvatar.join('|') + '\u001F' + normText);
                }
                if (previewNonAvatar.length) {
                  localPreviewSignatures.push(normTime + '\u001F' + previewNonAvatar.join('|') + '\u001F' + normText);
                }
                if (settledLocalNonAvatar.length) {
                  settledLocalSignatures.push(normTime + '\u001F' + settledLocalNonAvatar.join('|') + '\u001F' + normText);
                }
                if (tempPreviewNonAvatar.length) {
                  tempPreviewSignatures.push(normTime + '\u001F' + tempPreviewNonAvatar.join('|') + '\u001F' + normText);
                }
              }
              var netSince = $netSinceEpochMs;
              var imSendSucceeded = false;
              var uploadOrSendInFlight = false;
              var uploadOrSendFailed = false;
              if (netSince > 0 && Array.isArray(window.__flameNetEvents)) {
                var evs = window.__flameNetEvents;
                for (var n = 0; n < evs.length; n++) {
                  var ev = evs[n];
                  if (!ev || typeof ev.t !== 'number' || ev.t < netSince) continue;
                  var evHost = String(ev.host || '').toLowerCase();
                  var evPath = String(ev.path || '').toLowerCase();
                  var evFull = evHost + evPath;
                  var isSend = evPath.indexOf('/message/send') !== -1;
                  var isUploadOrSend = isSend ||
                    evPath.indexOf('/upload') !== -1 ||
                    evHost.indexOf('tos-') !== -1 ||
                    evFull.indexOf('imagex') !== -1;
                  if (!isUploadOrSend) continue;
                  var st = typeof ev.status === 'number' ? ev.status : 0;
                  if (st === 0) {
                    uploadOrSendInFlight = true;
                  } else if (st < 0 || st >= 400) {
                    uploadOrSendFailed = true;
                  } else if (isSend && st >= 200 && st < 300) {
                    imSendSucceeded = true;
                  }
                }
              }
              return {
                failureMarkerHit: failureMarkerHit,
                modalVisible: modalVisible,
                hasSendingIndicator: hasSendingIndicator,
                totalBoxes: boxes.length,
                pendingImgCount: pendingImgCount,
                signatures: signatures,
                remoteSignatures: remoteSignatures,
                localPreviewSignatures: localPreviewSignatures,
                settledLocalSignatures: settledLocalSignatures,
                tempPreviewSignatures: tempPreviewSignatures,
                imSendSucceeded: imSendSucceeded,
                uploadOrSendInFlight: uploadOrSendInFlight,
                uploadOrSendFailed: uploadOrSendFailed,
                sanitizedImgSummary: sanitizedImgSummary
              };
            })();
        """.trimIndent()
    }

    fun buildOpenStickerPanelJs(bundle: SelectorBundle): String {
        val buttonsLit = jsStringArrayLiteral(bundle.stickerButtons)
        return """
            (function () {
              $DOM_HELPERS_JS
              var hit = __flameFirstVisible($buttonsLit);
              if (!hit || !hit.el) return { clicked: false };
              __flameDispatchClick(hit.el);
              return { clicked: true, selector: hit.selector };
            })();
        """.trimIndent()
    }

    fun buildCheckStickerPanelReadyJs(bundle: SelectorBundle): String {
        val panelsLit = jsStringArrayLiteral(bundle.stickerPanels)
        val itemLit = jsStringLiteral(bundle.stickerItem)
        return """
            (function () {
              $DOM_HELPERS_JS
              var hit = __flameFirstVisible($panelsLit);
              if (!hit || !hit.el) {
                return { panelFound: false, itemCount: 0 };
              }
              var count = hit.el.querySelectorAll($itemLit).length;
              return { panelFound: true, selector: hit.selector, itemCount: count };
            })();
        """.trimIndent()
    }

    fun buildClickStickerJs(
        bundle: SelectorBundle,
        stickerName: String,
        stickerLabel: String? = null,
        stickerCategory: String? = null,
        fallbackIndex: Int? = null,
    ): String {
        val targetDisplay = stickerLabel?.takeIf { it.isNotBlank() } ?: stickerName
        val panelsLit = jsStringArrayLiteral(bundle.stickerPanels)
        val itemLit = jsStringLiteral(bundle.stickerItem)
        val itemNameLit = jsStringLiteral(bundle.stickerItemName)
        val targetLit = jsStringLiteral(targetDisplay)
        val categoryLit = if (!stickerCategory.isNullOrBlank()) jsStringLiteral(stickerCategory.trim()) else "null"
        val fallbackLit = fallbackIndex?.takeIf { it >= 0 }?.toString() ?: "null"
        return """
            (function () {
              $DOM_HELPERS_JS
              var panelHit = __flameFirstVisible($panelsLit);
              if (!panelHit || !panelHit.el) {
                return { clicked: false, reason: 'panel_not_found' };
              }
              var panel = panelHit.el;
              var category = $categoryLit;
              if (category) {
                var catCandidates = panel.querySelectorAll('button, [role="tab"], div, span');
                for (var c = 0; c < catCandidates.length; c++) {
                  var node = catCandidates[c];
                  if (String(node.textContent || '').trim() === category && __flameIsVisible(node)) {
                    __flameDispatchClick(node);
                    break;
                  }
                }
              }

              var targetName = $targetLit;
              var items = panel.querySelectorAll($itemLit);
              for (var i = 0; i < items.length; i++) {
                var item = items[i];
                if (!__flameIsVisible(item)) continue;
                var desc = item.querySelector($itemNameLit);
                var descText = desc ? String(desc.innerText || desc.textContent || '').trim() : '';
                if (descText === targetName || descText === '[' + targetName + ']') {
                  var clickTarget = __flameResolveClickTarget(item, true);
                  __flameDispatchClick(clickTarget);
                  return {
                    clicked: true,
                    method: 'item_name',
                    targetTag: String((clickTarget && clickTarget.tagName) || '')
                  };
                }
              }

              var attrNodes = panel.querySelectorAll('[aria-label], [title], [alt]');
              for (var a = 0; a < attrNodes.length; a++) {
                var an = attrNodes[a];
                if (!__flameIsVisible(an)) continue;
                var aria = String(an.getAttribute('aria-label') || '').trim();
                var title = String(an.getAttribute('title') || '').trim();
                var alt = String(an.getAttribute('alt') || '').trim();
                if (aria === targetName || title === targetName || alt === targetName) {
                  var attrTarget = __flameResolveClickTarget(an, true);
                  __flameDispatchClick(attrTarget);
                  return {
                    clicked: true,
                    method: 'attr_match',
                    targetTag: String((attrTarget && attrTarget.tagName) || '')
                  };
                }
              }

              var fallbackIdx = $fallbackLit;
              if (fallbackIdx !== null) {
                var slots = panel.querySelectorAll('[role="button"], img, [aria-label], [title]');
                if (fallbackIdx >= 0 && fallbackIdx < slots.length) {
                  var slotTarget = __flameResolveClickTarget(slots[fallbackIdx], true);
                  __flameDispatchClick(slotTarget);
                  return {
                    clicked: true,
                    method: 'fallback_index',
                    targetTag: String((slotTarget && slotTarget.tagName) || '')
                  };
                }
              }

              return { clicked: false, reason: 'sticker_not_found' };
            })();
        """.trimIndent()
    }

    fun buildResetInputFocusJs(bundle: SelectorBundle): String {
        val editorSelectorsLit = jsStringArrayLiteral(editorSelectors(bundle))
        return """
            (function () {
              $DOM_HELPERS_JS
              var escOpts = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
              try {
                (document.activeElement || document.body || document).dispatchEvent(new KeyboardEvent('keydown', escOpts));
                document.dispatchEvent(new KeyboardEvent('keydown', escOpts));
                document.dispatchEvent(new KeyboardEvent('keyup', escOpts));
              } catch (_) {}
              var editorHit = __flameFirstVisible($editorSelectorsLit);
              if (editorHit && editorHit.el) {
                try { editorHit.el.focus(); } catch (_) {}
                __flameDispatchClick(editorHit.el);
              }
              return { reset: true };
            })();
        """.trimIndent()
    }

    fun buildOpenSampleConversationForProbeJs(bundle: SelectorBundle): String {
        val convItemLit = jsStringLiteral(bundle.conversationItem)
        val convTitleLit = jsStringLiteral(bundle.conversationTitle)
        val streakLit = jsStringLiteral(bundle.conversationStreak)
        return """
            (function () {
              $DOM_HELPERS_JS
              var rows = Array.from(document.querySelectorAll($convItemLit));
              if (!rows.length) return { clicked: false, totalRows: 0 };
              var sparkIdx = rows.findIndex(function (r) {
                return !!r.querySelector($streakLit);
              });
              var idx = sparkIdx >= 0 ? sparkIdx : 0;
              var row = rows[idx];
              var target = row.querySelector($convTitleLit) || row;
              __flameDispatchClick(target);
              return { clicked: true, hadSparkRow: sparkIdx >= 0, totalRows: rows.length };
            })();
        """.trimIndent()
    }

    /**
     * 只读网页结构体检（绝不写入字符、绝不点击发送按钮或表情项）。
     */
    fun buildStructureProbeJs(bundle: SelectorBundle): String {
        val listLit = jsStringLiteral(bundle.conversationList)
        val itemLit = jsStringLiteral(bundle.conversationItem)
        val searchInputsLit = jsStringArrayLiteral(searchInputSelectors(bundle))
        val rightTitleLit = jsStringLiteral(bundle.rightTitle)
        val editorSelectorsLit = jsStringArrayLiteral(editorSelectors(bundle))
        val sendButtonsLit = jsStringArrayLiteral(bundle.sendButtons)
        val stickerButtonsLit = jsStringArrayLiteral(bundle.stickerButtons)
        val stickerPanelsLit = jsStringArrayLiteral(bundle.stickerPanels)
        val stickerItemLit = jsStringLiteral(bundle.stickerItem)
        val stickerItemNameLit = jsStringLiteral(bundle.stickerItemName)
        return """
            (function () {
              $DOM_HELPERS_JS
              function probeFirst(selectors) {
                for (var i = 0; i < selectors.length; i++) {
                  try {
                    var els = document.querySelectorAll(selectors[i]);
                    if (els && els.length > 0) {
                      return { hit: true, count: els.length };
                    }
                  } catch (_) {}
                }
                return { hit: false, count: 0 };
              }
              var listRes = probeFirst([$listLit]);
              var itemRes = probeFirst([$itemLit]);
              var searchRes = probeFirst($searchInputsLit);
              var rightTitleRes = probeFirst([$rightTitleLit]);
              var editorRes = probeFirst($editorSelectorsLit);
              var sendRes = probeFirst($sendButtonsLit);
              var stickerBtnRes = probeFirst($stickerButtonsLit);
              var panelHit = __flameFirstVisible($stickerPanelsLit);
              var panelCount = panelHit ? 1 : 0;
              var stickerItemCount = panelHit ? panelHit.el.querySelectorAll($stickerItemLit).length : 0;
              var heartFound = false;
              if (panelHit && stickerItemCount > 0) {
                var items = panelHit.el.querySelectorAll($stickerItemLit);
                for (var i = 0; i < Math.min(items.length, 100); i++) {
                  var desc = items[i].querySelector($stickerItemNameLit);
                  if (desc && String(desc.innerText || desc.textContent || '').trim() === '比心') {
                    heartFound = true;
                    break;
                  }
                }
              }
              return {
                conversationList: listRes,
                conversationItem: itemRes,
                searchInputs: searchRes,
                rightTitle: rightTitleRes,
                chatEditorSelectors: editorRes,
                sendButtons: sendRes,
                stickerButtons: stickerBtnRes,
                stickerPanels: { hit: !!panelHit, count: panelCount },
                stickerItem: { hit: stickerItemCount > 0, count: stickerItemCount, heartFound: heartFound }
              };
            })();
        """.trimIndent()
    }

    fun buildProbeImageUploadDomJs(bundle: SelectorBundle): String {
        val triggerLit = jsStringLiteral(bundle.imageUploadTrigger)
        val imageInputsLit = jsStringArrayLiteral(bundle.imageInputs)
        return """
            (function () {
              $DOM_HELPERS_JS
              var triggerSel = $triggerLit;
              var allTriggers = [];
              try { allTriggers = Array.from(document.querySelectorAll(triggerSel)); } catch (_) {}
              var triggerHit = __flameFirstVisible([triggerSel]);
              var cx = null;
              var cy = null;
              var rw = null;
              var rh = null;
              if (triggerHit && triggerHit.el) {
                try { triggerHit.el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (_) {}
                try {
                  var r = triggerHit.el.getBoundingClientRect();
                  if (r && r.width > 0 && r.height > 0) {
                    cx = r.left + r.width / 2;
                    cy = r.top + r.height / 2;
                    rw = r.width;
                    rh = r.height;
                  }
                } catch (_) {}
              }
              var inputSels = $imageInputsLit;
              var seen = [];
              for (var s = 0; s < inputSels.length; s++) {
                try {
                  var nodes = document.querySelectorAll(inputSels[s]);
                  for (var j = 0; j < nodes.length; j++) {
                    if (seen.indexOf(nodes[j]) === -1) seen.push(nodes[j]);
                  }
                } catch (_) {}
              }
              return {
                triggerSelector: triggerSel,
                triggerFound: !!(triggerHit && triggerHit.el && cx !== null && cy !== null),
                triggerCount: allTriggers.length,
                centerX: cx,
                centerY: cy,
                rectWidth: rw,
                rectHeight: rh,
                viewportWidth: window.innerWidth || 0,
                viewportHeight: window.innerHeight || 0,
                devicePixelRatio: window.devicePixelRatio || 1,
                fileInputCount: seen.length,
                hasUploadEntry: (!!(triggerHit && triggerHit.el) || allTriggers.length > 0 || seen.length > 0)
              };
            })();
        """.trimIndent()
    }

    fun buildClickImageUploadTriggerJs(bundle: SelectorBundle): String {
        val triggerLit = jsStringLiteral(bundle.imageUploadTrigger)
        return """
            (function () {
              $DOM_HELPERS_JS
              var triggerSel = $triggerLit;
              var hit = __flameFirstVisible([triggerSel]);
              var el = (hit && hit.el) ? hit.el : null;
              if (!el) {
                try { el = document.querySelector(triggerSel); } catch (_) {}
              }
              if (!el) return { clicked: false, reason: 'upload_trigger_not_found' };
              try { el.scrollIntoView({ block: 'center', inline: 'nearest' }); } catch (_) {}
              var invoked = false;
              try {
                if (typeof el.click === 'function') {
                  el.click();
                  invoked = true;
                }
              } catch (_) {}
              if (!invoked) {
                __flameDispatchClick(el);
              }
              return { clicked: true, selector: triggerSel };
            })();
        """.trimIndent()
    }

    fun buildClickImageFileInputJs(bundle: SelectorBundle): String {
        val imageInputsLit = jsStringArrayLiteral(bundle.imageInputs)
        return """
            (function () {
              var selectors = $imageInputsLit;
              var target = null;
              var matchedSelector = null;
              for (var i = 0; i < selectors.length; i++) {
                try {
                  var list = document.querySelectorAll(selectors[i]);
                  if (list && list.length > 0) {
                    for (var j = 0; j < list.length; j++) {
                      if (!list[j].disabled) {
                        target = list[j];
                        matchedSelector = selectors[i];
                        break;
                      }
                    }
                    if (!target) {
                      target = list[0];
                      matchedSelector = selectors[i];
                    }
                    break;
                  }
                } catch (_) {}
              }
              if (!target) return { clicked: false, reason: 'file_input_not_found' };
              try {
                if (typeof target.click === 'function') {
                  target.click();
                  return { clicked: true, selector: matchedSelector };
                }
              } catch (_) {}
              return { clicked: false, reason: 'file_input_click_failed' };
            })();
        """.trimIndent()
    }

    fun buildInjectImageDataTransferJs(
        bundle: SelectorBundle,
        imageBase64: String,
        fileName: String,
        mimeType: String,
    ): String {
        val imageInputsLit = jsStringArrayLiteral(bundle.imageInputs)
        val b64Lit = jsStringLiteral(imageBase64)
        val fileNameLit = jsStringLiteral(fileName)
        val mimeLit = jsStringLiteral(mimeType)
        return """
            (function () {
              var selectors = $imageInputsLit;
              var input = null;
              var matchedSelector = null;
              for (var s = 0; s < selectors.length; s++) {
                try {
                  var els = document.querySelectorAll(selectors[s]);
                  if (els && els.length > 0) {
                    for (var j = 0; j < els.length; j++) {
                      if (!els[j].disabled) {
                        input = els[j];
                        matchedSelector = selectors[s];
                        break;
                      }
                    }
                    if (!input) {
                      input = els[0];
                      matchedSelector = selectors[s];
                    }
                    break;
                  }
                } catch (_) {}
              }
              if (!input) {
                return { ok: false, injected: false, reason: 'file_input_not_found' };
              }
              var cleanB64 = String($b64Lit || '').replace(/\s+/g, '');
              if (!cleanB64) {
                return { ok: false, injected: false, reason: 'empty_base64' };
              }
              try {
                var binary = atob(cleanB64);
                var len = binary.length;
                var bytes = new Uint8Array(len);
                for (var i = 0; i < len; i++) {
                  bytes[i] = binary.charCodeAt(i);
                }
                var file = new File([bytes], $fileNameLit, { type: $mimeLit || 'image/jpeg' });
                var dt = new DataTransfer();
                dt.items.add(file);
                try {
                  input.files = dt.files;
                } catch (_) {
                  Object.defineProperty(input, 'files', { value: dt.files, configurable: true });
                }
                try {
                  var tracker = input._valueTracker;
                  if (tracker && typeof tracker.setValue === 'function') {
                    var curVal = '';
                    try { curVal = String(input.value || ''); } catch (_) {}
                    tracker.setValue(curVal ? '' : '__flame_reset__');
                  }
                } catch (_) {}
                input.dispatchEvent(new Event('input', { bubbles: true, cancelable: true }));
                input.dispatchEvent(new Event('change', { bubbles: true, cancelable: true }));
                return {
                  ok: true,
                  injected: true,
                  selector: matchedSelector,
                  filesLength: input.files ? input.files.length : 0
                };
              } catch (err) {
                return {
                  ok: false,
                  injected: false,
                  reason: String((err && err.message) || err || 'datatransfer_error')
                };
              }
            })();
        """.trimIndent()
    }

    fun buildInspectImageConfirmModalJs(bundle: SelectorBundle): String {
        val sendSelectorsLit = jsStringArrayLiteral(bundle.imageDialogSend)
        return """
            (function () {
              $DOM_HELPERS_JS
              $IMAGE_MODAL_DISCOVERY_HELPERS_JS
              $IMAGE_MODAL_SEND_HELPERS_JS
              var modalState = __flameFindTopVisibleModal();
              var topModal = modalState.topModal;
              if (!topModal) {
                return {
                  modalVisible: false,
                  modalCount: modalState.modalCount,
                  visibleModalCount: 0,
                  hasImgPreview: false,
                  imgCount: 0,
                  sendButtonFound: false,
                  sendButtonEnabled: false,
                  sendButtonText: null,
                  sendMatchMethod: null
                };
              }
              var imgs = topModal.querySelectorAll('img');
              var imgCount = 0;
              for (var i = 0; i < imgs.length; i++) {
                var img = imgs[i];
                var st = window.getComputedStyle ? window.getComputedStyle(img) : null;
                if (st && (st.display === 'none' || st.visibility === 'hidden' || st.opacity === '0')) continue;
                var src = String(img.getAttribute('src') || img.currentSrc || img.src || '').trim();
                var rect = img.getBoundingClientRect ? img.getBoundingClientRect() : null;
                if (src.length > 0 || (rect && rect.width > 0 && rect.height > 0)) {
                  imgCount++;
                }
              }
              var sendHit = __flameFindImageModalSendButton(topModal, $sendSelectorsLit);
              return {
                modalVisible: true,
                modalCount: modalState.modalCount,
                visibleModalCount: modalState.visibleModalCount,
                hasImgPreview: imgCount > 0,
                imgCount: imgCount,
                sendButtonFound: !!sendHit,
                sendButtonEnabled: !!(sendHit && sendHit.enabled),
                sendButtonText: sendHit ? sendHit.text : null,
                sendMatchMethod: sendHit ? sendHit.method : null
              };
            })();
        """.trimIndent()
    }

    fun buildClickImageModalSendJs(bundle: SelectorBundle): String {
        val sendSelectorsLit = jsStringArrayLiteral(bundle.imageDialogSend)
        return """
            (function () {
              $DOM_HELPERS_JS
              $IMAGE_MODAL_DISCOVERY_HELPERS_JS
              $IMAGE_MODAL_SEND_HELPERS_JS
              var modalState = __flameFindTopVisibleModal();
              var topModal = modalState.topModal;
              if (!topModal) {
                return { clicked: false, reason: 'modal_not_visible' };
              }
              var sendHit = __flameFindImageModalSendButton(topModal, $sendSelectorsLit);
              if (!sendHit || !sendHit.el) {
                return { clicked: false, reason: 'send_button_not_found' };
              }
              if (!sendHit.enabled) {
                return { clicked: false, reason: 'send_button_disabled', buttonText: sendHit.text };
              }
              __flameDispatchClick(sendHit.el);
              return {
                clicked: true,
                method: sendHit.method,
                selector: sendHit.selector,
                buttonText: sendHit.text
              };
            })();
        """.trimIndent()
    }

    fun buildDismissImageModalJs(): String = """
        (function () {
          $DOM_HELPERS_JS
          $IMAGE_MODAL_DISCOVERY_HELPERS_JS
          var modalState = __flameFindTopVisibleModal();
          var topModal = modalState.topModal;
          if (!topModal) {
            return { dismissed: false, modalWasVisible: false };
          }
          var buttons = [];
          try { buttons = Array.from(topModal.querySelectorAll('button, [role="button"]')); } catch (_) {}
          for (var i = 0; i < buttons.length; i++) {
            var btn = buttons[i];
            if (!__flameIsVisible(btn)) continue;
            if (__flameNormButtonText(btn) === '取消') {
              __flameDispatchClick(btn);
              return { dismissed: true, modalWasVisible: true, method: 'cancel_button' };
            }
          }
          var closeHit = topModal.querySelector('.semi-modal-close, [aria-label="close"], [aria-label="关闭"], button[aria-label*="关闭"]');
          if (closeHit && __flameIsVisible(closeHit)) {
            __flameDispatchClick(closeHit);
            return { dismissed: true, modalWasVisible: true, method: 'close_icon' };
          }
          var escOpts = { key: 'Escape', code: 'Escape', keyCode: 27, which: 27, bubbles: true, cancelable: true };
          try {
            topModal.dispatchEvent(new KeyboardEvent('keydown', escOpts));
            document.dispatchEvent(new KeyboardEvent('keydown', escOpts));
            document.dispatchEvent(new KeyboardEvent('keyup', escOpts));
          } catch (_) {}
          return { dismissed: true, modalWasVisible: true, method: 'escape_key' };
        })();
    """.trimIndent()
}
