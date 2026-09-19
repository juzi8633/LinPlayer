// 插件运行时的 Web 全局与 SDK 外壳(D209)。Go 侧先注入 __lp(原生函数),这里包成标准形状。
// 没有 DOM、没有 Node 内置模块(D148)。
(function (g) {
  'use strict';
  var N = g.__lp;

  class PluginError extends Error {
    constructor(init) {
      init = init || {};
      super(init.message || '');
      this.name = 'PluginError';
      this.kind = init.kind || 'internal';
      if (init.retryAfter !== undefined) this.retryAfter = init.retryAfter;
      if (init.verifyUrl !== undefined) this.verifyUrl = init.verifyUrl;
      if (init.loginPage !== undefined) this.loginPage = init.loginPage;
      if (init.detail !== undefined) this.detail = init.detail;
    }
  }
  g.PluginError = PluginError;

  // ---------------------------------------------------------------- AbortController
  class AbortSignal {
    constructor() { this.aborted = false; this.reason = undefined; this._l = []; this.onabort = null; }
    addEventListener(t, f) { if (t === 'abort') this._l.push(f); }
    removeEventListener(t, f) { this._l = this._l.filter(function (x) { return x !== f; }); }
    throwIfAborted() { if (this.aborted) throw this.reason; }
    _fire(reason) {
      if (this.aborted) return;
      this.aborted = true;
      this.reason = reason !== undefined ? reason : new PluginError({ kind: 'timeout', message: '请求已取消' });
      var ev = { type: 'abort', target: this };
      if (typeof this.onabort === 'function') this.onabort(ev);
      this._l.slice().forEach(function (f) { f(ev); });
    }
    static timeout(ms) { var c = new AbortController(); setTimeout(function () { c.abort(new PluginError({ kind: 'timeout', message: '请求超时' })); }, ms); return c.signal; }
  }
  class AbortController {
    constructor() { this.signal = new AbortSignal(); }
    abort(reason) { this.signal._fire(reason); }
  }
  g.AbortSignal = AbortSignal;
  g.AbortController = AbortController;

  // ---------------------------------------------------------------- Headers
  class Headers {
    constructor(init) {
      this._m = {};
      if (!init) return;
      if (init instanceof Headers) init = init._m;
      if (Array.isArray(init)) init.forEach(function (p) { this.append(p[0], p[1]); }, this);
      else Object.keys(init).forEach(function (k) { this.append(k, init[k]); }, this);
    }
    append(k, v) { k = String(k).toLowerCase(); v = String(v); this._m[k] = this._m[k] !== undefined ? this._m[k] + ', ' + v : v; }
    set(k, v) { this._m[String(k).toLowerCase()] = String(v); }
    get(k) { var v = this._m[String(k).toLowerCase()]; return v === undefined ? null : v; }
    has(k) { return this._m[String(k).toLowerCase()] !== undefined; }
    delete(k) { delete this._m[String(k).toLowerCase()]; }
    forEach(f, t) { Object.keys(this._m).forEach(function (k) { f.call(t, this._m[k], k, this); }, this); }
    keys() { return Object.keys(this._m)[Symbol.iterator](); }
    values() { var m = this._m; return Object.keys(m).map(function (k) { return m[k]; })[Symbol.iterator](); }
    entries() { var m = this._m; return Object.keys(m).map(function (k) { return [k, m[k]]; })[Symbol.iterator](); }
    [Symbol.iterator]() { return this.entries(); }
  }
  g.Headers = Headers;

  // ---------------------------------------------------------------- 编码
  class TextEncoder {
    get encoding() { return 'utf-8'; }
    encode(s) { return new Uint8Array(N.utf8Encode(String(s === undefined ? '' : s))); }
  }
  class TextDecoder {
    constructor(label) { this.encoding = (label || 'utf-8').toLowerCase(); }
    decode(b) { return b === undefined ? '' : N.decode(toBuffer(b), this.encoding); }
  }
  g.TextEncoder = TextEncoder;
  g.TextDecoder = TextDecoder;

  function toBuffer(b) {
    if (b instanceof ArrayBuffer) return b;
    if (ArrayBuffer.isView(b)) return b.buffer.slice(b.byteOffset, b.byteOffset + b.byteLength);
    throw new TypeError('需要 ArrayBuffer 或 TypedArray');
  }

  // ---------------------------------------------------------------- Body / Response / Request
  function bodyToBuffer(body) {
    if (body === undefined || body === null) return null;
    if (typeof body === 'string') return N.utf8Encode(body);
    if (body instanceof URLSearchParams) return N.utf8Encode(body.toString());
    if (body instanceof ArrayBuffer || ArrayBuffer.isView(body)) return toBuffer(body);
    return N.utf8Encode(String(body));
  }

  class ReadableStreamReader {
    constructor(res) { this._r = res; }
    read() {
      var r = this._r;
      if (r._handle) return N.bodyRead(r._handle).then(function (b) { return b === null ? { done: true, value: undefined } : { done: false, value: new Uint8Array(b) }; });
      if (r._buf) { var b = r._buf; r._buf = null; return Promise.resolve({ done: false, value: new Uint8Array(b) }); }
      return Promise.resolve({ done: true, value: undefined });
    }
    cancel() { if (this._r._handle) N.bodyClose(this._r._handle); this._r._handle = 0; return Promise.resolve(); }
    releaseLock() {}
  }

  class Response {
    constructor(body, init) {
      init = init || {};
      this.status = init.status === undefined ? 200 : init.status;
      this.statusText = init.statusText || '';
      this.headers = new Headers(init.headers);
      this.url = init.url || '';
      this.redirected = !!init.redirected;
      this.type = 'basic';
      this._handle = init._handle || 0;
      this._buf = this._handle ? null : bodyToBuffer(body);
      this.bodyUsed = false;
      var self = this;
      this.body = { getReader: function () { return new ReadableStreamReader(self); } };
    }
    get ok() { return this.status >= 200 && this.status < 300; }
    arrayBuffer() {
      if (this.bodyUsed) return Promise.reject(new TypeError('响应体已被读取'));
      this.bodyUsed = true;
      if (this._handle) { var h = this._handle; this._handle = 0; return N.bodyAll(h); }
      return Promise.resolve(this._buf || new ArrayBuffer(0));
    }
    text() {
      var ct = this.headers.get('content-type') || '';
      return this.arrayBuffer().then(function (b) { return N.decodeAuto(b, ct); });
    }
    json() { return this.text().then(function (t) { return JSON.parse(t); }); }
    clone() { throw new PluginError({ kind: 'unsupported', message: 'Response.clone 不支持' }); }
    static json(v, init) { init = init || {}; var h = new Headers(init.headers); h.set('content-type', 'application/json'); return new Response(JSON.stringify(v), { status: init.status, headers: h }); }
  }
  g.Response = Response;

  class Request {
    constructor(input, init) {
      init = init || {};
      if (input instanceof Request) { init = Object.assign({ method: input.method, headers: input.headers, body: input._body, signal: input.signal, redirect: input.redirect, lp: input.lp }, init); input = input.url; }
      this.url = String(input);
      this.method = (init.method || 'GET').toUpperCase();
      this.headers = new Headers(init.headers);
      this._body = init.body;
      this.signal = init.signal || null;
      this.redirect = init.redirect || 'follow';
      this.lp = init.lp;
    }
    text() { return Promise.resolve(this._body === undefined || this._body === null ? '' : typeof this._body === 'string' ? this._body : N.decode(bodyToBuffer(this._body), 'utf-8')); }
    json() { return this.text().then(JSON.parse); }
    arrayBuffer() { return Promise.resolve(bodyToBuffer(this._body) || new ArrayBuffer(0)); }
  }
  g.Request = Request;

  g.fetch = function (input, init) {
    var req = new Request(input, init);
    if (req.signal && req.signal.aborted) return Promise.reject(req.signal.reason);
    var p = N.fetch({
      url: req.url, method: req.method, headers: req.headers._m,
      body: bodyToBuffer(req._body), redirect: req.redirect, lp: req.lp || {},
    });
    var id = p.id;
    if (req.signal) req.signal.addEventListener('abort', function () { N.fetchAbort(id); });
    return p.promise.then(function (r) {
      return new Response(null, { status: r.status, statusText: r.statusText, headers: r.headers, url: r.url, redirected: r.redirected, _handle: r.handle });
    });
  };

  // ---------------------------------------------------------------- URL
  class URLSearchParams {
    constructor(init) {
      this._p = [];
      if (init === undefined || init === null) return;
      if (init instanceof URLSearchParams) { this._p = init._p.slice(); return; }
      if (typeof init === 'string') {
        var s = init.charAt(0) === '?' ? init.slice(1) : init;
        if (!s) return;
        s.split('&').forEach(function (kv) {
          if (!kv) return;
          var i = kv.indexOf('=');
          var k = i < 0 ? kv : kv.slice(0, i), v = i < 0 ? '' : kv.slice(i + 1);
          this._p.push([dec(k), dec(v)]);
        }, this);
        return;
      }
      if (Array.isArray(init)) { init.forEach(function (p) { this._p.push([String(p[0]), String(p[1])]); }, this); return; }
      Object.keys(init).forEach(function (k) { this._p.push([k, String(init[k])]); }, this);
    }
    append(k, v) { this._p.push([String(k), String(v)]); this._sync(); }
    set(k, v) { k = String(k); var found = false; this._p = this._p.filter(function (p) { if (p[0] !== k) return true; if (found) return false; found = true; p[1] = String(v); return true; }); if (!found) this._p.push([k, String(v)]); this._sync(); }
    get(k) { for (var i = 0; i < this._p.length; i++) if (this._p[i][0] === k) return this._p[i][1]; return null; }
    getAll(k) { return this._p.filter(function (p) { return p[0] === k; }).map(function (p) { return p[1]; }); }
    has(k) { return this._p.some(function (p) { return p[0] === k; }); }
    delete(k) { this._p = this._p.filter(function (p) { return p[0] !== k; }); this._sync(); }
    sort() { this._p.sort(function (a, b) { return a[0] < b[0] ? -1 : a[0] > b[0] ? 1 : 0; }); this._sync(); }
    forEach(f, t) { this._p.forEach(function (p) { f.call(t, p[1], p[0], this); }, this); }
    keys() { return this._p.map(function (p) { return p[0]; })[Symbol.iterator](); }
    values() { return this._p.map(function (p) { return p[1]; })[Symbol.iterator](); }
    entries() { return this._p.map(function (p) { return [p[0], p[1]]; })[Symbol.iterator](); }
    [Symbol.iterator]() { return this.entries(); }
    get size() { return this._p.length; }
    toString() { return this._p.map(function (p) { return enc(p[0]) + '=' + enc(p[1]); }).join('&'); }
    _sync() { if (this._url) this._url._setSearch(this.toString()); }
  }
  function enc(s) { return encodeURIComponent(s).replace(/%20/g, '+').replace(/[!'()~]/g, function (c) { return '%' + c.charCodeAt(0).toString(16).toUpperCase(); }); }
  function dec(s) { try { return decodeURIComponent(s.replace(/\+/g, ' ')); } catch (e) { return s; } }
  g.URLSearchParams = URLSearchParams;

  var URL_FIELDS = ['protocol', 'username', 'password', 'hostname', 'port', 'pathname', 'search', 'hash'];
  class URL {
    constructor(href, base) {
      var p = N.urlParse(String(href), base === undefined ? '' : String(base));
      if (!p) throw new TypeError('无效的 URL: ' + href);
      this._p = p;
      this._sp = new URLSearchParams(p.search);
      this._sp._url = this;
    }
    _set(k, v) { var q = Object.assign({}, this._p); q[k] = String(v); var p = N.urlFormat(q); if (p) { this._p = p; if (k !== 'search') { this._sp = new URLSearchParams(p.search); this._sp._url = this; } } }
    _setSearch(s) { var q = Object.assign({}, this._p); q.search = s ? '?' + s : ''; var p = N.urlFormat(q); if (p) this._p = p; }
    get href() { return this._p.href; } set href(v) { var u = new URL(v); this._p = u._p; this._sp = new URLSearchParams(this._p.search); this._sp._url = this; }
    get origin() { return this._p.origin; }
    get host() { return this._p.host; } set host(v) { var i = String(v).lastIndexOf(':'); if (i > 0 && String(v).indexOf(']') < i) { this._set('hostname', String(v).slice(0, i)); this._set('port', String(v).slice(i + 1)); } else this._set('hostname', v); }
    get searchParams() { return this._sp; }
    toString() { return this._p.href; }
    toJSON() { return this._p.href; }
    static canParse(h, b) { return !!N.urlParse(String(h), b === undefined ? '' : String(b)); }
  }
  URL_FIELDS.forEach(function (k) {
    Object.defineProperty(URL.prototype, k, { get: function () { return this._p[k]; }, set: function (v) { this._set(k, v); }, configurable: true });
  });
  g.URL = URL;

  // ---------------------------------------------------------------- 杂项
  g.queueMicrotask = function (f) { Promise.resolve().then(f); };
  g.structuredClone = function (v) { return v === undefined ? undefined : JSON.parse(JSON.stringify(v)); };
  g.self = g;
  g.globalThis = g;
})(globalThis);
