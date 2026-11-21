package io.github.oinzc.flamecontractor.execution.webview

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

data class WebViewConfigResult(
    val activeUa: String,
    val defaultUa: String,
    val documentStartSupported: Boolean,
)

object DesktopWebViewFactory {

    const val CHAT_URL = "https://www.douyin.com/chat"
    private const val FALLBACK_CHROME_VERSION = "135.0.0.0"
    private const val TAG = "FlameM1"

    private val CHROME_VERSION_REGEX = Regex("""Chrome/([\d.]+)""")

    fun buildDesktopUserAgent(defaultUa: String): String {
        val chromeVer = CHROME_VERSION_REGEX
            .find(defaultUa)
            ?.groupValues
            ?.getOrNull(1)
            ?.takeIf { it.isNotBlank() }
            ?: FALLBACK_CHROME_VERSION
        return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/$chromeVer Safari/537.36"
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun configureDesktopWebView(
        webView: WebView,
        blockDeepLinks: Boolean = true,
        onPageFinished: ((String) -> Unit)? = null,
        onMainFrameError: ((Int, String) -> Unit)? = null,
    ): WebViewConfigResult {
        val defaultUa = WebSettings.getDefaultUserAgent(webView.context)
        val activeUa = buildDesktopUserAgent(defaultUa)

        webView.settings.apply {
            userAgentString = activeUa
            javaScriptEnabled = true
            domStorageEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            builtInZoomControls = true
            displayZoomControls = false
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            mediaPlaybackRequiresUserGesture = true
        }

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        val documentStartSupported =
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
        if (documentStartSupported) {
            WebViewCompat.addDocumentStartJavaScript(
                webView,
                DOCUMENT_START_SCRIPT,
                setOf("https://www.douyin.com", "https://*.douyin.com"),
            )
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?,
            ): Boolean {
                if (!blockDeepLinks) return false
                val scheme = request?.url?.scheme?.lowercase().orEmpty()
                if (scheme != "http" && scheme != "https") {
                    Log.w(TAG, "BLOCKED_DEEP_LINK scheme=$scheme")
                    return true
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                CookieManager.getInstance().flush()
                onPageFinished?.invoke(safeOriginPath(url))
            }

            override fun onReceivedError(
                view: WebView?,
                request: WebResourceRequest?,
                error: WebResourceError?,
            ) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame == true) {
                    onMainFrameError?.invoke(
                        error?.errorCode ?: -1,
                        error?.description?.toString().orEmpty().take(80),
                    )
                }
            }
        }

        return WebViewConfigResult(
            activeUa = activeUa,
            defaultUa = defaultUa,
            documentStartSupported = documentStartSupported,
        )
    }

    fun createOffscreenWebView(
        context: Context,
        widthPx: Int = 1440,
        heightPx: Int = 900,
        onPageFinished: ((String) -> Unit)? = null,
        onMainFrameError: ((Int, String) -> Unit)? = null,
    ): Pair<WebView, WebViewConfigResult> {
        val webView = WebView(context).apply {
            layoutParams = ViewGroup.LayoutParams(widthPx, heightPx)
        }
        val widthSpec = View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
        webView.measure(widthSpec, heightSpec)
        webView.layout(0, 0, widthPx, heightPx)

        val config = configureDesktopWebView(
            webView = webView,
            blockDeepLinks = true,
            onPageFinished = onPageFinished,
            onMainFrameError = onMainFrameError,
        )
        return webView to config
    }

    fun safeOriginPath(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        return try {
            val uri = android.net.Uri.parse(rawUrl)
            val scheme = uri.scheme.orEmpty()
            val host = uri.host.orEmpty()
            val path = uri.path.orEmpty()
            if (scheme.isNotEmpty() && host.isNotEmpty()) "$scheme://$host$path" else path
        } catch (_: Throwable) {
            ""
        }
    }

    val DOCUMENT_START_SCRIPT: String = """
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

            if (window.__flameNetProbeInstalled) {
              return;
            }
            window.__flameNetProbeInstalled = true;

            var IDENTITY_PATHS = [
              '/aweme/v1/web/im/user/info',
              '/aweme/v1/web/user/profile/other',
              '/aweme/v1/web/user/profile/scene'
            ];
            var PROTO_PATHS = [
              '/v1/message/get_message_by_init'
            ];

            var probe = {
              identityHits: 0,
              identityRecords: 0,
              initProtoHits: 0,
              initProtoBytes: 0,
              paths: {}
            };
            window.__flameNetProbe = probe;

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

            function countIdentityObjects(root) {
              if (!root || typeof root !== 'object') return 0;
              var stack = [root];
              var visited = 0;
              var count = 0;
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
                if (cur.sec_uid || cur.sec_user_id || cur.secUid || cur.secUserId || cur.uid || cur.user_id || cur.userId) {
                  count++;
                }
                var keys = Object.keys(cur);
                for (var k = 0; k < keys.length; k++) {
                  var child = cur[keys[k]];
                  if (child && typeof child === 'object') stack.push(child);
                }
              }
              return count;
            }

            function recordIdentityPayload(matchedKey, payload) {
              probe.identityHits += 1;
              probe.paths[matchedKey] = (probe.paths[matchedKey] || 0) + 1;
              probe.identityRecords += countIdentityObjects(payload);
            }

            function recordProtoPayload(matchedKey, byteLen) {
              probe.initProtoHits += 1;
              probe.paths[matchedKey] = (probe.paths[matchedKey] || 0) + 1;
              if (typeof byteLen === 'number' && byteLen > 0) {
                probe.initProtoBytes += byteLen;
              }
            }

            if (typeof window.fetch === 'function') {
              var origFetch = window.fetch;
              window.fetch = function (input, init) {
                var rawUrl = '';
                try {
                  rawUrl = typeof input === 'string' ? input : (input && input.url ? input.url : '');
                } catch (_) {}
                var path = extractPath(rawUrl);
                var idMatch = matchPath(path, IDENTITY_PATHS);
                var protoMatch = matchPath(path, PROTO_PATHS);
                var promise = origFetch.apply(this, arguments);
                if (idMatch || protoMatch) {
                  try {
                    promise.then(function (resp) {
                      try {
                        if (!resp || typeof resp.clone !== 'function') return;
                        var cloned = resp.clone();
                        if (idMatch) {
                          cloned.json().then(function (data) {
                            recordIdentityPayload(idMatch, data);
                          }).catch(function () {});
                        } else if (protoMatch) {
                          cloned.arrayBuffer().then(function (buf) {
                            recordProtoPayload(protoMatch, buf ? buf.byteLength : 0);
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
                try { this.__flameUrl = url ? String(url) : ''; } catch (_) {}
                return origOpen.apply(this, arguments);
              };
              XMLHttpRequest.prototype.send = function () {
                try {
                  var path = extractPath(this.__flameUrl || '');
                  var idMatch = matchPath(path, IDENTITY_PATHS);
                  var protoMatch = matchPath(path, PROTO_PATHS);
                  if ((idMatch || protoMatch) && !this.__flameHooked) {
                    this.__flameHooked = true;
                    this.addEventListener('load', function () {
                      try {
                        if (idMatch) {
                          if (this.responseType === 'json' && this.response) {
                            recordIdentityPayload(idMatch, this.response);
                          } else if (typeof this.responseText === 'string' && this.responseText) {
                            recordIdentityPayload(idMatch, JSON.parse(this.responseText));
                          }
                        } else if (protoMatch) {
                          var len = 0;
                          if (this.responseType === 'arraybuffer' && this.response && typeof this.response.byteLength === 'number') {
                            len = this.response.byteLength;
                          } else if (typeof this.responseText === 'string') {
                            len = this.responseText.length;
                          }
                          recordProtoPayload(protoMatch, len);
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

    val OFFSCREEN_DOM_PROBE_JS: String = """
        (async function () {
          function safeEval(fn, fallback) {
            try { return fn(); } catch (_) { return fallback; }
          }

          var anyExists = function (selectors) {
            for (var i = 0; i < selectors.length; i++) {
              if (document.querySelector(selectors[i])) return selectors[i];
            }
            return null;
          };

          var host = String(location.hostname || '').toLowerCase();
          var pathname = String(location.pathname || '');
          var isMobileRedirect = (host.indexOf('m.') === 0) ||
            (pathname.indexOf('/wap') === 0) ||
            (pathname.indexOf('/share') === 0);

          var qrImgFound = false;
          try {
            var imgs = document.querySelectorAll('img');
            for (var i = 0; i < imgs.length; i++) {
              var src = imgs[i].src || '';
              if (src.indexOf('data:image') !== 0) continue;
              var r = imgs[i].getBoundingClientRect();
              if (r.width >= 120 && r.width <= 260 && r.height >= 120 && r.height <= 260) {
                qrImgFound = true;
                break;
              }
            }
          } catch (_) {}

          var streakContainers = document.querySelectorAll('[class*="StreakstreakContainer"]');
          var sparkNumericCount = 0;
          var sparkLabelCount = 0;
          var sparkIconCount = 0;
          for (var s = 0; s < streakContainers.length; s++) {
            var sc = streakContainers[s];
            var daysEl = sc.querySelector('[class*="StreaknormalText"]');
            var daysText = daysEl ? String(daysEl.innerText || daysEl.textContent || '').trim() : '';
            if (/^\d+$/.test(daysText)) {
              sparkNumericCount += 1;
            } else if (daysText) {
              sparkLabelCount += 1;
            }
            if (sc.querySelector('img[src*="flame_icon"]')) {
              sparkIconCount += 1;
            }
          }

          var hasLocalStorageUserInfo = false;
          try {
            var rawUser = localStorage.getItem('user_info');
            if (rawUser) {
              var parsedUser = JSON.parse(rawUser);
              if (parsedUser && typeof parsedUser.nickname === 'string' && parsedUser.nickname.trim().length > 0) {
                hasLocalStorageUserInfo = true;
              }
            }
          } catch (_) {}

          var readIndexedDbCounts = function () {
            if (typeof indexedDB === 'undefined' || typeof indexedDB.databases !== 'function') {
              return Promise.resolve({ dbCount: 0, recordCount: 0 });
            }
            var readOne = function (name) {
              return new Promise(function (resolve) {
                var done = false;
                var finish = function (n) {
                  if (!done) { done = true; resolve(n); }
                };
                var timer = setTimeout(function () { finish(0); }, 1500);
                try {
                  var req = indexedDB.open(name);
                  req.onerror = function () { clearTimeout(timer); finish(0); };
                  req.onblocked = function () { clearTimeout(timer); finish(0); };
                  req.onsuccess = function (openEv) {
                    var db = openEv && openEv.target ? openEv.target.result : req.result;
                    var stores = Array.from(db.objectStoreNames || []);
                    if (!stores.length) {
                      clearTimeout(timer);
                      try { db.close(); } catch (_) {}
                      finish(0);
                      return;
                    }
                    var total = 0;
                    var pending = stores.length;
                    var onStoreDone = function () {
                      pending -= 1;
                      if (pending <= 0) {
                        clearTimeout(timer);
                        try { db.close(); } catch (_) {}
                        finish(total);
                      }
                    };
                    stores.forEach(function (storeName) {
                      try {
                        var tx = db.transaction(storeName, 'readonly');
                        var store = tx.objectStore(storeName);
                        var allReq = store.getAll();
                        allReq.onsuccess = function (ev) {
                          try {
                            var rows = (ev && ev.target && ev.target.result) ? ev.target.result : [];
                            for (var r = 0; r < rows.length; r++) {
                              var item = rows[r];
                              if (!item || typeof item !== 'object') continue;
                              var v = (item.value && typeof item.value === 'object') ? item.value : item;
                              if (v && (v.sec_uid || v.sec_user_id || v.secUid || v.secUserId || v.uid || v.user_id || v.userId)) {
                                total += 1;
                              }
                            }
                          } catch (_) {}
                          onStoreDone();
                        };
                        allReq.onerror = function () { onStoreDone(); };
                      } catch (_) {
                        onStoreDone();
                      }
                    });
                  };
                } catch (_) {
                  clearTimeout(timer);
                  finish(0);
                }
              });
            };
            return Promise.race([
              indexedDB.databases().then(function (dbs) {
                var userDbs = (dbs || [])
                  .map(function (d) { return d && d.name; })
                  .filter(function (name) { return typeof name === 'string' && name.toLowerCase().indexOf('user') !== -1; });
                return Promise.all(userDbs.map(readOne)).then(function (counts) {
                  var sum = 0;
                  for (var c = 0; c < counts.length; c++) sum += counts[c];
                  return { dbCount: userDbs.length, recordCount: sum };
                });
              }).catch(function () {
                return { dbCount: 0, recordCount: 0 };
              }),
              new Promise(function (resolve) {
                setTimeout(function () { resolve({ dbCount: -1, recordCount: -1 }); }, 2000);
              })
            ]);
          };

          var idb = await readIndexedDbCounts();
          var bodyText = safeEval(function () { return String(document.body ? (document.body.innerText || '') : ''); }, '');
          var net = window.__flameNetProbe || {
            identityHits: 0,
            identityRecords: 0,
            initProtoHits: 0,
            initProtoBytes: 0,
            paths: {}
          };

          var result = {
            urlOriginPath: String(location.origin || '') + pathname,
            viewport: {
              innerWidth: window.innerWidth,
              innerHeight: window.innerHeight,
              devicePixelRatio: window.devicePixelRatio || 1
            },
            platform: String(navigator.platform || ''),
            uaDataMobile: (navigator.userAgentData && typeof navigator.userAgentData.mobile === 'boolean')
              ? navigator.userAgentData.mobile
              : null,
            visibilityState: String(document.visibilityState || ''),
            isMobileRedirect: isMobileRedirect,
            loggedIn: !!document.querySelector('.conversationConversationListwrapper'),
            qrImgFound: qrImgFound,
            verificationModalVisible: bodyText.indexOf('身份验证') !== -1,
            captchaLikely: bodyText.indexOf('请完成下列验证') !== -1 ||
              bodyText.indexOf('向右滑动') !== -1 ||
              bodyText.indexOf('安全验证') !== -1,
            conversationCount: document.querySelectorAll('.conversationConversationItemwrapper').length,
            sparkCount: streakContainers.length,
            sparkNumericCount: sparkNumericCount,
            sparkLabelCount: sparkLabelCount,
            sparkIconCount: sparkIconCount,
            searchInputFound: !!anyExists([
              'input.semi-input[placeholder="搜索"][type="text"]',
              'input[placeholder="搜索"]'
            ]),
            hasLocalStorageUserInfo: hasLocalStorageUserInfo,
            indexedDbUserDbCount: idb.dbCount,
            indexedDbIdentityRecordCount: idb.recordCount,
            netProbe: {
              identityHits: net.identityHits || 0,
              identityRecords: net.identityRecords || 0,
              initProtoHits: net.initProtoHits || 0,
              initProtoBytes: net.initProtoBytes || 0,
              paths: net.paths || {}
            }
          };

          if (window.FlameM1 && typeof window.FlameM1.onProbe === 'function') {
            window.FlameM1.onProbe(JSON.stringify(result));
          }
          return result;
        })();
    """.trimIndent()

    val OPEN_FIRST_CONVERSATION_JS: String = """
        (function () {
          var rows = Array.from(document.querySelectorAll('.conversationConversationItemwrapper'));
          if (!rows.length) {
            return { clicked: false, hadSparkRow: false, rowIndex: -1, totalRows: 0 };
          }
          var sparkIdx = rows.findIndex(function (r) {
            return !!r.querySelector('[class*="StreakstreakContainer"]');
          });
          var idx = sparkIdx >= 0 ? sparkIdx : 0;
          var row = rows[idx];
          try { row.scrollIntoView({ block: 'nearest' }); } catch (_) {}
          var target = row.querySelector('.conversationConversationItemtitle') || row;
          var rect = target.getBoundingClientRect ? target.getBoundingClientRect() : { left: 0, top: 0, width: 0, height: 0 };
          var cx = rect.left + rect.width / 2;
          var cy = rect.top + rect.height / 2;
          var opts = { bubbles: true, cancelable: true, view: window, clientX: cx, clientY: cy };
          try { target.dispatchEvent(new PointerEvent('pointerdown', opts)); } catch (_) {}
          try { target.dispatchEvent(new MouseEvent('mousedown', opts)); } catch (_) {}
          try { target.dispatchEvent(new PointerEvent('pointerup', opts)); } catch (_) {}
          try { target.dispatchEvent(new MouseEvent('mouseup', opts)); } catch (_) {}
          try { target.dispatchEvent(new MouseEvent('click', opts)); } catch (_) {}
          try { target.click(); } catch (_) {}
          try { if (target !== row) row.click(); } catch (_) {}
          return {
            clicked: true,
            hadSparkRow: sparkIdx >= 0,
            rowIndex: idx,
            totalRows: rows.length
          };
        })();
    """.trimIndent()

    val EDITOR_READY_CHECK_JS: String = """
        (function () {
          var selectors = [
            'div[data-slate-editor="true"][contenteditable="true"]',
            '[class*="messageEditor"] [contenteditable="true"]',
            '.messageEditorinputArea[contenteditable="true"]'
          ];
          var anyEditor = null;
          var visibleEditor = null;
          for (var i = 0; i < selectors.length; i++) {
            var els = document.querySelectorAll(selectors[i]);
            for (var j = 0; j < els.length; j++) {
              if (!anyEditor) anyEditor = els[j];
              var r = els[j].getBoundingClientRect();
              if (r.width > 0 && r.height > 0 && !visibleEditor) {
                visibleEditor = { el: els[j], w: Math.round(r.width), h: Math.round(r.height) };
                break;
              }
            }
            if (visibleEditor) break;
          }
          return {
            rightTitleFound: !!document.querySelector('.RightPanelHeadertitleContainer'),
            editorFound: !!anyEditor,
            editorVisible: !!visibleEditor,
            editorRectW: visibleEditor ? visibleEditor.w : 0,
            editorRectH: visibleEditor ? visibleEditor.h : 0,
            contentEditableCount: document.querySelectorAll('[contenteditable="true"]').length
          };
        })();
    """.trimIndent()

    val SLATE_NON_SENDING_EXEC_COMMAND_JS: String = """
        (function () {
          var selectors = [
            'div[data-slate-editor="true"][contenteditable="true"]',
            '[class*="messageEditor"] [contenteditable="true"]',
            '.messageEditorinputArea[contenteditable="true"]'
          ];
          var editor = null;
          for (var i = 0; i < selectors.length; i++) {
            var els = document.querySelectorAll(selectors[i]);
            for (var j = 0; j < els.length; j++) {
              var r = els[j].getBoundingClientRect();
              if (r.width > 0 && r.height > 0) { editor = els[j]; break; }
            }
            if (editor) break;
          }
          if (!editor) {
            if (window.FlameM1) window.FlameM1.onSlateResult(JSON.stringify({ ok: false, reason: 'editor_not_found' }));
            return;
          }
          var existing = String(editor.innerText || '').replace(/[\s\u200B\u200C\u200D\uFEFF]+/g, '');
          if (existing.length > 0) {
            if (window.FlameM1) window.FlameM1.onSlateResult(JSON.stringify({ ok: false, reason: 'editor_not_empty' }));
            return;
          }

          var marker = '[m1-offscreen]';
          editor.focus();
          try {
            var sel = window.getSelection();
            var range = document.createRange();
            range.selectNodeContents(editor);
            range.collapse(false);
            sel.removeAllRanges();
            sel.addRange(range);
          } catch (_) {}

          var execReturn = false;
          try {
            execReturn = document.execCommand('insertText', false, marker);
          } catch (_) {
            execReturn = false;
          }

          setTimeout(function () {
            var accepted = String(editor.innerText || '').indexOf(marker) !== -1;
            var sendBtns = [
              '[class*="messageMsgInputpublishBtn"]',
              '.e2e-send-msg-bt',
              'button[aria-label*="发送"]',
              '[role="button"][aria-label*="发送"]'
            ];
            var sendVisible = false;
            for (var b = 0; b < sendBtns.length; b++) {
              var btns = document.querySelectorAll(sendBtns[b]);
              for (var k = 0; k < btns.length; k++) {
                var br = btns[k].getBoundingClientRect();
                var st = window.getComputedStyle ? window.getComputedStyle(btns[k]) : null;
                if (br.width > 0 && br.height > 0 && (!st || (st.display !== 'none' && st.visibility !== 'hidden'))) {
                  sendVisible = true;
                  break;
                }
              }
              if (sendVisible) break;
            }

            editor.focus();
            try {
              var sel2 = window.getSelection();
              var r2 = document.createRange();
              r2.selectNodeContents(editor);
              sel2.removeAllRanges();
              sel2.addRange(r2);
              document.execCommand('delete', false, null);
            } catch (_) {}

            var after = String(editor.innerText || '').replace(/[\s\u200B\u200C\u200D\uFEFF]+/g, '');
            if (window.FlameM1 && typeof window.FlameM1.onSlateResult === 'function') {
              window.FlameM1.onSlateResult(JSON.stringify({
                ok: true,
                rightTitleFound: !!document.querySelector('.RightPanelHeadertitleContainer'),
                execReturn: !!execReturn,
                accepted: accepted,
                sendVisible: sendVisible,
                cleared: after.length === 0
              }));
            }
          }, 200);
        })();
    """.trimIndent()
}
