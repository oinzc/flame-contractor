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

    fun buildIdentityCaptureDocumentStartJs(bridgeName: String = DEFAULT_BRIDGE_NAME): String {
        val bridgeExpr = jsStringLiteral(bridgeName)
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
                  '/v1/message/get_message_by_init'
                ];

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
                  if (!buffer || !buffer.byteLength) return '';
                  var bytes = new Uint8Array(buffer);
                  var chunkSize = 0x8000;
                  var chunks = [];
                  for (var i = 0; i < bytes.length; i += chunkSize) {
                    var sub = bytes.subarray(i, Math.min(i + chunkSize, bytes.length));
                    chunks.push(String.fromCharCode.apply(null, sub));
                  }
                  return btoa(chunks.join(''));
                }

                function emitProtoBuffer(buffer) {
                  try {
                    var bridge = getBridge();
                    if (!bridge || typeof bridge.onInitProtoBase64 !== 'function') return;
                    var b64 = arrayBufferToBase64Chunked(buffer);
                    if (b64) {
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
                    var netEntry = recordNetStart('fetch', method, rawUrl);
                    var promise = origFetch.apply(this, arguments);
                    if (netEntry) {
                      try {
                        promise.then(function (resp) {
                          try { netEntry.status = (resp && typeof resp.status === 'number') ? resp.status : -2; } catch (_) {}
                        }).catch(function () {
                          try { netEntry.status = -1; } catch (_) {}
                        });
                      } catch (_) {}
                    }
                    if (idMatch || protoMatch) {
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
                      if ((idMatch || protoMatch) && !this.__flameBridgeHooked) {
                        this.__flameBridgeHooked = true;
                        this.addEventListener('load', function () {
                          try {
                            if (idMatch) {
                              if (this.responseType === 'json' && this.response) {
                                emitIdentityPayload(this.response);
                              } else if (typeof this.responseText === 'string' && this.responseText) {
                                emitIdentityPayload(JSON.parse(this.responseText));
                              }
                            } else if (protoMatch) {
                              if (this.responseType === 'arraybuffer' && this.response) {
                                emitProtoBuffer(this.response);
                              } else if (this.responseType === 'blob' && this.response && typeof this.response.arrayBuffer === 'function') {
                                this.response.arrayBuffer().then(function (buf) {
                                  emitProtoBuffer(buf);
                                }).catch(function () {});
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
     * 只读读取浏览器 IndexedDB 中缓存的用户身份记录（每个 store 独立闭包与 `ev.target.result`，
     * 彻底避免在循环内共享句柄导致 `InvalidStateError`）。
     */
    fun buildReadIndexedDbIdentitiesJs(
        bridgeName: String = DEFAULT_BRIDGE_NAME,
        callId: String? = null,
    ): String {
        val bridgeLiteral = jsStringLiteral(bridgeName)
        val callIdLiteral = if (callId != null) jsStringLiteral(callId) else "null"
        return """
            (function () {
              var BRIDGE_NAME = $bridgeLiteral;
              var CALL_ID = $callIdLiteral;

              function notifyResult(records) {
                var json = '[]';
                try { json = JSON.stringify(records || []); } catch (_) {}
                try {
                  var bridge = window[BRIDGE_NAME];
                  if (bridge && typeof bridge.onIdentityRecords === 'function' && records && records.length > 0) {
                    bridge.onIdentityRecords(json);
                  }
                  if (CALL_ID && bridge && typeof bridge.onAsyncResult === 'function') {
                    bridge.onAsyncResult(CALL_ID, json);
                  }
                } catch (_) {}
                return records || [];
              }

              if (typeof indexedDB === 'undefined' || typeof indexedDB.databases !== 'function') {
                return Promise.resolve(notifyResult([]));
              }

              function pickStr(v, keys) {
                for (let i = 0; i < keys.length; i++) {
                  const val = v[keys[i]];
                  if (typeof val === 'string' && val.trim()) return val.trim();
                  if (typeof val === 'number' && isFinite(val)) return String(val);
                }
                return null;
              }

              function pickRecord(item) {
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

              function readOneDb(dbName) {
                return new Promise(function (resolve) {
                  let settled = false;
                  const finish = function (rows) {
                    if (!settled) {
                      settled = true;
                      resolve(rows || []);
                    }
                  };
                  const timer = setTimeout(function () { finish([]); }, 1500);
                  try {
                    const openReq = indexedDB.open(dbName);
                    openReq.onerror = function () { clearTimeout(timer); finish([]); };
                    openReq.onblocked = function () { clearTimeout(timer); finish([]); };
                    openReq.onsuccess = function (openEv) {
                      const db = (openEv && openEv.target && openEv.target.result) ? openEv.target.result : openReq.result;
                      const stores = Array.from((db && db.objectStoreNames) ? db.objectStoreNames : []);
                      if (!stores.length) {
                        clearTimeout(timer);
                        try { db.close(); } catch (_) {}
                        finish([]);
                        return;
                      }
                      const out = [];
                      let pending = stores.length;
                      const finishStore = function () {
                        pending -= 1;
                        if (pending <= 0) {
                          clearTimeout(timer);
                          try { db.close(); } catch (_) {}
                          finish(out);
                        }
                      };
                      stores.forEach(function (storeName) {
                        try {
                          const tx = db.transaction(storeName, 'readonly');
                          const store = tx.objectStore(storeName);
                          const storeReq = store.getAll();
                          storeReq.onsuccess = function (ev) {
                            try {
                              const rows = (ev && ev.target && ev.target.result) ? ev.target.result : [];
                              rows.forEach(function (row) {
                                const picked = pickRecord(row);
                                if (picked) out.push(picked);
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
                    finish([]);
                  }
                });
              }

              return Promise.race([
                indexedDB.databases().then(function (dbs) {
                  const userDbs = (dbs || [])
                    .map(function (d) { return d && d.name; })
                    .filter(function (name) {
                      return typeof name === 'string' && name.toLowerCase().indexOf('user') !== -1;
                    });
                  return Promise.all(userDbs.map(readOneDb)).then(function (chunks) {
                    const merged = [];
                    chunks.forEach(function (chunk) {
                      chunk.forEach(function (item) {
                        merged.push(item);
                      });
                    });
                    return notifyResult(merged);
                  });
                }).catch(function () {
                  return notifyResult([]);
                }),
                new Promise(function (resolve) {
                  setTimeout(function () { resolve(notifyResult([])); }, 2500);
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
              var href = String(window.location && window.location.href ? window.location.href : '');
              var host = String(window.location && window.location.hostname ? window.location.hostname : '').toLowerCase();
              var pathname = String(window.location && window.location.pathname ? window.location.pathname : '');
              var isBlankPage = !href || href === 'about:blank';
              var isErrorPage = href.indexOf('chrome-error://') === 0;
              var bodyText = '';
              try { bodyText = String(document.body ? (document.body.innerText || '') : ''); } catch (_) {}

              var loggedIn = false;
              var conversationCount = 0;
              try {
                loggedIn = !!document.querySelector($convListLit);
                conversationCount = document.querySelectorAll($convItemLit).length;
              } catch (_) {}

              var verificationRequired = bodyText.indexOf('身份验证') !== -1 ||
                bodyText.indexOf('安全验证') !== -1 ||
                bodyText.indexOf('请完成下列验证') !== -1 ||
                bodyText.indexOf('向右滑动') !== -1;

              var qrFound = false;
              try {
                var imgs = document.querySelectorAll('img');
                for (var i = 0; i < imgs.length; i++) {
                  var src = imgs[i].src || '';
                  if (src.indexOf('data:image') !== 0) continue;
                  var r = imgs[i].getBoundingClientRect();
                  if (r.width >= 120 && r.width <= 260 && r.height >= 120 && r.height <= 260) {
                    qrFound = true;
                    break;
                  }
                }
              } catch (_) {}

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
                readyState: String(document.readyState || ''),
                loggedIn: loggedIn,
                verificationRequired: verificationRequired,
                hasQrOrLoginSignal: hasQrOrLoginSignal,
                hasLocalStorageUserInfo: hasLocalStorageUserInfo,
                conversationCount: conversationCount,
                selfUid: selfUid,
                douyinNickname: douyinNickname
              };
            })();
        """.trimIndent()
    }

    fun buildScrollAndCollectChatsJs(bundle: SelectorBundle): String {
        val listLit = jsStringLiteral(bundle.conversationList)
        val itemLit = jsStringLiteral(bundle.conversationItem)
        val titleLit = jsStringLiteral(bundle.conversationTitle)
        val streakLit = jsStringLiteral(bundle.conversationStreak)
        val streakDaysLit = jsStringLiteral(bundle.conversationStreakDays)
        return """
            (function () {
              $DOM_HELPERS_JS
              var listEl = document.querySelector($listLit);
              if (!listEl) {
                return { listFound: false, atBottom: true, items: [] };
              }
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
                var streakEl = row.querySelector($streakLit);
                if (streakEl) {
                  var daysEl = streakEl.querySelector($streakDaysLit);
                  var daysText = daysEl ? String(daysEl.innerText || daysEl.textContent || '').trim() : '';
                  if (/^\d+$/.test(daysText)) {
                    streakDays = parseInt(daysText, 10);
                  } else if (daysText) {
                    streakLabel = daysText;
                  }
                  var icon = streakEl.querySelector("img[src*='flame_icon']");
                  var src = icon ? String(icon.getAttribute('src') || '') : '';
                  if (src) {
                    if (src.indexOf('/couple/') !== -1) streakKind = 'couple';
                    else if (src.indexOf('/family/') !== -1) streakKind = 'family';
                    else if (src.indexOf('/normal/') !== -1) streakKind = 'normal';
                  }
                }

                var hasSparkInPreview = false;
                try {
                  var lines = String(row.innerText || '').split(/\r?\n/).map(function (s) { return s.trim(); }).filter(Boolean);
                  var markDays = streakDays !== null ? String(streakDays) : '';
                  var markLabel = streakLabel || '';
                  var rest = [];
                  for (var l = 1; l < lines.length; l++) {
                    if (lines[l] !== markDays && lines[l] !== markLabel) {
                      rest.push(lines[l]);
                    }
                  }
                  var previewText = rest.length > 1 ? rest.slice(1).join(' ') : '';
                  if (previewText.indexOf('火花') !== -1) {
                    hasSparkInPreview = true;
                  }
                } catch (_) {}

                items.push({
                  title: title,
                  streakDays: streakDays,
                  streakKind: streakKind,
                  streakLabel: streakLabel,
                  hasSparkInPreview: hasSparkInPreview
                });
              }

              var atBottom = (listEl.scrollTop + listEl.clientHeight >= listEl.scrollHeight - 5);
              return {
                listFound: true,
                atBottom: atBottom,
                scrollTop: listEl.scrollTop || 0,
                scrollHeight: listEl.scrollHeight || 0,
                clientHeight: listEl.clientHeight || 0,
                items: items
              };
            })();
        """.trimIndent()
    }

    fun buildScrollConversationListStepJs(bundle: SelectorBundle, deltaPx: Int = 900): String {
        val listLit = jsStringLiteral(bundle.conversationList)
        return """
            (function () {
              var listEl = document.querySelector($listLit);
              if (!listEl) return { listFound: false, atBottom: true };
              var delta = $deltaPx;
              if (delta === 0) {
                listEl.scrollTop = 0;
              } else {
                listEl.scrollTop = (listEl.scrollTop || 0) + delta;
              }
              return {
                listFound: true,
                atBottom: (listEl.scrollTop + listEl.clientHeight >= listEl.scrollHeight - 5),
                scrollTop: listEl.scrollTop || 0
              };
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
              try { input.focus(); } catch (_) {}
              try {
                var desc = Object.getOwnPropertyDescriptor(window.HTMLInputElement.prototype, 'value');
                if (desc && typeof desc.set === 'function') {
                  desc.set.call(input, $queryLit);
                } else {
                  input.value = $queryLit;
                }
              } catch (_) {
                input.value = $queryLit;
              }
              try { input.dispatchEvent(new Event('input', { bubbles: true })); } catch (_) {}
              try { input.dispatchEvent(new Event('change', { bubbles: true })); } catch (_) {}
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
              var targetName = $targetLit;
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

              var matches = [];
              var searchRows = [];
              try { searchRows = Array.from(document.querySelectorAll($searchResultItemLit)); } catch (_) {}
              for (var s = 0; s < searchRows.length; s++) {
                var sr = searchRows[s];
                if (!__flameIsVisible(sr)) continue;
                if (titleOf(sr) === targetName) {
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
                  if (titleOf(cr) === targetName) {
                    matches.push(cr);
                  }
                }
              }

              if (!matches.length) {
                var divs = document.querySelectorAll('div');
                for (var d = 0; d < divs.length; d++) {
                  var el = divs[d];
                  var txt = String(el.textContent || '').trim();
                  if (txt !== targetName && txt.indexOf(targetName + '\n') !== 0) continue;
                  if (!__flameIsVisible(el)) continue;
                  var row = el.closest('[class*="Search"], [class*="search"]') || el;
                  var sendMsgBtn = Array.from(row.querySelectorAll('*')).find(function (n) {
                    return String(n.textContent || '').trim() === '发消息';
                  });
                  matches.push(sendMsgBtn || row);
                  break;
                }
              }

              if (wantIdx >= 0 && wantIdx < matches.length) {
                __flameDispatchClick(matches[wantIdx]);
                return { clicked: true, totalMatches: matches.length, candidateIndex: wantIdx };
              }
              return { clicked: false, totalMatches: matches.length, candidateIndex: wantIdx };
            })();
        """.trimIndent()
    }

    fun buildClickListItemByTitleJs(
        bundle: SelectorBundle,
        targetName: String,
        candidateIndex: Int = 0,
    ): String {
        val convItemLit = jsStringLiteral(bundle.conversationItem)
        val convTitleLit = jsStringLiteral(bundle.conversationTitle)
        val targetLit = jsStringLiteral(targetName)
        return """
            (function () {
              $DOM_HELPERS_JS
              var targetName = $targetLit;
              var wantIdx = $candidateIndex;
              var rows = Array.from(document.querySelectorAll($convItemLit));
              var matches = [];
              for (var i = 0; i < rows.length; i++) {
                var row = rows[i];
                if (!__flameIsVisible(row)) continue;
                var titleEl = row.querySelector($convTitleLit);
                var t = titleEl
                  ? __flameFirstNonEmptyLine(titleEl.innerText || titleEl.textContent || '')
                  : __flameFirstNonEmptyLine(row.innerText || row.textContent || '');
                if (t === targetName) {
                  matches.push(row);
                }
              }
              if (wantIdx >= 0 && wantIdx < matches.length) {
                var targetRow = matches[wantIdx];
                var clickNode = targetRow.querySelector($convTitleLit) || targetRow;
                __flameDispatchClick(clickNode);
                return { clicked: true, totalMatches: matches.length };
              }
              return { clicked: false, totalMatches: matches.length };
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
