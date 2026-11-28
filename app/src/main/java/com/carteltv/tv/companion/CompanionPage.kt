package com.carteltv.tv.companion

/**
 * The phone page served by [PhoneCompanionServer]. Thumb-first: three bottom tabs
 * (Remote, Type, Setup), 48px+ targets, 16px inputs so iOS does not zoom, safe-area insets, reduced
 * motion respected, glass only on the tab bar. `?m=type` opens straight on the Type tab with the
 * keyboard up, which is what the QR on a TV text field uses. Contains no dollar signs: it is a Kotlin raw string.
 */
internal const val COMPANION_HTML = """<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<meta name="color-scheme" content="light"><meta name="theme-color" content="#F5F5F7">
<title>CartelTV</title>
<style>
:root{--bg:#F5F5F7;--s1:#FFFFFF;--s2:#F0F0F3;--s3:#E5E5EA;--line:#D8D8DD;--tx:#111113;--tx2:#5C5C61;--ac:#B8923F;--ac2:#8F6B1F;--ok:#1E7A3C;--bad:#C23B2E;--r:18px;--bar:72px}
*{box-sizing:border-box;-webkit-tap-highlight-color:transparent}
html{background:var(--bg)}
body{margin:0;background:radial-gradient(120% 60% at 50% -10%,#FFFFFF 0,var(--bg) 60%) no-repeat,var(--bg);color:var(--tx);font:16px/1.4 system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;min-height:100dvh;padding:calc(env(safe-area-inset-top) + 12px) 16px calc(var(--bar) + env(safe-area-inset-bottom) + 24px)}
main{max-width:520px;margin:0 auto}
header{display:flex;align-items:center;gap:12px;padding:6px 2px 18px}
.logo{width:36px;height:36px;border-radius:11px;background:linear-gradient(145deg,var(--ac2),var(--ac));display:grid;place-items:center;color:#0A0E0C;font-weight:800}
header h1{font-size:18px;margin:0;letter-spacing:.02em}
header small{display:block;color:var(--tx2);font-size:12px}
.link{margin-left:auto;display:flex;align-items:center;gap:6px;font-size:12px;color:var(--tx2);background:var(--s1);border:1px solid var(--line);padding:6px 10px;border-radius:99px}
.link i{width:8px;height:8px;border-radius:50%;background:var(--ok)}
.link.off i{background:var(--bad)}
section{display:none}section.on{display:block}
@media(prefers-reduced-motion:no-preference){section.on{animation:in .22s ease-out}@keyframes in{from{opacity:0;transform:translateY(8px)}}}
h2{font-size:13px;font-weight:600;letter-spacing:.09em;text-transform:uppercase;color:var(--tx2);margin:22px 4px 10px}
.card{background:var(--s1);border:1px solid var(--line);border-radius:var(--r);padding:14px}
label{display:block;font-size:13px;color:var(--tx2);margin:12px 2px 6px}
input,textarea{width:100%;min-height:48px;background:var(--s2);border:1px solid var(--line);border-radius:14px;color:var(--tx);padding:12px 14px;font:inherit;font-size:16px;outline:none}
input:focus,textarea:focus{border-color:var(--ac);box-shadow:0 0 0 3px #B8923F33}
.row{display:flex;gap:10px;margin-top:12px}
.row>*{flex:1}
button{min-height:48px;border:1px solid var(--line);background:var(--s2);color:var(--tx);border-radius:14px;padding:0 16px;font:inherit;font-weight:600;cursor:pointer;touch-action:manipulation;transition:transform .08s,background .15s}
button:active{transform:scale(.96);background:var(--s3)}
button.pri{background:linear-gradient(145deg,var(--ac2),var(--ac));color:#0A0E0C;border:0}
button.pri:active{filter:brightness(.92)}
.chips{display:flex;flex-wrap:wrap;gap:8px;margin-top:12px}
.chips button{min-height:44px;border-radius:99px;font-weight:500;font-size:14px}
.pad{position:relative;width:min(100%,300px);aspect-ratio:1;margin:8px auto 4px;border-radius:50%;background:var(--s1);border:1px solid var(--line);box-shadow:inset 0 0 40px #0000001A}
.pad button{position:absolute;width:30%;height:30%;min-height:0;border:0;background:transparent;border-radius:50%;font-size:26px;color:var(--tx2)}
.pad button:active{background:var(--s3);color:var(--ac2)}
#k-up{top:4%;left:35%}#k-down{bottom:4%;left:35%}#k-left{left:4%;top:35%}#k-right{right:4%;top:35%}
#k-ok{top:31%;left:31%;width:38%;height:38%;background:linear-gradient(145deg,var(--ac2),var(--ac));color:#0A0E0C;font-size:18px;font-weight:800}
#k-ok:active{background:var(--ac);color:#0A0E0C}
.grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:14px}
.big{min-height:64px;font-size:22px}
details{background:var(--s1);border:1px solid var(--line);border-radius:var(--r);margin:10px 0;overflow:hidden}
summary{list-style:none;min-height:56px;display:flex;align-items:center;gap:10px;padding:0 16px;font-weight:600;cursor:pointer}
summary::-webkit-details-marker{display:none}
summary::after{content:"\203A";margin-left:auto;color:var(--tx2);font-size:22px;transition:transform .2s}
details[open] summary::after{transform:rotate(90deg)}
details .in{padding:0 16px 16px}
.tag{font-size:11px;font-weight:600;padding:3px 8px;border-radius:99px;background:var(--s3);color:var(--tx2)}
.tag.set{background:#1E7A3C22;color:var(--ok)}
.hint{color:var(--tx2);font-size:13px;margin:4px 2px 0}
nav{position:fixed;left:0;right:0;bottom:0;display:flex;justify-content:center;padding:10px 16px calc(10px + env(safe-area-inset-bottom));background:#F5F5F7CC;-webkit-backdrop-filter:blur(18px);backdrop-filter:blur(18px);border-top:1px solid var(--line)}
nav div{display:flex;gap:6px;width:100%;max-width:520px}
nav button{flex:1;height:52px;border:0;background:transparent;color:var(--tx2);display:flex;flex-direction:column;align-items:center;justify-content:center;gap:2px;font-size:12px;border-radius:16px}
nav button svg{width:22px;height:22px;stroke:currentColor;fill:none;stroke-width:1.8;stroke-linecap:round;stroke-linejoin:round}
nav button.on{color:var(--ac2);background:var(--s2)}
#toast{position:fixed;left:50%;bottom:calc(var(--bar) + env(safe-area-inset-bottom) + 14px);transform:translate(-50%,20px);max-width:90vw;background:var(--s3);border:1px solid var(--line);color:var(--tx);padding:12px 18px;border-radius:99px;font-size:14px;opacity:0;pointer-events:none;transition:opacity .2s,transform .2s}
#toast.show{opacity:1;transform:translate(-50%,0)}#toast.bad{color:var(--bad)}
.ty{font-size:20px;margin:6px 2px 2px;font-weight:700}
</style></head><body><main>
<header><div class="logo">C</div><div><h1>CartelTV</h1><small id="dev">Connecting…</small></div><div class="link" id="link"><i></i><span>Linked</span></div></header>

<section id="remote" class="on">
<div class="card"><label for="cmd" style="margin-top:0">Say or type a command</label>
<input id="cmd" enterkeyhint="send" autocomplete="off" autocapitalize="off" placeholder="Open YouTube, pause, turn on the lights">
<div class="row"><button class="pri" id="send">Send</button><button id="mic">Speak</button></div></div>
<h2>Remote</h2>
<div class="pad"><button id="k-up" aria-label="Up">&#9650;</button><button id="k-left" aria-label="Left">&#9664;</button><button id="k-ok">OK</button><button id="k-right" aria-label="Right">&#9654;</button><button id="k-down" aria-label="Down">&#9660;</button></div>
<div class="grid"><button data-k="back">Back</button><button data-c="home">Home</button><button data-c="mute">Mute</button>
<button class="big" data-c="volume down">&minus;</button><button class="big" data-c="play">&#9654;</button><button class="big" data-c="volume up">+</button></div>
<div class="chips"><button data-c="pause">Pause</button><button data-c="live tv">Live TV</button><button data-c="sports">Sports</button><button data-c="sleep in 30 minutes">Sleep 30 min</button></div>
</section>

<section id="type">
<div class="ty">Type on TV</div><p class="hint">Whatever you type here appears in the TV field that is selected. Passwords and keys stay on your home network.</p>
<div class="card" style="margin-top:14px"><label for="live" style="margin-top:0">Text for the TV</label>
<input id="live" enterkeyhint="done" autocomplete="off" autocapitalize="off" autocorrect="off" spellcheck="false" placeholder="Type or paste here">
<div class="row"><button data-k="backspace">Delete</button><button class="pri" data-k="enter">Done</button></div></div>
</section>

<section id="setup">
<h2>Accounts and keys</h2>
<details id="d-xt"><summary>Provider login <span class="tag" id="t-xt">Not set</span></summary><div class="in">
<label for="xtHost">Server URL</label><input id="xtHost" inputmode="url" autocapitalize="off" placeholder="http://example.com:8080" // bughunt:ignore kt-cleartext-http>
<label for="xtUser">Username</label><input id="xtUser" autocapitalize="off" autocomplete="username">
<label for="xtPass">Password</label><input id="xtPass" type="password" autocomplete="current-password">
<div class="row"><button class="pri" id="saveXt">Save and sign in</button></div></div></details>
<details id="d-keys"><summary>API keys and tokens <span class="tag" id="t-keys"></span></summary><div class="in">
<p class="hint">Saved keys are never shown again. Leave a box empty to keep what is there.</p>
<label for="tmdb">TMDB key <span class="tag" id="t-tmdb"></span></label><input id="tmdb" type="password" autocapitalize="off">
<label for="debrid">Real-Debrid token <span class="tag" id="t-debrid"></span></label><input id="debrid" type="password" autocapitalize="off">
<label for="torbox">TorBox token <span class="tag" id="t-torbox"></span></label><input id="torbox" type="password" autocapitalize="off">
<label for="simkl">Simkl client ID <span class="tag" id="t-simkl"></span></label><input id="simkl" type="password" autocapitalize="off">
<label for="traktCid">Trakt client ID <span class="tag" id="t-trakt"></span></label><input id="traktCid" type="password" autocapitalize="off">
<label for="traktSec">Trakt client secret</label><input id="traktSec" type="password" autocapitalize="off">
<div class="row"><button class="pri" id="saveKeys">Save keys</button></div></div></details>
<details><summary>Stremio add-on</summary><div class="in">
<label for="addon">Manifest URL</label><input id="addon" inputmode="url" autocapitalize="off" placeholder="https://example.com/manifest.json">
<div class="row"><button class="pri" id="addAddon">Install add-on</button></div></div></details>
<details><summary>Backup and restore</summary><div class="in">
<label for="code">Configuration code</label><textarea id="code" rows="4" autocapitalize="off" spellcheck="false" placeholder="Paste a CartelTV code to import"></textarea>
<div class="row"><button id="exp">Export from TV</button><button class="pri" id="imp">Import to TV</button></div></div></details>
</section>

<div id="toast" role="status" aria-live="polite"></div>
</main>
<nav><div>
<button data-tab="remote" class="on"><svg viewBox="0 0 24 24"><rect x="7" y="2" width="10" height="20" rx="3"/><circle cx="12" cy="9" r="2"/><path d="M10 16h4"/></svg>Remote</button>
<button data-tab="type"><svg viewBox="0 0 24 24"><rect x="2" y="6" width="20" height="12" rx="3"/><path d="M6 10h.01M10 10h.01M14 10h.01M18 10h.01M7 14h10"/></svg>Type</button>
<button data-tab="setup"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="3"/><path d="M19 12a7 7 0 0 0-.1-1.2l2-1.5-2-3.4-2.3 1a7 7 0 0 0-2-1.2L14 3h-4l-.6 2.7a7 7 0 0 0-2 1.2l-2.3-1-2 3.4 2 1.5A7 7 0 0 0 5 12c0 .4 0 .8.1 1.2l-2 1.5 2 3.4 2.3-1a7 7 0 0 0 2 1.2L10 21h4l.6-2.7a7 7 0 0 0 2-1.2l2.3 1 2-3.4-2-1.5c.1-.4.1-.8.1-1.2z"/></svg>Setup</button>
</div></nav>
<script>
var P=new URLSearchParams(location.search),tok=P.get('t')||'',mode=P.get('m')||'';
history.replaceState(null,'',location.pathname);
var g=function(i){return document.getElementById(i);};
var tt=0;
function toast(m,bad){var t=g('toast');t.textContent=m;t.className='show'+(bad?' bad':'');clearTimeout(tt);tt=setTimeout(function(){t.className='';},2600);}
function buzz(){if(navigator.vibrate)navigator.vibrate(8);}
function setLink(ok){g('link').className='link'+(ok?'':' off');g('link').lastChild.textContent=ok?'Linked':'Offline';}
async function call(p,b){
var h={'X-Cartel-Token':tok};if(b)h['Content-Type']='application/json';
try{var r=await fetch(p,b?{method:'POST',headers:h,body:JSON.stringify(b)}:{headers:h});setLink(true);return await r.json();}
catch(e){setLink(false);return{ok:false,error:'Lost the TV. Check Wi-Fi.'};}}
function done(r,ok){toast(r.message||r.error||ok||'Done',!r.ok);return r.ok;}
function tab(n){['remote','type','setup'].forEach(function(s){g(s).className=s===n?'on':'';});
document.querySelectorAll('nav button').forEach(function(b){b.className=b.dataset.tab===n?'on':'';});
if(n==='type')setTimeout(function(){g('live').focus();},60);}
document.querySelectorAll('nav button').forEach(function(b){b.onclick=function(){buzz();tab(b.dataset.tab);};});
function key(k){buzz();call('/api/key',{key:k}).then(function(r){if(!r.ok)toast(r.error,true);});}
function cmd(t){if(!t||!t.trim())return;buzz();call('/api/voice',{text:t}).then(function(r){done(r);});}
document.querySelectorAll('[data-k]').forEach(function(b){b.onclick=function(){key(b.dataset.k);};});
document.querySelectorAll('[data-c]').forEach(function(b){b.onclick=function(){cmd(b.dataset.c);};});
function hold(id,k){var b=g(id),t=null;
function stop(){if(t){clearInterval(t);t=null;}}
b.addEventListener('pointerdown',function(e){e.preventDefault();key(k);t=setInterval(function(){key(k);},110);});
['pointerup','pointerleave','pointercancel'].forEach(function(ev){b.addEventListener(ev,stop);});}
hold('k-up','up');hold('k-down','down');hold('k-left','left');hold('k-right','right');hold('k-ok','ok');
g('send').onclick=function(){cmd(g('cmd').value);g('cmd').value='';};
g('cmd').onkeydown=function(e){if(e.key==='Enter'){g('send').onclick();}};
g('mic').onclick=function(){var SR=window.SpeechRecognition||window.webkitSpeechRecognition;
if(!SR||!window.isSecureContext){toast('Use the mic key on your keyboard instead.',true);g('cmd').focus();return;}
var r=new SR();r.lang='en-US';r.onresult=function(e){var s=e.results[0][0].transcript;g('cmd').value=s;cmd(s);};r.onerror=function(e){toast('Mic: '+e.error,true);};r.start();toast('Listening…');};
var prev='';
g('live').addEventListener('input',function(){var v=this.value,i=0;
while(i<prev.length&&i<v.length&&prev[i]===v[i])i++;
var del=prev.length-i,add=v.slice(i);prev=v;
call('/api/type',{del:del,text:add}).then(function(r){if(!r.ok)toast(r.error,true);});});
g('live').onkeydown=function(e){if(e.key==='Enter'){key('enter');}};
g('saveXt').onclick=async function(){var r=await call('/api/xtream',{host:g('xtHost').value,user:g('xtUser').value,pass:g('xtPass').value});
if(done(r)){g('xtHost').value='';g('xtUser').value='';g('xtPass').value='';load();}};
g('saveKeys').onclick=async function(){var r=await call('/api/keys',{tmdb:g('tmdb').value,debrid:g('debrid').value,torbox:g('torbox').value,simklClientId:g('simkl').value,traktClientId:g('traktCid').value,traktClientSecret:g('traktSec').value});
if(done(r)){['tmdb','debrid','torbox','simkl','traktCid','traktSec'].forEach(function(i){g(i).value='';});load();}};
g('addAddon').onclick=async function(){var r=await call('/api/addon/add',{url:g('addon').value});if(done(r))g('addon').value='';};
g('exp').onclick=async function(){var r=await call('/api/export');g('code').value=r.code||'';toast('Exported current setup.');};
g('imp').onclick=async function(){done(await call('/api/import',{code:g('code').value}));};
function tag(i,on){var e=g(i);e.textContent=on?'Set':'Not set';e.className='tag'+(on?' set':'');}
async function load(){var s=await call('/api/state');if(!s||s.device===undefined)return;
g('dev').textContent=s.device;tag('t-xt',s.xtreamSet);tag('t-tmdb',s.tmdbSet);tag('t-debrid',s.debridSet);tag('t-torbox',s.torboxSet);tag('t-simkl',s.simklSet);tag('t-trakt',s.traktSet);
g('t-keys').textContent=[s.tmdbSet,s.debridSet,s.torboxSet,s.simklSet,s.traktSet].filter(Boolean).length+' set';}
load();
if(mode==='type')tab('type');
</script></body></html>"""
