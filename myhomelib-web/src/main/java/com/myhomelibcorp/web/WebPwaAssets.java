package com.myhomelibcorp.web;

import java.io.IOException;
import java.io.InputStream;
import java.util.Objects;

/** Versioned, non-user-specific PWA assets for the existing Web Library. */
public final class WebPwaAssets {
    public static final String ASSET_VERSION = "v29-1";
    public static final String CLIENT_JS_PATH = "/web/assets/pwa-" + ASSET_VERSION + ".js";
    public static final String CSS_PATH = "/web/assets/pwa-" + ASSET_VERSION + ".css";
    public static final String MANIFEST_PATH = "/web/app.webmanifest";
    public static final String SERVICE_WORKER_PATH = "/web/sw.js";
    public static final String OFFLINE_PATH = "/web/offline.html";
    public static final String ICON_192_PATH = "/web/icons/icon-192.png";
    public static final String ICON_512_PATH = "/web/icons/icon-512.png";

    private final WebTextCatalog text;

    public WebPwaAssets() { this(new WebTextCatalog()); }
    public WebPwaAssets(WebTextCatalog text) { this.text = Objects.requireNonNull(text, "text"); }

    public String manifest() { return manifest(WebTextCatalog.DEFAULT_LANGUAGE); }

    public String manifest(String language) {
        String lang = text.languageTag(language);
        return "{" +
                "\"name\":\"" + json(text.text(lang, "web.pwa.name")) + "\"," +
                "\"short_name\":\"MyHomeLib\"," +
                "\"description\":\"" + json(text.text(lang, "web.pwa.description")) + "\"," +
                "\"lang\":\"" + json(lang) + "\"," +
                "\"id\":\"/web/\",\"start_url\":\"/web/\",\"scope\":\"/web/\"," +
                "\"display\":\"standalone\",\"background_color\":\"#f7f7f8\",\"theme_color\":\"#111827\"," +
                "\"icons\":[" +
                "{\"src\":\"/web/icons/icon-192.png\",\"sizes\":\"192x192\",\"type\":\"image/png\",\"purpose\":\"any maskable\"}," +
                "{\"src\":\"/web/icons/icon-512.png\",\"sizes\":\"512x512\",\"type\":\"image/png\",\"purpose\":\"any maskable\"}" +
                "]}";
    }

    public String headTags() {
        return "<meta name=\"theme-color\" content=\"#111827\">"
                + "<meta name=\"application-name\" content=\"MyHomeLib\">"
                + "<link rel=\"manifest\" href=\"" + MANIFEST_PATH + "\">"
                + "<link rel=\"icon\" sizes=\"192x192\" href=\"" + ICON_192_PATH + "\">"
                + "<link rel=\"stylesheet\" href=\"" + CSS_PATH + "\">"
                + "<script src=\"" + CLIENT_JS_PATH + "\" defer></script>";
    }

    public String stylesheet() {
        return """
                .pwa-offline-actions{display:flex;gap:.55rem;align-items:center;flex-wrap:wrap;margin-top:.75rem}
                .pwa-offline-actions button{font:inherit;padding:.55rem .75rem;border-radius:.45rem;border:1px solid #9ca3af;background:var(--panel,#fff);color:inherit;cursor:pointer}
                .pwa-offline-actions button[data-pwa-save-book]{background:#1d4ed8;color:#fff;border-color:#1d4ed8}
                .pwa-offline-actions button:disabled{opacity:.65;cursor:wait}
                .pwa-status{font-size:.9rem;color:var(--muted,#6b7280);min-height:1.2em}
                .pwa-offline-page{max-width:720px;margin:10vh auto;padding:1rem;font:16px system-ui,sans-serif}.pwa-offline-page article{background:#fff;border:1px solid #e5e7eb;border-radius:.7rem;padding:1.25rem}
                """.strip();
    }

    public String clientScript() {
        return """
                (()=>{'use strict';
                const DB='mhl-web-progress-v1',STORE='progress',SEQ='mhl.web.progress.sequence';
                const isLoopback=['localhost','127.0.0.1','::1'].includes(location.hostname);
                const secureContext=window.isSecureContext||isLoopback;
                let registrationPromise=Promise.resolve(null);
                if('serviceWorker' in navigator&&secureContext){registrationPromise=navigator.serviceWorker.register('/web/sw.js',{scope:'/web/'}).then(()=>navigator.serviceWorker.ready).catch(()=>null)}
                const openDb=()=>new Promise((resolve,reject)=>{if(!('indexedDB' in self)){reject(new Error('IndexedDB unavailable'));return}const r=indexedDB.open(DB,1);r.onupgradeneeded=()=>{const db=r.result;if(!db.objectStoreNames.contains(STORE))db.createObjectStore(STORE,{keyPath:'key'})};r.onsuccess=()=>resolve(r.result);r.onerror=()=>reject(r.error)});
                const nextSequence=()=>{let v=Number(localStorage.getItem(SEQ)||'0');if(!Number.isSafeInteger(v)||v<0)v=0;v++;localStorage.setItem(SEQ,String(v));return v};
                const transact=(mode,fn)=>openDb().then(db=>new Promise((resolve,reject)=>{const tx=db.transaction(STORE,mode),store=tx.objectStore(STORE);fn(store,tx);tx.oncomplete=()=>{db.close();resolve()};tx.onerror=()=>{db.close();reject(tx.error)};tx.onabort=()=>{db.close();reject(tx.error||new Error('transaction aborted'))}}));
                const putLatest=entry=>transact('readwrite',(store)=>{const q=store.openCursor();q.onsuccess=()=>{const c=q.result;if(c){if(c.value&&c.value.bookId===entry.bookId)c.delete();c.continue()}else store.put(entry)}});
                const all=()=>openDb().then(db=>new Promise((resolve,reject)=>{const tx=db.transaction(STORE,'readonly'),r=tx.objectStore(STORE).getAll();r.onsuccess=()=>{db.close();resolve(r.result||[])};r.onerror=()=>{db.close();reject(r.error)}}));
                const remove=key=>transact('readwrite',(store)=>store.delete(key));
                async function sendProgress(entry){const r=await fetch('/web/read/'+encodeURIComponent(entry.bookId)+'/progress',{method:'POST',credentials:'same-origin',cache:'no-store',keepalive:true,headers:{'Content-Type':'application/json','X-MyHomeLib-Request':'1'},body:JSON.stringify({chapter:entry.chapter,offset:entry.offset,clientTimestamp:entry.clientTimestamp,clientSequence:entry.clientSequence})});if(!r.ok)throw new Error('progress '+r.status);return r.json()}
                async function flush(){if(!navigator.onLine)return;let entries;try{entries=await all()}catch(_){return}entries.sort((a,b)=>a.clientSequence-b.clientSequence);for(const entry of entries){try{await sendProgress(entry);await remove(entry.key)}catch(_){break}}}
                async function queueProgress(payload){const seq=nextSequence(),entry={key:String(payload.bookId)+':'+seq,bookId:String(payload.bookId),chapter:Number(payload.chapter),offset:Number(payload.offset),clientTimestamp:Date.now(),clientSequence:seq};try{await putLatest(entry);await flush()}catch(_){try{await sendProgress(entry)}catch(__){}}}
                async function postToWorker(message){const reg=await registrationPromise;if(!reg)return{ok:false};const target=navigator.serviceWorker.controller||reg.active||reg.waiting;if(!target)return{ok:false};return await new Promise(resolve=>{const channel=new MessageChannel(),timer=setTimeout(()=>resolve({ok:false}),45000);channel.port1.onmessage=e=>{clearTimeout(timer);resolve(e.data||{ok:false})};target.postMessage(message,[channel.port2])})}
                function statusFor(button,text){const root=button.closest('.pwa-offline-actions'),s=root&&root.querySelector('.pwa-status');if(s)s.textContent=text||''}
                async function act(button,message){button.disabled=true;statusFor(button,button.dataset.working||'…');const result=await postToWorker(message);statusFor(button,result.ok?(button.dataset.done||'✓'):(button.dataset.failed||'!'));button.disabled=false;return result}
                function bind(){document.querySelectorAll('[data-pwa-save-book]').forEach(b=>b.addEventListener('click',()=>act(b,{type:'SAVE_BOOK',bookId:b.dataset.pwaSaveBook})));document.querySelectorAll('[data-pwa-remove-book]').forEach(b=>b.addEventListener('click',()=>act(b,{type:'REMOVE_BOOK',bookId:b.dataset.pwaRemoveBook})));document.querySelectorAll('[data-pwa-clear-books]').forEach(b=>b.addEventListener('click',()=>act(b,{type:'CLEAR_OFFLINE_BOOKS'})))}
                addEventListener('online',flush);if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',bind,{once:true});else bind();
                self.MyHomeLibPwa={queueProgress,flush,saveBook:(id)=>postToWorker({type:'SAVE_BOOK',bookId:id}),removeBook:(id)=>postToWorker({type:'REMOVE_BOOK',bookId:id}),clearBooks:()=>postToWorker({type:'CLEAR_OFFLINE_BOOKS'})};
                })();
                """.strip();
    }

    public String serviceWorker() {
        return ("""
                'use strict';
                const V='__VERSION__',SHELL='mhl-shell-'+V,BOOK_PREFIX='mhl-book-'+V+'-',MAX_OFFLINE_BOOKS=20;
                const OFFLINE='/web/offline.html';
                const SHELL_URLS=['/web/app.webmanifest','/web/offline.html','/web/assets/pwa-__VERSION__.css','/web/assets/pwa-__VERSION__.js','/web/icons/icon-192.png','/web/icons/icon-512.png'];
                async function cleanResponse(r){const h=new Headers(r.headers);h.delete('set-cookie');h.delete('set-cookie2');h.delete('authorization');const body=await r.arrayBuffer();return new Response(body,{status:r.status,statusText:r.statusText,headers:h})}
                async function putClean(cache,url,response){await cache.put(new Request(url,{method:'GET',credentials:'omit'}),await cleanResponse(response))}
                self.addEventListener('install',e=>e.waitUntil((async()=>{const c=await caches.open(SHELL);for(const u of SHELL_URLS){const r=await fetch(u,{credentials:'omit',cache:'no-store'});if(!r.ok)throw new Error('shell '+r.status);await putClean(c,u,r)}await self.skipWaiting()})()));
                self.addEventListener('activate',e=>e.waitUntil((async()=>{for(const n of await caches.keys())if(n.startsWith('mhl-shell-')&&n!==SHELL)await caches.delete(n);await self.clients.claim()})()));
                const stamp=n=>{const v=Number(n.substring(n.lastIndexOf('-')+1));return Number.isFinite(v)?v:0};
                async function bookCacheNames(){return (await caches.keys()).filter(n=>n.startsWith(BOOK_PREFIX)).sort((a,b)=>stamp(b)-stamp(a))}
                async function bookCacheMatch(req){for(const n of await bookCacheNames()){const c=await caches.open(n),r=await c.match(req,{ignoreVary:true});if(r)return r}return null}
                async function enforceBookLimit(){const names=await bookCacheNames();for(const n of names.slice(MAX_OFFLINE_BOOKS))await caches.delete(n)}
                async function saveBook(id){const manifestUrl='/web/offline/books/'+encodeURIComponent(id)+'/manifest.json';const mr=await fetch(manifestUrl,{credentials:'same-origin',cache:'no-store'});if(!mr.ok)throw new Error('manifest '+mr.status);const m=await mr.json();if(!m||m.bookId!==id||!m.revision||!Array.isArray(m.chapterUrls)||!m.detailUrl)throw new Error('invalid manifest');const encoded=encodeURIComponent(id),prefix=BOOK_PREFIX+encoded+'-',name=prefix+m.revision+'-'+Date.now(),cache=await caches.open(name);const urls=[m.detailUrl,...m.chapterUrls];try{for(const u of urls){const r=await fetch(u,{credentials:'same-origin',cache:'no-store'});if(!r.ok)throw new Error('book '+r.status);await putClean(cache,u,r)}}catch(e){await caches.delete(name);throw e}for(const n of await caches.keys())if(n.startsWith(prefix)&&n!==name)await caches.delete(n);await enforceBookLimit();return m.revision}
                async function removeBook(id){const prefix=BOOK_PREFIX+encodeURIComponent(id)+'-';for(const n of await caches.keys())if(n.startsWith(prefix))await caches.delete(n)}
                async function clearBooks(){for(const n of await caches.keys())if(n.startsWith(BOOK_PREFIX))await caches.delete(n)}
                function reply(e,data){try{if(e.ports&&e.ports[0])e.ports[0].postMessage(data)}catch(_){}}
                self.addEventListener('message',e=>{const m=e.data||{};e.waitUntil((async()=>{try{if(m.type==='SAVE_BOOK'&&m.bookId){const revision=await saveBook(String(m.bookId));reply(e,{ok:true,revision})}else if(m.type==='REMOVE_BOOK'&&m.bookId){await removeBook(String(m.bookId));reply(e,{ok:true})}else if(m.type==='CLEAR_OFFLINE_BOOKS'){await clearBooks();reply(e,{ok:true})}else reply(e,{ok:false})}catch(_){reply(e,{ok:false})}})())});
                self.addEventListener('fetch',e=>{const req=e.request;if(req.method!=='GET')return;const u=new URL(req.url);if(u.origin!==location.origin)return;const isStatic=SHELL_URLS.includes(u.pathname);if(isStatic){e.respondWith(caches.match(new Request(u.pathname,{credentials:'omit'})).then(r=>r||fetch(req)));return}if(u.pathname.startsWith('/web/read/')||u.pathname.startsWith('/web/books/')){e.respondWith(fetch(req).catch(async()=>await bookCacheMatch(req)||await caches.match(OFFLINE)));return}if(req.mode==='navigate'&&u.pathname.startsWith('/web/'))e.respondWith(fetch(req).catch(()=>caches.match(OFFLINE)))});
                """).replace("__VERSION__", ASSET_VERSION).strip();
    }

    public String offlinePage(String language) {
        String lang = text.languageTag(language);
        String title = escape(text.text(lang, "web.offline.title"));
        String message = escape(text.text(lang, "web.offline.message"));
        return "<!doctype html><html lang=\"" + escape(lang) + "\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + headTags() + "<title>" + title + " — MyHomeLib</title></head><body><main class=\"pwa-offline-page\"><article><h1>" + title
                + "</h1><p>" + message + "</p><p><a href=\"/web/\">MyHomeLib</a></p></article></main></body></html>";
    }

    public byte[] icon192() throws IOException { return resource("/web-pwa/icon-192.png"); }
    public byte[] icon512() throws IOException { return resource("/web-pwa/icon-512.png"); }

    private static byte[] resource(String path) throws IOException {
        try (InputStream in = WebPwaAssets.class.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing PWA resource: " + path);
            return in.readAllBytes();
        }
    }

    private static String json(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
