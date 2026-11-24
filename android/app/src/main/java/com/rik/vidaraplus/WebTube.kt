package com.rik.vidaraplus

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.MotionEvent
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.app.AlertDialog
import android.widget.TextView
import android.widget.EditText
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import java.io.ByteArrayInputStream

/**
 * Ad-free YouTube / YouTube Music inside the app.
 * It runs the real YouTube web player in a WebView on the phone's own connection, so the player
 * itself handles YouTube's protection (SABR, PO tokens) - no server, no yt-dlp.
 * Ads are removed by filtering network requests, stripping ad fields from responses and a small
 * skip script. Audio keeps playing with the screen off.
 */
object WebTube {
    private val logBuf = ArrayDeque<String>()

    @Volatile var logFile: java.io.File? = null
    fun log(msg: String) {
        synchronized(logBuf) {
            val line = (android.os.SystemClock.elapsedRealtime() % 1000000).toString().padStart(6, ' ') + " " + msg
            runCatching { logFile?.let { f -> if (f.length() > 3_000_000) f.writeText(""); f.appendText(line + "\n") } }
            logBuf.addLast(line)
            while (logBuf.size > 250) logBuf.removeFirst()
        }
    }

    fun logText(): String = synchronized(logBuf) { logBuf.joinToString("\n") }
    fun clearLog() = synchronized(logBuf) { logBuf.clear(); dumped = 0 }

    // a még ki nem írt sorok (a logcatbe folyamatosan kiírjuk, így fagyásnál is látszik, mi történt)
    private var dumped = 0
    fun drainNew(): String = synchronized(logBuf) {
        val list = logBuf.toList()
        if (list.size <= dumped) return ""
        val out = list.subList(dumped, list.size).joinToString("\n")
        dumped = list.size
        out
    }

    const val YT = "https://m.youtube.com/"
    const val MUSIC = "https://music.youtube.com/"

    fun watchUrl(id: String, music: Boolean) = (if (music) MUSIC else YT) + "watch?v=" + id

    val BLOCK_HOSTS = listOf(
        "doubleclick.net", "googlesyndication.com", "googleadservices.com",
        "adservice.google.com", "pagead2.googlesyndication.com", "2mdn.net",
        "google-analytics.com", "googletagmanager.com", "googletagservices.com"
    )
    val OK_HOSTS = listOf(
        "youtube.com", "google.com", "gstatic.com", "ytimg.com", "googlevideo.com",
        "googleapis.com", "ggpht.com", "googleusercontent.com", "youtube-nocookie.com"
    )

        // Ez fut az oldal betöltése előtt (és a videóváltások közben is él).
    val JS = """
(function(){
if(window.__vdOn)return;window.__vdOn=true;
// csak H.264 (hardveres dekódolás: gyorsabb indulás, nincs szaggatás a gyengébb telefonon)
try{
  var hq=false;try{hq=localStorage.getItem('vd_hq')==='1';}catch(e){}
  var bad=hq?/^\b$/:/vp09|vp9|av01|av1/i;
  if(window.MediaSource&&MediaSource.isTypeSupported){var ict=MediaSource.isTypeSupported.bind(MediaSource);MediaSource.isTypeSupported=function(t){return bad.test(t)?false:ict(t);};}
  var cpt=HTMLMediaElement.prototype.canPlayType;HTMLMediaElement.prototype.canPlayType=function(t){return bad.test(t)?'':cpt.call(this,t);};
}catch(e){}
window.__vdCap='hd1080';window.__vdMode='';window.__vdTouch=0;
function L(m){try{VidaraApp.log(m);}catch(e){}}
// saját "teljes képernyő" (nincs Android-os teljes képernyős nézet: az fekete képet hagyott maga után)
(function(){
  var fsEl=null;
  function fire(){try{document.dispatchEvent(new Event('fullscreenchange'));document.dispatchEvent(new Event('webkitfullscreenchange'));}catch(e){}}
  function req(){if(Date.now()-window.__vdTouch>2000){L('fullscreen kérés elutasítva (nem te nyomtad)');return Promise.reject(new Error('no gesture'));}fsEl=this;try{VidaraApp.fs(true);}catch(e){}setTimeout(fire,0);return Promise.resolve();}
  function ex(){fsEl=null;try{VidaraApp.fs(false);}catch(e){}setTimeout(fire,0);return Promise.resolve();}
  try{
    Element.prototype.requestFullscreen=req;Element.prototype.webkitRequestFullscreen=req;
    document.exitFullscreen=ex;document.webkitExitFullscreen=ex;
    ['fullscreenElement','webkitFullscreenElement','webkitCurrentFullScreenElement'].forEach(function(n){Object.defineProperty(document,n,{configurable:true,get:function(){return fsEl;}});});
    ['fullscreenEnabled','webkitFullscreenEnabled'].forEach(function(n){Object.defineProperty(document,n,{configurable:true,get:function(){return true;}});});
    window.__vdExitFs=function(){if(fsEl)ex();};
  }catch(e){}
})();
// a lejátszó rögzítése a teljes nézetre (kis ablak, PiP, teljes képernyő) – elemnevektől független
window.__vdApply=function(){
  var m=window.__vdMode,v=null,pl=null;
  try{
    v=pickVideo();
    // Shorts / zenés lejátszó: a videó nem feltétlenül .html5-video-player alatt van
    pl=v&&v.closest?(v.closest('.html5-video-player')||v.closest('#player-container-id')||v.closest('ytm-reel-player')||v.closest('ytm-shorts-player')||v.parentElement):null;
    // VidaraMusic: az egész zenei lejátszó (videó VAGY borítókép – amelyik épp látszik) kerül a kis ablakba
    if(location.hostname==='music.youtube.com'&&v)pl=v;   // VidaraMusic: magát a videót rögzítjük (a lejátszó elrendezése levágta)
  }catch(e){}
  var de=document.documentElement;
  de.classList.toggle('vd-mini',m==='mini');de.classList.toggle('vd-full',m==='full');
  var fixIt=(m==='full');
  var olds=document.querySelectorAll('.vd-fixed');
  for(var i=0;i<olds.length;i++){if(!fixIt||olds[i]!==pl){olds[i].classList.remove('vd-fixed');}}
  if(fixIt&&pl){
    if(!pl.classList.contains('vd-fixed')){
      // a helye megmarad (különben a YouTube azt hinné, hogy a lejátszó eltűnt, és megállítja)
      var par=pl.parentElement;
      if(par){window.__vdPH=par;par.style.minHeight=par.offsetHeight+'px';}
      pl.classList.add('vd-fixed');
      for(var p=pl.parentElement;p&&p!==de;p=p.parentElement)p.classList.add('vd-anc');
    }
  }else if(!fixIt){
    var an=document.querySelectorAll('.vd-anc');for(var j=0;j<an.length;j++)an[j].classList.remove('vd-anc');
    if(window.__vdPH){try{window.__vdPH.style.minHeight='';}catch(e){}window.__vdPH=null;}
  }
  if(m==='mini'){try{window.scrollTo(0,0);}catch(e){}}
  try{window.__vdMusicArt&&window.__vdMusicArt();}catch(e){}
  if(window.__vdForce){window.__vdForce=false;try{window.dispatchEvent(new Event('resize'));}catch(e){}}
  // rendes nézetben semmi ne maradjon rögzítve/elrejtve: a görgetés és a koppintás mindig működjön
  if(!m){try{if(!document.querySelector('[role="dialog"],ytm-bottom-sheet-renderer,bottom-sheet-layout,.bottom-sheet-layout')){de.style.overflow='';if(document.body)document.body.style.overflow='';}}catch(e){}}
};
// ---- Lezárt képernyős médiavezérlő: állapot jelentése + a gombok végrehajtása
window.__vdMedia=function(c,a){
  try{
    var v=pickVideo(),pl=document.querySelector('.html5-video-player'),music=location.hostname==='music.youtube.com';
    if(c==='play'){if(v){window.__vdUP=false;v.play();}}
    else if(c==='pause'){if(v){window.__vdUP=true;v.pause();}}
    else if(c==='seek'){if(v)v.currentTime=Math.max(0,a/1000);}
    else if(c==='next'){
      if(music){var nb=document.querySelector('ytmusic-player-bar .next-button');if(nb)nb.click();else if(pl&&pl.nextVideo)pl.nextVideo();}
      else if(/^\/shorts/.test(location.pathname)){window.scrollBy({top:window.innerHeight,behavior:'smooth'});}
      else if(/[?&]list=/.test(location.search)&&pl&&pl.nextVideo){pl.nextVideo();}
      else{var l=document.querySelector('ytm-compact-video-renderer a[href*="watch"],ytm-video-with-context-renderer a[href*="watch"],ytm-compact-autoplay-renderer a[href*="watch"]');if(l){if(window.__vdGo)window.__vdGo(l.href);else l.click();}}
    }
    else if(c==='prev'){
      if(v&&v.currentTime>5){v.currentTime=0;}
      else if(music){var pb=document.querySelector('ytmusic-player-bar .previous-button');if(pb)pb.click();else if(pl&&pl.previousVideo)pl.previousVideo();}
      else if(/^\/shorts/.test(location.pathname)){window.scrollBy({top:-window.innerHeight,behavior:'smooth'});}
      else if(/[?&]list=/.test(location.search)&&pl&&pl.previousVideo){pl.previousVideo();}
      else{history.back();}
    }
    setTimeout(function(){window.__vdMediaReport(true);},400);
  }catch(e){}
};
window.__vdMediaReport=function(force){
  try{
    if(window.__vdQuiet)return;
    var p=location.pathname||'',music=location.hostname==='music.youtube.com';
    var v=pickVideo(),on=!!v&&(music||/^\/(watch|shorts)/.test(p));
    if(!on){if(window.__vdMS!=='none'){window.__vdMS='none';try{VidaraApp.media('','','',0,0,false);}catch(e){}}return;}
    var pl=document.querySelector('.html5-video-player'),gd=null;try{gd=pl&&pl.getVideoData?pl.getVideoData():null;}catch(e){}
    if(pl&&(pl.classList.contains('ad-showing')||pl.classList.contains('ad-interrupting')))return;
    var t=(gd&&gd.title)||'',a=(gd&&gd.author)||'',id=(gd&&gd.video_id)||'';
    if(music){var mt=document.querySelector('ytmusic-player-bar .title');if(!t&&mt)t=(mt.textContent||'').trim();var mb=document.querySelector('ytmusic-player-bar .byline');if(!a&&mb)a=(mb.textContent||'').split('•')[0].trim();}
    if(!t)t=(document.title||'').replace(/\s*[-–]\s*(YouTube|Vidara)[\s\S]*$/,'');
    if(!id){try{id=new URL(location.href).searchParams.get('v')||'';}catch(e){}}
    if(!id){var sm=p.match(/^\/shorts\/([A-Za-z0-9_-]+)/);if(sm)id=sm[1];}
    if(music&&id&&window.__vdArtId!==id){window.__vdArtId=id;try{document.documentElement.style.setProperty('--vd-art','url(https://i.ytimg.com/vi/'+id+'/default.jpg)');}catch(e){}}
    var d=isFinite(v.duration)?Math.round(v.duration*1000):0,pos=Math.round((v.currentTime||0)*1000),pg=!v.paused&&!v.ended;
    var key=[t,a,id,d,pg].join('|'),now=Date.now();
    if(force||key!==window.__vdMS||now-(window.__vdMST||0)>5000){window.__vdMS=key;window.__vdMST=now;try{VidaraApp.media(t,a,id,d,pos,pg);}catch(e){}}
  }catch(e){}
};
setInterval(function(){window.__vdMediaReport(false);},1000);
['play','pause','ended','seeked'].forEach(function(n){document.addEventListener(n,function(){setTimeout(function(){window.__vdMediaReport(true);},60);},true);});
// VidaraMusic PiP / kis ablak: SAJÁT réteg a lap tetején, ami nem függ a VidaraMusic elrendezésétől.
// Van videókép -> a képkockákat erre a rétegre rajzoljuk; csak hang -> a dal borítóképe. A YouTube elemeihez NEM nyúlunk.
window.__vdMusicArt=function(){
  if(location.hostname!=='music.youtube.com')return;
  var o=document.getElementById('vd-art');if(o)o.style.display='none';
  var v=pickVideo(),has=false;
  try{
    if(v&&v.videoWidth>0&&v.readyState>=2){
      var cs=getComputedStyle(v);
      has=cs.display!=='none'&&parseFloat(cs.opacity||'1')>0.05;
      // a lejátszó saját "csak hang" állapota (rejtett videó-doboz)
      var sv=v.closest('#song-video');if(sv){var c2=getComputedStyle(sv);if(c2.display==='none')has=false;}
    }
  }catch(e){}
  if(window.__vdHasV!==has){window.__vdHasV=has;try{VidaraApp.musicVideo(has);}catch(e){}}
  // PiP: a lejátszó (videó vagy borító) helye a lapon – a natív oldal erre nagyít rá
  if(window.__vdSmall){
    var r=null;
    if(has){try{var b=v.getBoundingClientRect(),ar=v.videoWidth/v.videoHeight;
      if(b.width>60&&b.height>40&&ar>0){var cw=b.width,ch=b.width/ar;if(ch>b.height){ch=b.height;cw=b.height*ar;}
        r={left:b.left+(b.width-cw)/2,top:b.top+(b.height-ch)/2,width:cw,height:ch,bottom:b.top+(b.height+ch)/2};}}catch(e){}}
    try{var im=document.querySelector('ytmusic-player #song-image img,ytmusic-player #thumbnail img');var src=im&&im.src||'';
      if(src)src=src.replace(/=w\d+-h\d+[^&?]*/,'=w720-h720-l90-rj');
      if(src!==window.__vdArtSrc){window.__vdArtSrc=src;try{VidaraApp.musicArt(src);}catch(e){}}}catch(e){}
    var ok=r&&r.width>80&&r.height>40&&r.bottom>0&&r.top<window.innerHeight;
    var key=ok?[Math.round(r.left),Math.round(r.top),Math.round(r.width),Math.round(r.height)].join(','):'0';
    if(window.__vdMR!==key){window.__vdMR=key;try{if(ok)VidaraApp.musicRect(r.left,r.top,r.width,r.height);else VidaraApp.musicRect(0,0,0,0);}catch(e){}}
  }else window.__vdMR=null;
};
setInterval(function(){try{if(document.hidden)return;window.__vdMusicArt();}catch(e){}},1200);
setInterval(function(){try{if(!document.hidden&&window.__vdSmall)window.__vdMusicArt();}catch(e){}},600);
// PiP-ből visszatérve a WebView néha nem rajzolja újra a VidaraMusic fülek szövegét (a DOM jó, a réteg marad elavult).
// A sáv display:none -> visszaállítás kényszerített újrarajzolása ilyenkor helyrehozza.
window.__vdFixTabs=function(){
  try{
    var l=document.querySelectorAll('tp-yt-paper-tabs');
    for(var i=0;i<l.length;i++){var t=l[i],d=t.style.display;t.style.display='none';void t.offsetHeight;t.style.display=d;}
  }catch(e){}
};
if(!window.__vdGearOn){window.__vdGearOn=true;
  ['click','touchend','pointerup','mouseup','touchstart','pointerdown','mousedown'].forEach(function(n){window.addEventListener(n,function(e){
    try{
      var t=e.target;if(!t||!t.closest)return;
      var b=t.closest('button,[role="button"],.ytp-button,ytm-button-renderer,c3-icon');
      if(!b)return;
      var sig=(b.className&&b.className.toString?b.className.toString():'')+' '+(b.getAttribute('aria-label')||'')+' '+(b.getAttribute('title')||'');
      if(!/settings|setting|beállít/i.test(sig))return;
      if(!b.closest('.html5-video-player,#player-container-id,ytm-custom-control,.player-controls-top,.player-controls-content'))return;
      e.preventDefault();e.stopImmediatePropagation();e.stopPropagation();
      var de=document.documentElement;if(!de.classList.contains('vd-qopen')){de.classList.add('vd-qopen');clearTimeout(window.__vdQT2);window.__vdQT2=setTimeout(function(){window.__vdQAuto&&window.__vdQAuto();},20000);window.__vdQHide();}
      if(n==='click'||n==='touchend'){if(!window.__vdGT||Date.now()-window.__vdGT>600){window.__vdGT=Date.now();try{VidaraApp.quality();}catch(x){}}}
      window.__vdKillYS();
    }catch(x){}
  },{capture:true,passive:false});});
}
// A YouTube saját (beállítás) lapja és sötét fátyla a fogaskerék után: szövegtől/nyelvtől függetlenül.
// Előbb a fátylra "koppintunk" (így a YouTube maga zárja be a lapot), ami utána is fent maradna, azt levesszük.
window.__vdDiag=function(tag){try{
  var L=function(m){try{VidaraApp.log('DIAG '+tag+' '+m);}catch(e){}};
  var W=innerWidth,H=innerHeight;L('vp='+W+'x'+H+' html='+document.documentElement.className+' body='+document.body.className+' bodyOv='+document.body.style.overflow);
  var d=function(e){if(!e||!e.getBoundingClientRect)return'-';var r=e.getBoundingClientRect(),c=getComputedStyle(e);
    return e.tagName.toLowerCase()+(e.id?'#'+e.id:'')+(e.className&&e.className.toString?'.'+e.className.toString().trim().split(/\s+/).slice(0,4).join('.'):'')+
    ' ['+Math.round(r.left)+','+Math.round(r.top)+' '+Math.round(r.width)+'x'+Math.round(r.height)+'] bg='+c.backgroundColor+' op='+c.opacity+' disp='+c.display+' vis='+c.visibility+' pos='+c.position+' z='+c.zIndex+' st='+(e.getAttribute('style')||'').slice(0,90);};
  [[0.5,0.5],[0.5,0.15],[0.5,0.85]].forEach(function(p){var a=document.elementsFromPoint(W*p[0],H*p[1]);L('pt'+p[1]+': '+a.slice(0,7).map(d).join(' | '));});
  var big=[];document.querySelectorAll('body *').forEach(function(e){if(big.length>12)return;var c=getComputedStyle(e);if(c.position!=='fixed'&&c.position!=='absolute')return;var r=e.getBoundingClientRect();if(r.width>=W*0.9&&r.height>=H*0.6&&c.display!=='none'&&c.visibility!=='hidden'&&parseFloat(c.opacity)>0.02&&c.backgroundColor!=='rgba(0, 0, 0, 0)')big.push(d(e));});
  L('fullcovers: '+big.join(' || '));
  if(location.hostname==='music.youtube.com'){
    var al=document.querySelector('ytmusic-app-layout'),pp=document.querySelector('ytmusic-player-page'),mp=document.querySelector('ytmusic-player');
    var at=function(e){if(!e)return'-';var o=[];for(var i=0;i<e.attributes.length;i++){var a=e.attributes[i];if(a.name!=='style')o.push(a.name+'='+String(a.value).slice(0,40));}return o.join(' ');};
    L('layout: '+at(al));L('playerpage: '+at(pp)+' :: '+d(pp));L('player: '+at(mp)+' :: '+d(mp));
    ['ytmusic-nav-bar','ytmusic-player-page .top-bar','ytmusic-player-page #header','ytmusic-player-page tp-yt-paper-tabs','ytmusic-player-page .side-panel','ytmusic-player-page #main-panel','ytmusic-player-page .player-minimize-button','ytmusic-player-page ytmusic-av-toggle','ytmusic-player-bar','ytmusic-player-page #side-panel','tp-yt-paper-tab .tab-content','tp-yt-paper-tab yt-formatted-string','ytmusic-player .top-row-buttons > :last-child','ytmusic-player .top-row-buttons yt-icon'].forEach(function(q){var e=document.querySelector(q);L('el '+q+': '+(e?d(e):'NONE'));});
    var hid=[];document.querySelectorAll('ytmusic-player-page *,ytmusic-nav-bar *').forEach(function(e){if(hid.length>15)return;var st=e.getAttribute('style')||'';if(/display:\s*none|visibility:\s*hidden|opacity:\s*0[;\s]/.test(st))hid.push(d(e));});
    L('inline-hidden: '+hid.join(' || '));
  }
}catch(e){try{VidaraApp.log('DIAG err '+e);}catch(x){}}};
// VidaraMusic: dal koppintásra azonnal a dal borítója (a lejátszó lap felépülése közben ne fagyott kép látszódjon)
if(!window.__vdMTap){
  window.__vdMTap=1;
  var mtx=0,mty=0,mtt=0;
  document.addEventListener('pointerdown',function(e){try{mtx=e.clientX;mty=e.clientY;mtt=Date.now();}catch(x){}},true);
  document.addEventListener('pointerup',function(e){
    try{
      if(location.hostname!=='music.youtube.com')return;
      if(Date.now()-mtt>900||Math.abs(e.clientX-mtx)>16||Math.abs(e.clientY-mty)>16)return;
      var a=e.target&&e.target.closest?e.target.closest('a[href*="watch?v="]'):null;
      if(!a)return;
      var mm=(a.getAttribute('href')||'').match(/[?&]v=([A-Za-z0-9_-]{6,})/);
      if(!mm)return;
      if(window.__vdMTId===mm[1]&&Date.now()-(window.__vdMTT||0)<2500)return;
      var art='';
      try{
        var box=a.closest('ytmusic-responsive-list-item-renderer,ytmusic-two-row-item-renderer,ytmusic-carousel-shelf-renderer,ytmusic-carousel')||a;
        var im=a.querySelector('img')||(box.querySelector&&box.querySelector('img'));
        if(im){art=(im.currentSrc||im.src||'').replace(/=w\d+-h\d+[^&?]*/,'=w544-h544-l90-rj');}
      }catch(x){}
      window.__vdMTId=mm[1];window.__vdMTT=Date.now();
      try{VidaraApp.musicTap(mm[1],art);}catch(x){}
    }catch(x){}
  },true);
}
window.__vdQClose=function(){try{var o=document.getElementById('vd-qp');if(o)o.remove();}catch(e){}};
// A panel érintéseit az ABLAK kapja el elsőként – teljes képernyőn a YouTube lejátszója különben elnyeli (nincs "click")
if(!window.__vdQEv){window.__vdQEv=1;var qsx=0,qsy=0;
  var inQ=function(t){return t&&t.closest&&t.closest('#vd-qp');};
  window.addEventListener('touchstart',function(e){if(!inQ(e.target))return;var q=e.touches[0];qsx=q.clientX;qsy=q.clientY;e.stopImmediatePropagation();},{capture:true,passive:true});
  window.addEventListener('touchmove',function(e){if(!inQ(e.target))return;e.stopImmediatePropagation();},{capture:true,passive:true});
  ['pointerdown','pointerup','mousedown','mouseup'].forEach(function(n){window.addEventListener(n,function(e){if(inQ(e.target))e.stopImmediatePropagation();},{capture:true,passive:true});});
  ['touchend','click'].forEach(function(n){window.addEventListener(n,function(e){
    var t=e.target;if(!inQ(t))return;
    e.stopImmediatePropagation();
    if(n==='touchend'){var q=e.changedTouches&&e.changedTouches[0];if(q&&(Math.abs(q.clientX-qsx)>12||Math.abs(q.clientY-qsy)>12))return;if(e.cancelable)e.preventDefault();}
    if(window.__vdQDone&&Date.now()-window.__vdQDone<600)return;window.__vdQDone=Date.now();
    var r=t.closest('[data-vdqi]');
    if(r){window.__vdQClose();try{VidaraApp.qPick(+r.getAttribute('data-vdqi'));}catch(x){}}
    else if(!t.closest('#vd-qpi')){window.__vdQClose();try{VidaraApp.qPick(-1);}catch(x){}}
  },{capture:true,passive:false});});
}
// A YouTube saját lapja: ami a fogaskerék után a képernyő közepén/alján rögzítve (fixed) megjelenik, átlátszó lesz – névtől függetlenül
window.__vdQHide=function(){
  var de=document.documentElement;
  [0,60,150,300,500,800,1200].forEach(function(d){setTimeout(function(){try{
    if(!de.classList.contains('vd-qopen'))return;
    var W=innerWidth,H=innerHeight;
    [[0.5,0.92],[0.5,0.8],[0.5,0.6],[0.5,0.4],[0.5,0.2]].forEach(function(pt){
      document.elementsFromPoint(W*pt[0],H*pt[1]).forEach(function(e){
        if(e===document.body||e===de||e.hasAttribute('data-vdq'))return;
        if(e.closest('#vd-qp')||e.closest('.html5-video-player'))return;
        if(e.querySelector&&e.querySelector('video,#vd-qp'))return;
        if(getComputedStyle(e).position!=='fixed')return;
        e.setAttribute('data-vdq','1');e.style.setProperty('opacity','0','important');
      });
    });
  }catch(x){}},d);});
};
window.__vdQShow=function(){try{document.querySelectorAll('[data-vdq]').forEach(function(e){e.style.removeProperty('opacity');e.removeAttribute('data-vdq');});document.documentElement.classList.remove('vd-qopen','vd-qtap');}catch(e){}};
// 20 mp után magától bezárul a panel (és a natív oldal is visszakapja az érintést teljes képernyőn).
window.__vdQAuto=function(){try{if(!document.getElementById('vd-qp')){window.__vdQShow();return;}window.__vdQClose();window.__vdQShow();VidaraApp.qPick(-1);}catch(e){}};
window.__vdQPanel=function(title,items){
  try{
    window.__vdQClose();
    document.documentElement.classList.add('vd-qopen');clearTimeout(window.__vdQT2);window.__vdQT2=setTimeout(function(){window.__vdQAuto&&window.__vdQAuto();},20000);window.__vdQHide();
    var o=document.createElement('div');o.id='vd-qp';
    o.style.cssText='position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:2147483647;background:rgba(0,0,0,.35);display:flex;align-items:center;justify-content:center';
    var p=document.createElement('div');
    p.style.cssText='min-width:240px;max-width:82vw;max-height:72vh;overflow:auto;background:rgba(34,18,64,.97);border:1px solid rgba(196,165,255,.25);border-radius:22px;padding:14px 8px 10px;font:500 15px/1.25 system-ui,Roboto,sans-serif;color:#f4efff;box-shadow:0 18px 50px rgba(0,0,0,.5)';
    var h=document.createElement('div');h.textContent=title;h.style.cssText='font-weight:700;font-size:17px;padding:0 16px 8px;color:#fff';p.appendChild(h);
    p.id='vd-qpi';p.style.webkitOverflowScrolling='touch';p.style.overscrollBehavior='contain';
    items.forEach(function(t,i){var r=document.createElement('div');r.textContent=t;r.setAttribute('data-vdqi',i);r.style.cssText='padding:12px 16px;border-radius:12px';p.appendChild(r);});
    window.__vdQDone=0;
    (document.body||document.documentElement).appendChild(o);o.appendChild(p);
    // Bármelyik útvonalon nyílt meg a panel (fogaskerék, teljes képernyős gomb, observer):
    // a YouTube saját beállítás-lapját ilyenkor zárjuk be, ne maradjon ott második ablakként.
    try{window.__vdKillYS&&window.__vdKillYS();}catch(e){}
  }catch(e){}
};
window.__vdKillYS=function(){
  // A YouTube saját beállítás-lapját NEM csak elrejtjük, hanem be is zárjuk: a saját sötét fátylára
  // koppintva a YouTube maga zárja be. Rövid ideig próbálkozunk, mert a lap néha később épül fel
  // (és a nyitó animáció alatt még nem reagál a koppintásra). A ciklus a panel zárásakor leáll.
  var n=0;
  var once=function(){
    try{var l=document.querySelectorAll('ytw-scrim,[class*="Scrim"],.ytp-scrim'),best=null;
      for(var i=0;i<l.length;i++){var s=l[i],r=s.getBoundingClientRect(),c=getComputedStyle(s);
        if(r.width>60&&r.height>60&&c.display!=='none'&&c.visibility!=='hidden')best=s;}
      if(best)best.click();}catch(e){}
    try{document.querySelectorAll('.ytp-settings-menu').forEach(function(m){try{m.remove();}catch(e){}});}catch(e){}
  };
  clearInterval(window.__vdKST);window.__vdKST=0;
  once();
  window.__vdKST=setInterval(function(){
    // csak a nyitott állapot számít: a panel eleme a híd miatt később jön létre, az első ellenőrzésnél még nincs
    try{if(!document.documentElement.classList.contains('vd-qopen')){clearInterval(window.__vdKST);window.__vdKST=0;return;}}catch(e){}
    once();
    if(++n>20){clearInterval(window.__vdKST);window.__vdKST=0;}
  },250);
};
function vdKillSheet(root){
  try{
    var tx=(root.textContent||'');
    if(tx.indexOf('Stats for nerds')<0&&tx.indexOf('Copy Debug Info')<0&&tx.indexOf('Stable volume')<0)return false;
    var c=root.closest?root.closest('[role="dialog"],ytm-bottom-sheet-renderer,bottom-sheet-layout,.bottom-sheet-layout,ytm-menu-popup-renderer,.player-settings-menu'):null;
    return true;
  }catch(e){return false;}
}
try{
  new MutationObserver(function(ms){
    // olcsó ellenőrzések elöl: a textContent olvasása nagy részfa alatt nagyon drága (fagyás)
    if(!/^\/watch/.test(location.pathname))return;
    if(Date.now()-(window.__vdTouch||0)>=4000)return;
    for(var i=0;i<ms.length;i++){var a=ms[i].addedNodes;
      for(var j=0;j<a.length;j++){var n=a[j];
        if(n.nodeType!==1||n.childElementCount>24)continue;
        var tx=n.textContent;if(!tx||tx.length>4000)continue;
        if(tx.indexOf('Stats for nerds')<0&&tx.indexOf('Copy Debug Info')<0)continue;
        if(vdKillSheet(n)){
          // A YouTube saját lapja itt jelenik meg először: azonnal elrejtjük/bezárjuk, mielőtt kirajzolódna.
          var de=document.documentElement;if(!de.classList.contains('vd-qopen')){de.classList.add('vd-qopen');clearTimeout(window.__vdQT2);window.__vdQT2=setTimeout(function(){window.__vdQAuto&&window.__vdQAuto();},20000);window.__vdQHide();}
          try{window.__vdKillYS&&window.__vdKillYS();}catch(x){}
          if(!window.__vdGT||Date.now()-window.__vdGT>600){window.__vdGT=Date.now();try{VidaraApp.quality();}catch(x){}}return;}
      }}
  }).observe(document.documentElement,{childList:true,subtree:true});
}catch(e){}
try{
  var ops=history.pushState;
  history.pushState=function(){var r=ops.apply(this,arguments);
    try{if(/^\/watch/.test(location.pathname)){window.scrollTo(0,0);VidaraApp.nav(location.href);}else if(/^\/shorts/.test(location.pathname)){VidaraApp.nav(location.href);}}catch(e){}
    return r;};
}catch(e){}
window.__vdInfo=function(){
  try{var v=pickVideo();if(!v)return '0|0|1';return (v.currentTime||0).toFixed(1)+'|'+(isFinite(v.duration)?v.duration:0).toFixed(1)+'|'+(v.paused?0:1);}catch(e){return '0|0|1';}
};
window.__vdSeek=function(t){try{var v=pickVideo();if(v)v.currentTime=t;}catch(e){}};
window.__vdSkip=function(d){try{var v=pickVideo();if(v)v.currentTime=Math.max(0,v.currentTime+d);}catch(e){}};
window.__vdLevels=function(){
  try{var v=pickVideo(),pl=v.closest('.html5-video-player');var hq=false;try{hq=localStorage.getItem('vd_hq')==='1';}catch(e){}return JSON.stringify({l:pl.getAvailableQualityLevels(),c:pl.getPlaybackQuality(),u:window.__vdUQ||'auto',hq:hq});}catch(e){return '{}';}
};
window.__vdHQ=function(on){try{localStorage.setItem('vd_hq',on?'1':'0');}catch(e){}location.reload();};
window.__vdSetQ=function(q){
  window.__vdUQ=q;window.__vdQK='';
  // Csak a sávot állítjuk be: a YouTube a következő darabnál vált, nem dobja el a puffert
  // (a setPlaybackQuality kényszerített újratöltés volt: szürke kép + pörgés + fagyás).
  try{
    var v=pickVideo();var pl=v&&v.closest?v.closest('.html5-video-player'):null;
    if(pl&&pl.setPlaybackQualityRange)pl.setPlaybackQualityRange(q,q);
    if(v){try{v.play();}catch(e){}}
  }catch(e){}
};
// ---- Shorts: azonnali indítás + fekete takaró a videóváltás alatt (nincs szürke YouTube-háttér)
// csak a háttérben előtöltött (még nem látszó) Shorts-lapot tesszük csendbe, minden más lejátszót nem
window.__vdQuiet=false;
window.__vdPlayNow=function(){
  try{
    window.__vdUP=false;window.__vdPlayTries=0;window.__vdStarted=null;
    window.__vdResumeUntil=Date.now()+6000;   // pár mp-ig minden "véletlen" szünetet visszaindítunk
    var v=pickVideo();
    if(v){try{v.preload='auto';}catch(e){}try{v.play();}catch(e){}}
  }catch(e){}
};
// a Shorts-fül aktív: innentől indulhat a lejátszás
window.__vdActive=function(){window.__vdQuiet=false;window.__vdPlayNow();};
// elhagytuk a Shorts-fület: csendben marad a háttérben betöltött lap
window.__vdIdle=function(){
  window.__vdQuiet=true;
  try{document.querySelectorAll('video').forEach(function(v){try{v.pause();}catch(e){}});}catch(e){}
};
(function(){
  function reel(){return /^\/shorts/.test(location.pathname);}
  function sig(on){try{VidaraApp.reel(on);}catch(x){}}
  // új videó töltődik -> fekete takaró; amint megy a kép -> takaró el
  document.addEventListener('loadstart',function(e){var v=e.target;if(v&&v.tagName==='VIDEO'&&reel())sig(1);},true);
  document.addEventListener('emptied',function(e){var v=e.target;if(v&&v.tagName==='VIDEO'&&reel())sig(1);},true);
  document.addEventListener('playing',function(e){var v=e.target;if(v&&v.tagName==='VIDEO'&&reel())sig(0);},true);
  document.addEventListener('seeked',function(e){var v=e.target;if(v&&v.tagName==='VIDEO'&&reel())sig(0);},true);
  // a takaró azonnal eltűnik, ha a videó TÉNYLEG megy (függetlenül a lap geometriájától)
  function started(v){
    if(!v||v.tagName!=='VIDEO')return;
    if(!/^\/watch|^\/shorts/.test(location.pathname))return;
    if(v.paused||v.currentTime<=0.10||v.readyState<3)return;
    if(window.__vdPL===location.href)return;
    window.__vdPL=location.href;
    try{window.__vdUnfreeze&&window.__vdUnfreeze();}catch(x){}
    try{VidaraApp.playing();}catch(x){}
  }
  document.addEventListener('playing',function(e){started(e.target);},true);
  document.addEventListener('timeupdate',function(e){started(e.target);},true);
})();
window.__vdSetMode=function(m){
  var was=window.__vdMode;window.__vdMode=m;window.__vdForce=true;window.__vdApply();
  if(was&&!m){
    // a videó újrarajzoltatása (különben néha álló kép marad)
    [60,400].forEach(function(d){setTimeout(function(){try{var v=pickVideo();if(v){var o=v.style.display;v.style.display='none';void v.offsetHeight;v.style.display=o;window.dispatchEvent(new Event('resize'));}}catch(e){}},d);});
  }
};
// eseménynapló (hibakereséshez)
['play','pause','playing','waiting','stalled','error','ended','emptied','loadstart','canplay'].forEach(function(n){
  document.addEventListener(n,function(e){var v=e.target;if(!v||v.tagName!=='VIDEO')return;
    L(n+' t='+(v.currentTime||0).toFixed(1)+' rs='+v.readyState+' mode='+window.__vdMode+' touch='+Math.min(99999,Date.now()-window.__vdTouch)+' up='+(window.__vdUP?1:0)+(v.error?' err='+v.error.code:''));},true);
});
// oldalon belüli navigáció (nem tölti újra az egész YouTube-ot)
window.__vdGo=function(u){
  try{
    window.scrollTo(0,0);
    try{window.__vdLoadOn&&window.__vdLoadOn(u);}catch(x){}
    // FONTOS: a régi videót NEM állítjuk le itt – attól a YouTube "te szüneteltetted" állapotba került,
    // és az új videó sosem indult el magától (fekete kép).
    var a=document.createElement('a');a.href=u;a.style.display='none';document.body.appendChild(a);
    var before=location.href;
    var ok=a.dispatchEvent(new MouseEvent('click',{bubbles:true,cancelable:true,view:window}));
    // új videó: az autóindítás újra engedélyezett
    window.__vdUP=false;window.__vdPlayTries=0;window.__vdStarted=null;
    setTimeout(function(){try{a.remove();}catch(e){}if(!ok&&location.href===before)location.assign(u);},1500);
  }catch(e){location.assign(u);}
};
var AD=/^(adSlotRenderer|promotedSparklesWebRenderer|promotedVideoRenderer|searchPyvRenderer|compactPromotedVideoRenderer|displayAdRenderer|bannerPromoRenderer|statementBannerRenderer|inFeedAdLayoutRenderer|videoMastheadAdV3Renderer|primetimePromoRenderer|mealbarPromoRenderer|reelAdRenderer|adBreakServiceRenderer)${'$'}/;
function isAd(e){
  if(!e||typeof e!=='object')return false;
  var k=Object.keys(e);
  for(var i=0;i<k.length;i++){if(AD.test(k[i]))return true;}
  try{var rw=e.command&&e.command.reelWatchEndpoint;if(rw&&rw.adClientParams)return true;}catch(x){}
  var c=e.richItemRenderer&&e.richItemRenderer.content;
  if(c){var k2=Object.keys(c);for(var j=0;j<k2.length;j++){if(AD.test(k2[j]))return true;}}
  return false;
}
function clean(o){
  if(Array.isArray(o)){
    for(var i=o.length-1;i>=0;i--){
      var e=o[i];
      if(isAd(e)){o.splice(i,1);continue;}
      clean(e);
    }
  }else if(o&&typeof o==='object'){
    for(var k in o){
      if(k==='adPlacements'||k==='playerAds'||k==='adSlots'||k==='adBreakHeartbeatParams'){delete o[k];}
      else clean(o[k]);
    }
  }
  return o;
}
['ytInitialPlayerResponse','ytInitialData'].forEach(function(n){
  var v;
  try{Object.defineProperty(window,n,{configurable:true,get:function(){return v;},set:function(x){try{clean(x);}catch(e){}v=x;}});}catch(e){}
});
var jp=JSON.parse;
JSON.parse=function(){
  var r=jp.apply(this,arguments);
  try{if(r&&typeof r==='object'&&(r.adPlacements||r.playerAds||r.adSlots))clean(r);}catch(e){}
  return r;
};
var of=window.fetch;
window.fetch=function(){
  var a=arguments;var p=of.apply(this,a);var u='';
  try{u=(typeof a[0]==='string')?a[0]:((a[0]&&a[0].url)||'');}catch(e){}
  // A /player választ NEM írjuk át: a lejátszó azt várja streamben, az átírás megakaszthatta
  // a lejátszót (minőségváltás után "csak a videó megy, semmi nem kattintható"). A reklámokat
  // a listák szűrése + az átugrás kezeli.
  // a kezdőlap (browse) és a videó alatti lista/kommentek (next) válaszához NEM nyúlunk: így folyamatosan,
  // azonnal érkeznek; a bennük lévő reklámelemeket a CSS rejti el
  if(!/\/youtubei\/v1\/(search|reel)/.test(u))return p;
  return p.then(function(r){
    return r.clone().text().then(function(t){
      // csak akkor dolgozunk rajta, ha tényleg van benne reklám-adat (nagy JSON átfésülése = fagyás)
      if(t.length>4000000)return r;
      if(!/adPlacements|playerAds|"adSlot|adClientParams|adBreakHeartbeatParams/.test(t))return r;
      try{
        var j=jp(t);clean(j);
        return new Response(JSON.stringify(j),{status:r.status,statusText:r.statusText,headers:r.headers});
      }catch(e){return r;}
    });
  }).catch(function(){return p;});
};
// háttérben is menjen a lejátszás
try{
  Object.defineProperty(document,'hidden',{configurable:true,get:function(){return false;}});
  Object.defineProperty(document,'visibilityState',{configurable:true,get:function(){return 'visible';}});
  Object.defineProperty(document,'webkitHidden',{configurable:true,get:function(){return false;}});
  Object.defineProperty(document,'webkitVisibilityState',{configurable:true,get:function(){return 'visible';}});
  document.hasFocus=function(){return true;};
  ['visibilitychange','webkitvisibilitychange'].forEach(function(n){
    document.addEventListener(n,function(e){e.stopImmediatePropagation();},true);
  });
}catch(e){}
// reklámok elrejtése
try{
  var st=document.createElement('style');
  st.textContent='.ytp-spinner,.ytp-spinner-container,.ytp-bezel,.ytp-bezel-text-wrapper,.ytp-cued-thumbnail-overlay{display:none!important}html,body,.html5-video-player,.html5-video-container,#player-container-id,.player-container,ytm-watch,ytm-reel-player{background:#000!important}html.vd-watch ytm-mobile-topbar-renderer,html.vd-watch .mobile-topbar-header{display:none!important}html.vd-shorts,html.vd-shorts body{background:#000!important}html.vd-shorts .ytp-cued-thumbnail-overlay,html.vd-shorts .ytp-cued-thumbnail-overlay-image{display:block!important;background:#000!important}html.vd-watch .ytp-cued-thumbnail-overlay{display:none!important}html.vd-watch,html.vd-watch body,html.vd-watch ytm-watch{background:#0f0f0f!important;padding-top:0!important}html.vd-watch #player-container-id,html.vd-watch .player-container,html.vd-shorts #player-container-id,html.vd-shorts .player-container{background:#000!important;top:0!important}html.vd-watch ytm-watch,html.vd-shorts ytm-reel-player{background:#000!important;padding-top:0!important}html.vd-watch .html5-video-player,html.vd-watch .html5-video-container,html.vd-watch #player,html.vd-watch .player-size,html.vd-watch ytm-custom-control{background:#000!important}html.vd-watch .ytp-cued-thumbnail-overlay-image{display:none!important}html.vd-mini,html.vd-mini body,html.vd-full,html.vd-full body{overflow:hidden!important;background:#000!important}.vd-anc{transform:none!important;filter:none!important;contain:none!important;perspective:none!important;will-change:auto!important;backdrop-filter:none!important;overflow:visible!important}.vd-fixed{position:fixed!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100vw!important;height:100vh!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;z-index:2147483647!important;background:#000!important}.vd-fixed .html5-video-container,.vd-fixed video{width:100%!important;height:100%!important;left:0!important;top:0!important;object-fit:contain!important}html.vd-watch .ytp-settings-button,html.vd-full .ytp-chrome-bottom,html.vd-full .ytp-chrome-top,html.vd-full .ytp-gradient-bottom,html.vd-full .ytp-large-play-button,html.vd-mini .ytp-chrome-bottom,html.vd-mini .ytp-gradient-bottom,html.vd-mini .player-controls-top,html.vd-mini .ytp-chrome-top,html.vd-mini .ytp-large-play-button{display:none!important}.video-ads,.ytp-ad-module,.ytp-ad-overlay-container,#player-ads,ytm-promoted-sparkles-web-renderer,ytm-companion-ad-renderer,ytm-statement-banner-renderer,ytm-ad-slot-renderer,ad-slot-renderer,ytm-promoted-video-renderer,ytm-mealbar-promo-renderer,ytm-banner-promo-renderer,ytm-app-promo,.ytm-app-promo,ytm-in-feed-ad-layout-renderer,.ad-container,ytm-home-logo,ytm-topbar-logo-renderer,.mobile-topbar-logo,.mobile-topbar-logo-container,ytmusic-nav-bar .logo,ytm-open-app-button-renderer,.open-app-button,ytm-topbar-open-app-button-renderer,ytm-open-app-banner-renderer,.mobile-topbar-header-content ytm-button-renderer.open-app{display:none!important}[class*="skeleton"],[class*="Skeleton"],ytm-skeleton-renderer{background:#000!important;box-shadow:none!important}ytm-account-button-renderer,#avatar-btn,ytm-sign-in-prompt-renderer,ytm-identity-button-renderer,ytm-account-section{display:none!important}ytm-pivot-bar-renderer *:has(> a[href*="/feed/you"]),ytm-pivot-bar-renderer *:has(> a[href*="/feed/library"]){display:none!important}ytm-pivot-bar-item-renderer:has(a[href*="/feed/you"]),ytm-pivot-bar-item-renderer:has(a[href*="/feed/library"]),ytm-pivot-bar-item-renderer:has(a[href*="account"]),yt-tab-shape:has(a[href*="/feed/you"]){display:none!important}ytm-reel-shelf-renderer h2,ytm-reel-shelf-renderer .title,ytm-reel-shelf-renderer ytm-shelf-header-layout,ytm-reel-shelf-renderer .reel-shelf-title-wrapper{display:none!important}html,body{overscroll-behavior:none!important}*{-webkit-tap-highlight-color:transparent!important}ytm-pivot-bar-renderer{margin-bottom:0!important;padding-bottom:0!important}ytmusic-menu-navigation-item-renderer:has(a[href*="premium" i]),ytmusic-menu-navigation-item-renderer:has(a[href*="/t/terms"]),ytmusic-menu-navigation-item-renderer:has(a[href*="terms" i]),ytmusic-menu-navigation-item-renderer:has(a[href*="policies.google"]){display:none!important}tp-yt-iron-dropdown .dropdown-content{background:transparent!important;box-shadow:none!important}ytmusic-search-box #input,ytmusic-search-box input,ytm-searchbox input,.searchbox-input{background:transparent!important}ytmusic-search-box .search-box,ytmusic-search-box #search-box,ytm-searchbox .searchbox-input-wrapper{background:rgba(255,255,255,.10)!important;-webkit-backdrop-filter:blur(18px) saturate(140%)!important;backdrop-filter:blur(18px) saturate(140%)!important;border-radius:999px!important;border:1px solid rgba(255,255,255,.16)!important}a[href*="/t/terms"],a[href*="/t/privacy"],a[href*="policies.google"],a[href*="/about/policies"],a[href*="template=terms"],a[href*="/static?gl="][href*="terms"]{display:none!important}.vd-glass{background:rgba(34,18,64,.58)!important;-webkit-backdrop-filter:blur(18px) saturate(140%)!important;backdrop-filter:blur(18px) saturate(140%)!important;border-radius:22px!important;border:1px solid rgba(196,165,255,.20)!important;box-shadow:0 12px 40px rgba(0,0,0,.45)!important;overflow:hidden!important}.vd-glass.vd-glass-l{border-radius:0 24px 24px 0!important;border-left:0!important}.vd-glass.vd-glass-b{border-radius:24px 24px 0 0!important;border-bottom:0!important}ytm-engagement-panel,.engagement-panel-container{border-radius:24px 24px 0 0!important;overflow:hidden!important;background:rgba(26,14,52,.64)!important;-webkit-backdrop-filter:blur(18px) saturate(140%)!important;backdrop-filter:blur(18px) saturate(140%)!important;border-top:1px solid rgba(255,255,255,.12)!important}tp-yt-app-drawer #contentContainer,tp-yt-app-drawer .contentContainer{background:rgba(26,14,52,.62)!important;-webkit-backdrop-filter:blur(18px) saturate(140%)!important;backdrop-filter:blur(18px) saturate(140%)!important;border-radius:0 24px 24px 0!important;border-right:1px solid rgba(255,255,255,.12)!important;overflow:hidden!important}tp-yt-app-drawer #contentContainer *:not(.iron-selected):not([active]):not(img):not(button):not(yt-icon):not(svg){background-color:transparent!important}tp-yt-paper-item,ytmusic-guide-entry-renderer tp-yt-paper-item,ytm-menu-item,tp-yt-app-drawer .iron-selected,ytmusic-guide-entry-renderer[active] tp-yt-paper-item{border-radius:14px!important}tp-yt-app-drawer .iron-selected,ytmusic-guide-entry-renderer[active] tp-yt-paper-item{background-color:rgba(255,255,255,.12)!important}tp-yt-paper-tabs tp-yt-paper-tab:not(.iron-selected):not([aria-selected="true"]),tp-yt-paper-tabs tp-yt-paper-tab:not(.iron-selected):not([aria-selected="true"]) *,[role="tablist"] [role="tab"][aria-selected="false"],[role="tablist"] [role="tab"][aria-selected="false"] *{background:transparent!important;box-shadow:none!important}tp-yt-paper-tab tp-yt-paper-ripple,tp-yt-paper-tab paper-ripple{display:none!important}html.vd-load,html.vd-load body{background:#000!important}html.vd-qtap .vd-fixed,html.vd-qtap .vd-fixed *,html.vd-qtap video,html.vd-qtap #vd-qp{pointer-events:none!important}html.vd-qopen ytw-scrim,html.vd-qopen [class*="Scrim"],html.vd-qopen [class*="BottomSheet"],html.vd-qopen [class*="bottom-sheet"],html.vd-qopen bottom-sheet-container,html.vd-qopen ytm-bottom-sheet-renderer,html.vd-qopen ytw-bottom-sheet,html.vd-qopen ytm-menu-popup-renderer{opacity:0!important;transition:none!important}html.vd-qopen .ytp-settings-menu,html.vd-qopen .ytp-settings-menu *,html.vd-qopen .ytp-popup,html.vd-qopen .ytp-popup *,html.vd-qopen .player-settings-menu,html.vd-qopen .player-settings-menu *,html.vd-qopen player-settings-menu,html.vd-qopen player-settings-menu *,html.vd-qopen ytm-settings-menu,html.vd-qopen ytm-settings-menu *{opacity:0!important;transition:none!important}html.vd-full ytmusic-player-page #side-panel,html.vd-full ytmusic-player-page .side-panel,html.vd-full ytmusic-player-bar,html.vd-full ytmusic-nav-bar,html.vd-full tp-yt-app-drawer,html.vd-full ytmusic-player-page #main-panel > :not(ytmusic-player):not(#player){visibility:hidden!important}html.vd-full ytmusic-player.vd-fixed{visibility:visible!important}ytmusic-player.vd-fixed #song-media-window,ytmusic-player.vd-fixed #song-video,ytmusic-player.vd-fixed #song-image,ytmusic-player.vd-fixed .html5-video-player,ytmusic-player.vd-fixed .html5-video-container,ytmusic-player.vd-fixed yt-img-shadow,ytmusic-player.vd-fixed #thumbnail{position:absolute!important;top:0!important;left:0!important;right:0!important;bottom:0!important;width:100%!important;height:100%!important;max-width:none!important;max-height:none!important;margin:0!important;padding:0!important;transform:none!important}ytmusic-player.vd-fixed img,ytmusic-player.vd-fixed video{position:absolute!important;top:0!important;left:0!important;width:100%!important;height:100%!important;object-fit:contain!important;transform:none!important}ytmusic-player.vd-fixed .song-media-controls,ytmusic-player.vd-fixed #song-media-controls,ytmusic-player.vd-fixed .ytp-chrome-bottom,ytmusic-player.vd-fixed .ytp-chrome-top{display:none!important}html.vd-music ytmusic-player,html.vd-music ytmusic-player #song-media-window,html.vd-music ytmusic-player .html5-video-player,html.vd-music ytmusic-player .html5-video-container,html.vd-music ytmusic-player #song-video,html.vd-music ytmusic-player video,html.vd-music ytmusic-player-page #player{background:transparent!important;background-color:transparent!important}html.vd-music ytmusic-player.vd-fixed{background:#000!important}html.vd-pip ytmusic-player button,html.vd-pip ytmusic-player tp-yt-paper-icon-button,html.vd-pip ytmusic-player yt-icon-button,html.vd-pip ytmusic-player yt-button-shape,html.vd-pip ytmusic-player .song-media-controls,html.vd-pip ytmusic-player #song-media-controls,html.vd-pip ytmusic-player .ytp-chrome-top,html.vd-pip ytmusic-player .ytp-chrome-bottom,html.vd-pip ytmusic-player .ytp-gradient-top,html.vd-pip ytmusic-player .ytp-gradient-bottom,html.vd-pip ytmusic-player .ytp-large-play-button,html.vd-pip ytmusic-player .ytp-paid-content-overlay,html.vd-pip ytmusic-player .ytp-ce-element{display:none!important}video.vd-fixed{object-fit:contain!important;visibility:visible!important;opacity:1!important;transform:none!important}html.vd-home ytm-mobile-topbar-renderer,html.vd-home ytm-topbar-renderer{display:none!important}html.vd-chipoff ytm-feed-filter-chip-bar-renderer,html.vd-chipoff ytm-chip-cloud-renderer,html.vd-chipoff ytm-chip-cloud-chip-renderer{opacity:0!important;visibility:hidden!important;pointer-events:none!important;transition:none!important}ytm-rich-item-renderer:has(ytm-ad-slot-renderer),ytm-rich-item-renderer:has(ad-slot-renderer),ytm-rich-section-renderer:has(ytm-statement-banner-renderer),ytm-item-section-renderer:has(> ytm-ad-slot-renderer){display:none!important}.yt-core-image,img.video-thumbnail-img,ytm-thumbnail-cover img{transition:none!important;animation:none!important}.yt-core-image--loaded{opacity:1!important}.video-thumbnail-bg,.video-thumbnail-container-large,.video-thumbnail-container-compact,.yt-thumbnail-view-model,.yt-thumbnail-view-model__image,.media-item-thumbnail-container,ytm-thumbnail-cover,.compact-media-item-image,.ytCoreImageHost,yt-thumbnail-view-model,.shimmer,[class*="shimmer"],[class*="placeholder"]:not(input):not(textarea){background-color:#000!important}ytm-player-placeholder,.player-placeholder,.ytm-player-placeholder,.player-container-background,#player-placeholder,.html5-video-player video,html.vd-watch #player,html.vd-shorts #player{background-color:#000!important;background-image:none!important}html.vd-music ytmusic-nav-bar ytmusic-search-box:not([has-query]){display:none!important}html.vd-music ytmusic-nav-bar:has(ytmusic-search-box[has-query]) #right-content yt-icon-button[aria-label="Settings"]{display:none!important}html.vd-music ytmusic-search-box[has-query]{left:52px!important;right:8px!important;width:auto!important;max-width:none!important}html.vd-music ytmusic-search-box[has-query] .search-box,html.vd-music ytmusic-search-box[has-query] input,html.vd-music ytmusic-search-box[has-query] #input{width:100%!important;max-width:100%!important}.ytp-cued-thumbnail-overlay-image,.ytp-timed-thumbnail-background{background-size:contain!important;background-position:center!important;background-color:#000!important}';
  (document.head||document.documentElement).appendChild(st);
}catch(e){}
// ha a rendszer (zárolás, kis ablak) leállítja a videót, de mi nem nyúltunk hozzá: folytatjuk
['touchstart','pointerdown','click','keydown'].forEach(function(n){document.addEventListener(n,function(e){if(e.isTrusted)window.__vdTouch=Date.now();},true);});
window.__vdUP=false;
window.__vdRes=[];
document.addEventListener('play',function(e){if(Date.now()-window.__vdTouch<2000)window.__vdUP=false;},true);
document.addEventListener('pause',function(e){
  var v=e.target;
  if(!v||v.tagName!=='VIDEO'||v.ended)return;
  if(v.readyState===0)return;   // forrásváltás közbeni szünet, nem a felhasználó állította meg
  if(Date.now()-window.__vdTouch<2000){window.__vdUP=true;return;}   // te állítottad meg
  if(window.__vdUP)return;
  if(!(window.__vdBg||window.__vdMode||Date.now()<(window.__vdResumeUntil||0)))return;   // kis ablak / PiP / háttér / visszatérés
  var pl=v.closest&&v.closest('.html5-video-player');
  if(pl&&(pl.classList.contains('ad-showing')||pl.classList.contains('ad-interrupting')))return;
  var now=Date.now();window.__vdRes=window.__vdRes.filter(function(t){return now-t<10000;});
  if(window.__vdRes.length>=6)return;
  window.__vdRes.push(now);
  setTimeout(function(){try{if(v.paused&&!v.ended&&!window.__vdUP)v.play();}catch(x){}},400);
},true);
// az aktuális (látható) videó kiválasztása – a Shorts-ban több videóelem is van
var __vdPV=null,__vdPVT=0;
function pickVideo(){
  var vs=document.querySelectorAll('video');
  if(vs.length<2)return vs[0]||null;
  var now=Date.now();
  if(__vdPV&&now-__vdPVT<300&&__vdPV.isConnected)return __vdPV;  // nem mérünk elrendezést 250 ms-onként
  var best=null,ba=-1,H=window.innerHeight,W=window.innerWidth;
  for(var i=0;i<vs.length;i++){
    var r=vs[i].getBoundingClientRect();
    var w=Math.max(0,Math.min(r.right,W)-Math.max(r.left,0)),h=Math.max(0,Math.min(r.bottom,H)-Math.max(r.top,0));
    if(w*h>ba){ba=w*h;best=vs[i];}
  }
  __vdPV=best;__vdPVT=now;
  return best;
}
// ha mégis lenne reklám: azonnal átugorjuk
function tick(){
  try{
    // VidaraMusic: a lap magától frissül, nekünk 1 mp-enként elég – így váltáskor nem fut egymásra két kör
    if(location.hostname==='music.youtube.com'){if(Date.now()-(window.__vdTkM||0)<1000)return;window.__vdTkM=Date.now();}
    if(window.__vdQuiet){var qv=document.querySelectorAll('video');for(var qi=0;qi<qv.length;qi++){try{if(!qv[qi].paused)qv[qi].pause();}catch(e){}}return;}
    if(window.__vdMode&&Date.now()-(window.__vdApplyT||0)>1500){window.__vdApplyT=Date.now();window.__vdApply();}
    var v=pickVideo();
    var pl=(v&&v.closest&&v.closest('.html5-video-player'))||document.querySelector('.html5-video-player');
    var ad=pl&&(pl.classList.contains('ad-showing')||pl.classList.contains('ad-interrupting'));
    var mus=location.hostname==='music.youtube.com';
    var watch=!mus&&/^\/watch/.test(location.pathname);
    var shorts=!mus&&/^\/shorts/.test(location.pathname);
    document.documentElement.classList.toggle('vd-watch',watch);
    document.documentElement.classList.toggle('vd-shorts',shorts);
    if(window.__vdNavHref!==location.href){window.__vdNavHref=location.href;if(watch){try{window.scrollTo(0,0);}catch(e){}}if(!watch&&!shorts&&!mus){[80,350,900,1800].forEach(function(d){setTimeout(function(){try{window.__vdSweep&&window.__vdSweep();}catch(e){}},d);});}if(watch||shorts){try{VidaraApp.nav(location.href);}catch(e){}}}
    if(window.__vdHref!==location.href){var wasSh=/\/shorts/.test(window.__vdHref||'');window.__vdHref=location.href;window.__vdTry=0;window.__vdPlayTries=0;window.__vdHT=Date.now();if(!(shorts&&wasSh))window.__vdRz=0;window.__vdClicked=false;window.__vdUP=false;window.__vdStarted=null;if(mus)window.__vdAutoUntil=Date.now()+14000;}
    // a Shorts-elrendezés néha félig számolódik ki: újraszámoltatjuk
    if(shorts&&window.__vdRz<2&&Date.now()-window.__vdHT>(350+window.__vdRz*900)){window.__vdRz++;try{window.dispatchEvent(new Event('resize'));}catch(e){}}
    // a takaró addig marad, amíg a videó TÉNYLEG megy (és az alatta lévő lista is betöltött)
    var vidOK=true;
    try{
      var want=watch?(new URLSearchParams(location.search)).get('v'):null;
      if(want&&pl&&pl.getVideoData){var gd=pl.getVideoData();if(gd&&gd.video_id)vidOK=(gd.video_id===want);}
    }catch(e){}
    var atTop=true;
    try{if(watch&&pl){var pr=pl.getBoundingClientRect();atTop=(pr.top<90&&pr.height>80&&Math.abs(pr.width-window.innerWidth)<10);if(atTop){window.__vdStab=(window.__vdStab||0)+1;atTop=window.__vdStab>=3;}else window.__vdStab=0;if(!atTop&&Date.now()-window.__vdHT<6000)window.scrollTo(0,0);}}catch(e){}
    if(v&&vidOK&&atTop&&(watch||shorts)&&window.__vdRdy!==location.href&&!v.paused&&v.currentTime>0.05&&v.readyState>=3){
      if(window.__vdPS!==location.href){window.__vdPS=location.href;window.__vdPT=Date.now();try{VidaraApp.playing();}catch(e){}}
      var rel=shorts||document.querySelectorAll('ytm-compact-video-renderer,ytm-video-with-context-renderer,ytm-media-item').length>0;
      if(rel||Date.now()-window.__vdPT>2000){window.__vdRdy=location.href;try{VidaraApp.ready();}catch(e){}}
    }
    // Visszatérés PiP-ből / háttérből: a rendszer által megállított videót újraindítjuk
    if(v&&v.paused&&!ad&&!window.__vdQuiet&&Date.now()<(window.__vdResumeUntil||0)){try{v.play();}catch(e){}}
    // Shorts: a látható videó azonnal, előtöltéssel induljon
    if(shorts&&v){try{if(v.preload!=='auto')v.preload='auto';}catch(e){}}
    // magától induljon el (a nagy lejátszó gomb ne kelljen)
    if(v&&!v.paused&&v.currentTime>0.05)window.__vdStarted=location.href;
    if((watch||shorts)&&v&&v.paused&&!ad&&window.__vdStarted!==location.href&&window.__vdPlayTries<(shorts?80:20)&&!window.__vdUP){
      window.__vdPlayTries++;
      try{v.play();}catch(e){}
      if(!window.__vdClicked&&Date.now()-window.__vdHT>2500){
        window.__vdClicked=true;
        var lp=document.querySelector('.ytp-large-play-button');
        if(lp)lp.click();
      }
    }
    // Ha koppintás után sem indul el (fekete kép), 2,5 mp után erőből elindítjuk
    var sinceHref=Date.now()-(window.__vdHT||0);
    if((watch||shorts)&&v&&v.paused&&!ad&&sinceHref>2500&&sinceHref<15000&&window.__vdStarted!==location.href){
      window.__vdKick=(window.__vdKick||0)+1;
      if(!window.__vdKickT||Date.now()-window.__vdKickT>800){
        window.__vdKickT=Date.now();window.__vdUP=false;
        try{v.play();}catch(e){}
        if(window.__vdKick%3===0){var lpk=document.querySelector('.ytp-large-play-button');if(lpk)lpk.click();}
      }
    }
    // hanggal induljon a videó (a YouTube alapból némán indítja)
    if(!mus&&!ad&&v&&!v.paused&&(shorts||(pl&&window.__vdTry<25))){
      var mu=(pl&&pl.isMuted&&pl.isMuted())||v.muted||v.volume===0;
      if(mu){window.__vdTry++;try{if(pl&&pl.unMute)pl.unMute();}catch(e){}v.muted=false;if(v.volume===0)v.volume=1;try{if(pl&&pl.setVolume&&pl.getVolume&&pl.getVolume()===0)pl.setVolume(100);}catch(e){}}
      else if(!shorts){window.__vdTry=99;}
    }
    // Felbontást NEM kényszerítünk menet közben: a setPlaybackQualityRange ismételt hívása
    // újraindította a videó streamjét (8 mp-enként újratöltés = szürke kép, akadás, "fagyás").
    // A YouTube maga választ; a felhasználó a menüből kérhet egyszer mást (__vdSetQ).
    // Shorts: a hosszú / fekvő (nem igazi short) videókat átugorjuk
    if(shorts&&v&&v.readyState>=1&&(v.videoWidth>v.videoHeight*1.1||(isFinite(v.duration)&&v.duration>200))&&Date.now()-(window.__vdLS||0)>1500){
      window.__vdLS=Date.now();try{VidaraApp.skip();}catch(e){}
    }
    // Shorts: reklám-dia automatikus átugrása
    if(shorts&&Date.now()-(window.__vdSA||0)>1200){
      window.__vdSA=Date.now();
      var cs=document.querySelectorAll('[class*="ad-badge"],[class*="adBadge"],[class*="sponsor"],[aria-label="Ad"],[aria-label*="Sponsored"],ytm-reel-player-overlay-renderer span,ytm-reel-player-overlay-renderer a,ytm-reel-player-overlay-renderer button');
      var H2=window.innerHeight;
      for(var ci=0;ci<cs.length;ci++){
        var ce=cs[ci];var tx=(ce.textContent||'').trim();
        if(/^(Ad|Sponsored|Advertisement|Hirdetés|Szponzorált)$/i.test(tx)||/^(Ad|Sponsored)\b/i.test(ce.getAttribute('aria-label')||'')){
          var cr=ce.getBoundingClientRect();
          if(cr.width>0&&cr.top>=0&&cr.bottom<=H2){try{VidaraApp.skip();}catch(e){}break;}
        }
      }
    }
    var sk=document.querySelector('.ytp-ad-skip-button,.ytp-ad-skip-button-modern,.ytp-skip-ad-button,.ytp-ad-skip-button-container button');
    if(sk)sk.click();
    if(ad&&v){
      v.__vdm=true;v.muted=true;v.playbackRate=16;
      if(isFinite(v.duration)&&v.duration>0)v.currentTime=v.duration;
    }else if(v&&v.__vdm){v.__vdm=false;v.muted=false;v.playbackRate=1;}
  }catch(e){}
}
setInterval(function(){try{if(location.hostname==='music.youtube.com'){var n=Date.now();if(n-(window.__vdTickM||0)<900)return;window.__vdTickM=n;}tick();}catch(e){}},500);
['playing','canplay','loadeddata','play'].forEach(function(n){document.addEventListener(n,function(){if(location.hostname!=='music.youtube.com')tick();},true);});
// ---- Teljes képernyő gomb: natív váltás (a videó nem áll meg)
try{
  document.addEventListener('click', function(e){
    try{
      var path = e.composedPath ? e.composedPath() : [];
      for (var i=0;i<path.length;i++){
        var n=path[i];
        if(!n || !n.getAttribute) continue;
        var cls=(n.className&&n.className.toString)?n.className.toString():'';
        var lab=n.getAttribute('aria-label')||'';
        if(cls.indexOf('ytp-fullscreen-button')>=0 || /full screen|teljes k/i.test(lab)){
          e.preventDefault(); e.stopPropagation();
          try{ VidaraApp.fsToggle(); }catch(x){}
          return false;
        }
      }
    }catch(x){}
  }, true);
}catch(e){}
// ---- Videóindítás: a szürke YouTube-váz/háttérkép HELYETT azonnal a videó saját képe látszik,
// és csak akkor tűnik el, amikor az új videó első képkockája tényleg kirajzolódott.
(function(){
  function vid(u){
    try{
      var x=new URL(u,location.href);
      var m=x.pathname.match(/^\/shorts\/([A-Za-z0-9_-]{6,})/);
      if(m)return {id:m[1],s:true};
      if(/^\/watch/.test(x.pathname)){var q=x.searchParams.get('v');if(q)return {id:q,s:false};}
    }catch(e){}
    return null;
  }
  function ov(){
    var o=window.__vdLd;
    if(!o){
      o=document.createElement('div');
      o.style.cssText='position:fixed;left:0;top:0;right:0;bottom:0;z-index:2147483647;background:#000;pointer-events:none;display:none';
      var im=document.createElement('img');
      im.style.cssText='position:absolute;left:0;top:0;width:100%;object-fit:cover;display:none;background:#000';
      o.appendChild(im);
      window.__vdLd=o;
    }
    if(!o.isConnected)(document.body||document.documentElement).appendChild(o);
    return o;
  }
  window.__vdLoadOn=function(u){
    try{
      var d=vid(u||location.href);
      if(!d)return;
      var o=ov(),im=o.firstChild;
      var src='https://i.ytimg.com/vi/'+d.id+(d.s?'/oar2.jpg':'/hqdefault.jpg');
      im.onerror=function(){if(im.src.indexOf('oar2')>=0)im.src='https://i.ytimg.com/vi/'+d.id+'/hqdefault.jpg';else im.style.display='none';};
      im.style.height=d.s?'100%':'56.25vw';
      if(im.getAttribute('src')!==src)im.setAttribute('src',src);
      im.style.display='block';
      o.style.bottom=d.s?'0':'auto';o.style.height=d.s?'auto':'56.25vw';
      o.style.top='0';o.style.left='0';o.style.width='';o.style.right='0';
      if(location.hostname==='music.youtube.com')place(o);
      o.style.display='block';
      window.__vdLdId=d.id;window.__vdLdT=Date.now();window.__vdLdF=false;
      document.documentElement.classList.add('vd-load');
      clearTimeout(window.__vdLdTo);window.__vdLdTo=setTimeout(function(){window.__vdLoadOff();},10000);
    }catch(e){}
  };
  // VidaraMusic: a takaró pontosan a lejátszó dobozát fedi (ott volt a szürke YouTube-háttér)
  function place(o){
    try{
      var ps=document.querySelectorAll('ytmusic-player,#player.ytmusic-player-page,#song-media-window,.html5-video-player');
      for(var i=0;i<ps.length;i++){
        var r=ps[i].getBoundingClientRect();
        if(r.width>60&&r.height>60&&r.bottom>0&&r.top<window.innerHeight){
          o.style.top=Math.round(r.top)+'px';o.style.left=Math.round(r.left)+'px';
          o.style.width=Math.round(r.width)+'px';o.style.height=Math.round(r.height)+'px';
          o.style.right='auto';o.style.bottom='auto';
          var im=o.firstChild;if(im){im.style.height='100%';}
          return true;
        }
      }
    }catch(e){}
    return false;
  }
  window.__vdLoadOff=function(){
    try{
      window.__vdLdId=null;clearTimeout(window.__vdLdTo);
      if(window.__vdLd)window.__vdLd.style.display='none';
      document.documentElement.classList.remove('vd-load');
    }catch(e){}
  };
  function chk(){
    try{
      if(!window.__vdLdId)return;
      var cur=vid(location.href);
      // máshova léptünk (vissza a listára, vagy a Shortsban továbblapoztak): nincs takarás
      if(!cur){if(Date.now()-window.__vdLdT>1200)window.__vdLoadOff();return;}
      if(cur.id!==window.__vdLdId){if(cur.s||Date.now()-window.__vdLdT>1500)window.__vdLoadOff();return;}
      if(window.__vdQuiet)return;
      if(location.hostname==='music.youtube.com'&&window.__vdLd)place(window.__vdLd);
      var v=pickVideo();
      if(!v||v.paused||v.readyState<3||v.currentTime<0.12)return;
      var pl=(v.closest&&v.closest('.html5-video-player'))||document.querySelector('.html5-video-player');
      if(pl&&(pl.classList.contains('ad-showing')||pl.classList.contains('ad-interrupting')))return;   // reklám alatt is takarunk
      if(!cur.s&&pl&&pl.getVideoData){var gd=pl.getVideoData();if(gd&&gd.video_id&&gd.video_id!==cur.id)return;}
      if(window.__vdLdF)return;
      window.__vdLdF=true;
      if(v.requestVideoFrameCallback){
        var done=false;
        v.requestVideoFrameCallback(function(){done=true;window.__vdLoadOff();});
        setTimeout(function(){if(!done)window.__vdLoadOff();},150);
      }else window.__vdLoadOff();
    }catch(e){}
  }
  ['playing','timeupdate','loadeddata'].forEach(function(n){document.addEventListener(n,chk,true);});
  setInterval(function(){if(!document.hidden)chk();},400);
  setInterval(function(){
    try{
      if(document.hidden||location.hostname!=='m.youtube.com'||window.__vdYHref===location.href)return;
      var prev=window.__vdYHref;window.__vdYHref=location.href;
      if(prev==null)return;   // első betöltés: azt a dokumentum-indítás már takarja
      var d=vid(location.href);if(!d||d.s||d.id===window.__vdLdId)return;
      var v=pickVideo(),pl=document.querySelector('.html5-video-player');
      var gd=null;try{gd=pl&&pl.getVideoData?pl.getVideoData():null;}catch(e){}
      if(v&&!v.paused&&v.currentTime>0.3&&gd&&gd.video_id===d.id)return;   // már ez megy
      window.__vdLoadOn(location.href);
    }catch(e){}
  },350);
  setInterval(function(){
    try{
      if(location.hostname!=='music.youtube.com'||window.__vdMHref===location.href)return;
      window.__vdMHref=location.href;return;   // VidaraMusic: a saját lejátszója gyors, takaró nélkül
      var d=vid(location.href);if(!d||d.id===window.__vdLdId)return;
      var v=pickVideo(),pl=document.querySelector('.html5-video-player');
      var gd=null;try{gd=pl&&pl.getVideoData?pl.getVideoData():null;}catch(e){}
      if(v&&!v.paused&&v.currentTime>0.3&&gd&&gd.video_id===d.id)return;   // már ez megy
      window.__vdLoadOn(location.href);
    }catch(e){}
  },1000);
  // ujj a videón (még a koppintás előtt): a képe már töltődik, így a takaró azonnal a videó képét mutatja
  document.addEventListener('touchstart',function(e){
    try{
      var path=e.composedPath?e.composedPath():[];
      for(var i=0;i<path.length&&i<12;i++){
        var n=path[i];
        if(n&&n.tagName==='A'){var d=vid(n.getAttribute('href')||'');if(d){var im=new Image();im.src='https://i.ytimg.com/vi/'+d.id+(d.s?'/oar2.jpg':'/hqdefault.jpg');}break;}
      }
    }catch(x){}
  },{capture:true,passive:true});
  // közvetlen betöltés (videóoldal): már az első pillanattól a videó képe látszik
  if(/^\/(watch|shorts\/)/.test(location.pathname))window.__vdLoadOn(location.href);
})();
// ---- Koppintás videóra: azonnal fekete takaró (a szürke váz ne villanjon be)
try{
  document.addEventListener('click', function(e){
    try{
      var path = e.composedPath ? e.composedPath() : [];
      for (var i=0;i<path.length;i++){
        var n=path[i];
        if(n && n.tagName==='A' && n.getAttribute){
          var h=n.getAttribute('href')||'';
          if(/(^|\/)watch\?/.test(h) || h.indexOf('/shorts')>=0){
            if(location.hostname!=='music.youtube.com'){
              try{ window.__vdLoadOn&&window.__vdLoadOn(h); }catch(x){}
              try{ VidaraApp.videoStart(h); }catch(x){}
            }
            return;
          }
        }
      }
      // kártya, amin nincs közvetlen link a koppintás helyén: a kártyán belüli videó-linket vesszük
      var t=e.target,card=t&&t.closest?t.closest('ytm-media-item,ytm-video-with-context-renderer,ytm-compact-video-renderer,ytm-rich-item-renderer,ytm-reel-item-renderer,ytm-shorts-lockup-view-model'):null;
      if(card&&!t.closest('button,[role="button"],ytm-menu-renderer')){
        var a=card.querySelector('a[href*="watch?"],a[href*="/shorts/"]');
        if(a){var h2=a.getAttribute('href')||'';try{window.__vdLoadOn&&window.__vdLoadOn(h2);}catch(x){}try{VidaraApp.videoStart(h2);}catch(x){}}
      }
    }catch(x){}
  }, true);
}catch(e){}
// ---- Ha a lejátszó állva marad (nincs látható play gomb), indítsuk el
window.__vdVideo=function(){
  try{var v0=document.querySelector('video');if(v0)return v0;}catch(e){}
  try{var mm=window.__vdVS;if(mm&&mm.isConnected&&mm.readyState!==undefined)return mm;}catch(e){}
  try{
    var all=document.querySelectorAll('*');
    for(var i=0;i<all.length&&i<1500;i++){
      var sr=all[i].shadowRoot;
      if(sr){var w=sr.querySelector('video');if(w){window.__vdVS=w;return w;}}
    }
  }catch(e){}
  return null;
};
window.__vdAutoUntil=0;
window.__vdPlay=function(v){
  if(!v)return;
  try{
    if(v.paused){
      // a néma indítás mindig engedélyezett; utána visszakapcsoljuk a hangot
      var wasMuted=v.muted; v.muted=true;
      var pr=v.play();
      if(pr&&pr.then){pr.then(function(){ if(!wasMuted) setTimeout(function(){ try{v.muted=false;}catch(e){} },300); })
        .catch(function(e){try{VidaraApp.log('JS play hiba: '+(e&&e.name));}catch(x){}});}
    }
  }catch(e){}
};
// Shorts-váltás: a régi videó UTOLSÓ képkockáját kifessük egy vászonra (nincs fekete a váltás alatt)
window.__vdFreeze=function(){
  try{
    var v=document.querySelector('video');
    if(!v||!v.videoWidth||v.videoWidth<10)return;
    if(!window.__vdOv){
      var c=document.createElement('canvas');
      c.style.cssText='position:fixed;left:0;top:0;width:100%;height:100%;z-index:2147483646;display:none;pointer-events:none;background:#000;object-fit:contain';
      (document.body||document.documentElement).appendChild(c);
      window.__vdOv=c;
    }
    var ov=window.__vdOv;
    if(ov.width!==v.videoWidth){ov.width=v.videoWidth;ov.height=v.videoHeight;}
    try{
      var ctx=ov.getContext('2d');
      ctx.clearRect(0,0,ov.width,ov.height);
      ctx.drawImage(v,0,0,ov.width,ov.height);   // a videó utolsó képkockája (nem kell CORS)
      ov.style.display='block';
      clearTimeout(window.__vdOvT);
      window.__vdOvT=setTimeout(function(){try{ov.style.display='none';}catch(e){}},1400);
    }catch(e){}
  }catch(e){}
};
window.__vdUnfreeze=function(){try{if(window.__vdOv){window.__vdOv.style.display='none';clearTimeout(window.__vdOvT);}}catch(e){}};
try{if(location.hostname==='music.youtube.com')document.documentElement.classList.add('vd-music');}catch(e){}
// VidaraMusic: a felcsúszó lap (Up next / Lyrics / Comments / Related) is lila üveg – csak maga a lap (a lejátszó fölé nem)
(function(){
  if(location.hostname!=='music.youtube.com')return;
  var TABS=/^(up next|lyrics|comments|related|következő|dalszöveg|hozzászólások|megjegyzések|kapcsolódó)$/i,done=new WeakSet(),cur=null;
  function al(c){if(!c||c==='transparent')return 0;var m=c.match(/rgba?\(([^)]+)\)/);if(!m)return 1;var q=m[1].split(',');return q.length>3?parseFloat(q[3]):1;}
  function find(){
    var ls=document.querySelectorAll('tp-yt-paper-tabs,[role="tablist"]');
    for(var i=0;i<ls.length;i++){
      var r=ls[i].getBoundingClientRect();if(r.height<10||r.width<100)continue;
      var t=ls[i].querySelectorAll('tp-yt-paper-tab,[role="tab"]'),ok=false;
      for(var j=0;j<t.length;j++){if(TABS.test((t[j].textContent||'').trim())){ok=true;break;}}
      if(!ok)continue;
      var e=ls[i],best=null;
      for(var k=0;k<12&&e&&e!==document.body;k++){
        var b=e.getBoundingClientRect();
        if(b.top<r.top-60||b.height>=window.innerHeight-2)break;
        if(b.width>=window.innerWidth*0.9)best=e;
        e=e.parentElement;
      }
      if(best)return best;
    }
    return null;
  }
  function paint(sh){
    var st=sh.style;st.removeProperty('backdrop-filter');st.removeProperty('-webkit-backdrop-filter');var w={'background':'rgba(30,16,58,0.96)','border-radius':'24px 24px 0 0','border-top':'1px solid rgba(196,165,255,0.20)','overflow':'hidden'};
    for(var k in w){if(st.getPropertyValue(k)!==w[k])st.setProperty(k,w[k],'important');}
    if(!done.has(sh)){
      done.add(sh);
      // a lapon belüli szürke rétegek átlátszók (a sorok kiemelése megmarad)
      var R=sh.getBoundingClientRect(),q=[sh],n=0;
      while(q.length&&n<300){var e=q.shift();n++;for(var c=0;c<e.children.length;c++){var ch=e.children[c],b=ch.getBoundingClientRect();
        if(b.width>=R.width*0.85&&b.height>=40){if(al(getComputedStyle(ch).backgroundColor)>=0.85)ch.style.setProperty('background','transparent','important');q.push(ch);}}}
    }
  }
  var lastFind=0;
  setInterval(function(){try{
    if(cur&&cur.isConnected){paint(cur);if(Date.now()-lastFind<6000)return;}
    lastFind=Date.now();var sh=find();if(sh){cur=sh;paint(sh);}
  }catch(e){}},1500);
})();
// Saját ikonok a gombokon: a gomb felirata alapján, ikon-elemenként egyszer, tétlen időben (nem lassít)
(function(){
  var MAP=[["exitfs",new RegExp("^Exit\\ full\\ screen|^Kil\u00e9p\u00e9s\\ a\\ teljes\\ k\u00e9perny\u0151b\u0151l",'i')],["fullscreen",new RegExp("^Full\\ screen|^Enter\\ full\\ screen|^Fullscreen|^Teljes\\ k\u00e9perny\u0151",'i')],["mic",new RegExp("^Search\\ with\\ your\\ voice|^Voice\\ search|^Hangalap\u00fa\\ keres\u00e9s|^Keres\u00e9s\\ hanggal",'i')],["search",new RegExp("^Search|^Keres\u00e9s",'i')],["back",new RegExp("^Back|^Vissza|^Navigate\\ up",'i')],["close",new RegExp("^Close|^Bez\u00e1r\u00e1s|^Dismiss|^Elvet\u00e9s",'i')],["menu",new RegExp("^Guide$|^Menu$|^Men\u00fc$",'i')],["cast",new RegExp("^Cast|^Play\\ on\\ TV|^Lej\u00e1tsz\u00e1s\\ TV\\-n",'i')],["bell",new RegExp("^Notifications|^\u00c9rtes\u00edt\u00e9sek",'i')],["settings",new RegExp("^Settings|^Player\\ settings|^Playback\\ settings|^Be\u00e1ll\u00edt\u00e1sok",'i')],["cc",new RegExp("^Captions|^Subtitles|^Feliratok|^Felirat",'i')],["pause",new RegExp("^Pause$|^Pause\\ video|^Pause\\ \\(|^Sz\u00fcnet$|^Sz\u00fcneteltet\u00e9s",'i')],["play",new RegExp("^Play$|^Play\\ video|^Play\\ \\(|^Lej\u00e1tsz\u00e1s$|^Lej\u00e1tsz\u00e1s\\ \\(",'i')],["next",new RegExp("^Next$|^Next\\ video|^Next\\ \\(|^K\u00f6vetkez\u0151",'i')],["prev",new RegExp("^Previous$|^Previous\\ video|^Previous\\ \\(|^El\u0151z\u0151",'i')],["shuffle",new RegExp("^Shuffle|^Kever\u00e9s|^V\u00e9letlen",'i')],["repeat",new RegExp("^Repeat|^Ism\u00e9tl\u00e9s",'i')],["volume",new RegExp("^Mute|^Unmute|^Volume|^N\u00e9m\u00edt\u00e1s|^Hanger\u0151",'i')],["explore",new RegExp("^Explore|^Felfedez\u00e9s",'i')],["comment",new RegExp("^Comments|^Hozz\u00e1sz\u00f3l\u00e1sok|^Megjegyz\u00e9sek",'i')],["flag",new RegExp("^Report|^Jelent\u00e9s",'i')],["queue",new RegExp("^Queue$|^Lej\u00e1tsz\u00e1si\\ sor",'i')],["plus",new RegExp("^Create$|^L\u00e9trehoz\u00e1s",'i')],["dislike",new RegExp("^Dislike|^Nem\\ tetszik",'i')],["like",new RegExp("^Like|^Tetszik",'i')],["share",new RegExp("^Share|^Megoszt\u00e1s",'i')],["save",new RegExp("^Save|^Ment\u00e9s",'i')],["download",new RegExp("^Download|^Let\u00f6lt\u00e9s",'i')],["clip",new RegExp("^Clip|^Klip",'i')],["remix",new RegExp("^Remix",'i')],["more",new RegExp("^More\\ actions|^Action\\ menu|^More\\ options|^More$|^Tov\u00e1bbi|^Tov\u00e1bbiak|^M\u0171veletek",'i')]];
  var ICO='.yt-spec-button-shape-next__icon,c3-icon,yt-icon';
  var SPEC=[
    ['home','ytm-pivot-bar-item-renderer .pivot-w2w,ytmusic-pivot-bar-item-renderer[tab-id="FEmusic_home"]'],
    ['shorts','ytm-pivot-bar-item-renderer .pivot-shorts'],
    ['explore','ytmusic-pivot-bar-item-renderer[tab-id="FEmusic_explore"]'],
    ['playlist','ytmusic-pivot-bar-item-renderer[tab-id="FEmusic_library_landing"]'],
    ['next','ytmusic-player-bar .next-button,ytmusic-player-page .next-button'],
    ['prev','ytmusic-player-bar .previous-button,ytmusic-player-page .previous-button'],
    ['shuffle','ytmusic-player-bar .shuffle,ytmusic-player-page .shuffle'],
    ['repeat','ytmusic-player-bar .repeat,ytmusic-player-page .repeat'],
    ['search','ytmusic-nav-bar .search-icon,ytmusic-search-box .search-icon'],
    ['menu','ytmusic-nav-bar #guide-button']
  ];
  function setIc(ic,k){
    if(!ic||ic.__vdK===k||ic.closest('.vd-vm-box'))return;
    ic.__vdK=k;
    var v='var(--vi-'+k+') center/contain no-repeat';
    ic.style.setProperty('-webkit-mask',v,'important');ic.style.setProperty('mask',v,'important');
    ic.style.setProperty('background-color','currentColor','important');
    ic.classList.add('vd-ico');
  }
  function outer(b){var l=b.querySelectorAll(ICO);for(var i=0;i<l.length;i++){var e=l[i],p=e.parentElement,inner=false;while(p&&p!==b){if(p.matches&&p.matches(ICO)){inner=true;break;}p=p.parentElement;}if(!inner)return e;}return null;}
  function run(){
    try{
      var bs=document.querySelectorAll('button[aria-label],a[aria-label],tp-yt-paper-icon-button[aria-label],tp-yt-paper-icon-button[title],[role="button"][aria-label],[role="tab"][aria-label]');
      for(var i=0;i<bs.length&&i<500;i++){
        var b=bs[i],lab=(b.getAttribute('aria-label')||b.getAttribute('title')||'').trim();
        if(!lab)continue;
        var k=b.__vdKL===lab?b.__vdKK:null;
        if(b.__vdKL!==lab){b.__vdKL=lab;k=null;for(var m=0;m<MAP.length;m++){if(MAP[m][1].test(lab)){k=MAP[m][0];break;}}b.__vdKK=k;}
        if(!k)continue;
        var ic=outer(b);if(ic&&ic.__vdK!==k)setIc(ic,k);
      }
      for(var s=0;s<SPEC.length;s++){var es=document.querySelectorAll(SPEC[s][1]);for(var j=0;j<es.length;j++){var ic2=outer(es[j]);if(ic2)setIc(ic2,SPEC[s][0]);}}
    }catch(e){}
  }
  function idle(){(window.requestIdleCallback||function(f){setTimeout(f,1);})(run,{timeout:1500});}
  window.__vdIcons=idle;
  setInterval(function(){if(!document.hidden)idle();},6000);setTimeout(idle,900);
  var lh=location.href;setInterval(function(){if(lh!==location.href){lh=location.href;setTimeout(idle,250);setTimeout(idle,900);}},800);
  document.addEventListener('click',function(e){var t=e.target;if(t&&t.closest&&t.closest('.html5-video-player,#player-control-container,ytmusic-player'))setTimeout(idle,120);},true);
})();
// Saját ikonok a felugró menük soraiban (a sor szövege alapján) – csak a megnyílt menüben fut
window.__vdIconRows=function(root){
  try{
    if(!root||!root.querySelectorAll)return;
    var MAP=[["save",new RegExp("^(save|ment\u00e9s|save to|ment\u00e9s lej\u00e1tsz\u00e1si|watch later|megn\u00e9zend\u0151)",'i')],["share",new RegExp("^(share|megoszt\u00e1s)",'i')],["close",new RegExp("^(not interested|nem \u00e9rdekel|don't recommend|ne aj\u00e1nl|remove from|elt\u00e1vol\u00edt\u00e1s|dismiss)",'i')],["flag",new RegExp("^(report|jelent\u00e9s|bejelent\u00e9s)",'i')],["download",new RegExp("^(download|let\u00f6lt\u00e9s)",'i')],["queue",new RegExp("^(add to queue|play next|hozz\u00e1ad\u00e1s a sorhoz|lej\u00e1tsz\u00e1s k\u00f6vetkez\u0151k\u00e9nt|k\u00f6vetkez\u0151k\u00e9nt)",'i')],["radio",new RegExp("^(start radio|start mix|r\u00e1di\u00f3 ind\u00edt\u00e1sa|mix ind\u00edt\u00e1sa)",'i')],["playlist",new RegExp("^(add to playlist|go to album|save to playlist|album megnyit\u00e1sa|hozz\u00e1ad\u00e1s lej\u00e1tsz\u00e1si list\u00e1hoz)",'i')],["you",new RegExp("^(go to artist|go to channel|el\u0151ad\u00f3|csatorna)",'i')],["settings",new RegExp("^(settings|be\u00e1ll\u00edt\u00e1sok|quality|min\u0151s\u00e9g)",'i')],["speed",new RegExp("^(playback speed|lej\u00e1tsz\u00e1si sebess\u00e9g)",'i')],["cc",new RegExp("^(captions|feliratok|subtitles)",'i')],["help",new RegExp("^(help|s\u00fag\u00f3|s\u00fag\u00f3 \u00e9s visszajelz\u00e9s)",'i')],["comment",new RegExp("^(send feedback|visszajelz\u00e9s|feedback)",'i')],["like",new RegExp("^(like|tetszik|add to liked|hozzáadás a kedvelt)",'i')],["dislike",new RegExp("^(dislike|nem tetszik)",'i')],["trash",new RegExp("^(delete|t\u00f6rl\u00e9s|remove|elt\u00e1vol\u00edt)",'i')],["info",new RegExp("^(description|le\u00edr\u00e1s|stats for nerds|statisztika|about|n\u00e9vjegy)",'i')],["eyeoff",new RegExp("^(hide|elrejt\u00e9s)",'i')],["remix",new RegExp("^(remix)",'i')],["clip",new RegExp("^(clip|klip)",'i')],["explore",new RegExp("^(explore|felfedez\u00e9s)",'i')],["shorts",new RegExp("^(shorts)$",'i')],["home",new RegExp("^(home|kezd\u0151lap)$",'i')],["subs",new RegExp("^(subscriptions|feliratkoz\u00e1sok)$",'i')],["you",new RegExp("^(you|te|library|k\u00f6nyvt\u00e1r)$",'i')]];
    var rs=root.querySelectorAll('ytm-menu-service-item-renderer,ytm-menu-navigation-item-renderer,yt-list-item-view-model,ytmusic-menu-navigation-item-renderer,ytmusic-menu-service-item-renderer,ytmusic-toggle-menu-service-item-renderer,ytmusic-guide-entry-renderer,ytm-guide-entry-renderer,ytm-compact-link-renderer,[role="menuitem"]');
    for(var i=0;i<rs.length&&i<60;i++){
      var r=rs[i],t=(r.textContent||'').replace(/\s+/g,' ').trim();
      if(r.__vdIT===t)continue;r.__vdIT=t;
      var k=null;for(var m=0;m<MAP.length;m++){if(MAP[m][1].test(t)){k=MAP[m][0];break;}}
      if(!k)continue;
      var ic=r.querySelector('.yt-spec-button-shape-next__icon,c3-icon,yt-icon');
      if(!ic||ic.closest('.vd-vm-box'))continue;
      var v='var(--vi-'+k+') center/contain no-repeat';
      ic.style.setProperty('-webkit-mask',v,'important');ic.style.setProperty('mask',v,'important');
      ic.style.setProperty('background-color','currentColor','important');
      ic.classList.add('vd-ico');
    }
  }catch(e){}
};
// ---- VIDARA GLASS téma: saját kinézet (lila üveg, Outfit betű, kártyák) a YouTube felületén
try{document.documentElement.classList.add('vd-theme');}catch(e){}
(function(){
  var CSS="html.vd-theme{--yt-spec-base-background:transparent;--yt-spec-raised-background:rgba(30,16,58,.72);--yt-spec-menu-background:rgba(30,16,58,.92);--yt-spec-general-background-a:transparent;--yt-spec-general-background-b:rgba(255,255,255,.04);--yt-spec-general-background-c:rgba(255,255,255,.06);--yt-spec-brand-background-solid:rgba(20,10,42,.85);--yt-spec-brand-background-primary:rgba(20,10,42,.85);--yt-spec-brand-background-secondary:rgba(30,16,58,.85);--yt-spec-snackbar-background:rgba(30,16,58,.95);--yt-spec-badge-chip-background:rgba(255,255,255,.08);--yt-spec-10-percent-layer:rgba(196,165,255,.16);--yt-spec-static-brand-red:#a855f7;--yt-spec-brand-button-background:#a855f7;--yt-spec-call-to-action:#c4a5ff;--yt-spec-themed-blue:#c4a5ff;--yt-spec-text-primary:#f4efff;--yt-spec-text-secondary:#b9acd9;--yt-spec-outline:rgba(196,165,255,.18);--yt-spec-icon-active-other:#f4efff;--ytmusic-background:transparent;--ytmusic-general-background-a:transparent;--ytmusic-general-background-b:rgba(255,255,255,.04);--ytmusic-general-background-c:rgba(255,255,255,.06);--ytmusic-brand-background-solid:rgba(20,10,42,.85);--ytmusic-player-bar-background:rgba(22,12,46,.78);--ytmusic-color-white1:#f4efff;--ytmusic-color-white2:#b9acd9;--paper-slider-active-color:#a855f7;--paper-slider-knob-color:#e0c3ff;--paper-slider-secondary-color:rgba(196,165,255,.35);}html.vd-theme,html.vd-theme body{background:transparent!important;background-color:transparent!important;color:#f4efff}html.vd-theme{background:#0d0720 linear-gradient(180deg,#1d0d3d 0,#0d0720 420px) no-repeat!important}html.vd-theme ytm-app,html.vd-theme #app,html.vd-theme .page-container,html.vd-theme ytm-browse,html.vd-theme ytm-single-column-browse-results-renderer,html.vd-theme ytm-rich-grid-renderer,html.vd-theme ytm-section-list-renderer,html.vd-theme ytm-search,html.vd-theme ytm-item-section-renderer,html.vd-theme ytmusic-app,html.vd-theme ytmusic-app-layout,html.vd-theme #layout,html.vd-theme ytmusic-browse-response,html.vd-theme ytmusic-section-list-renderer,html.vd-theme ytmusic-search-page,html.vd-theme ytmusic-tabbed-search-results-renderer{background:transparent!important}html.vd-theme body,html.vd-theme body *{font-family:VidaraOutfit,Roboto,Arial,sans-serif!important}html.vd-theme h3,html.vd-theme .media-item-headline,html.vd-theme .compact-media-item-headline,html.vd-theme .slim-video-information-title,html.vd-theme yt-formatted-string.title,html.vd-theme .YtmCompactMediaItemHeadline{font-weight:600!important}html.vd-theme ytm-rich-item-renderer{box-sizing:border-box!important;max-width:calc(100% - 24px)!important;margin:10px 12px!important;padding:8px!important;background:rgba(255,255,255,.045)!important;border:1px solid rgba(196,165,255,.12)!important;border-radius:26px!important;box-shadow:none!important}html.vd-theme ytm-video-with-context-renderer:not(ytm-rich-item-renderer *),html.vd-theme ytm-compact-video-renderer,html.vd-theme ytm-compact-radio-renderer,html.vd-theme ytm-compact-playlist-renderer{margin:6px 10px!important;padding:6px!important;background:rgba(255,255,255,.04)!important;border:1px solid rgba(196,165,255,.10)!important;border-radius:20px!important}html.vd-theme ytm-thumbnail-cover,html.vd-theme .media-item-thumbnail-container,html.vd-theme .video-thumbnail-container-large,html.vd-theme .video-thumbnail-container-compact,html.vd-theme yt-thumbnail-view-model,html.vd-theme .yt-thumbnail-view-model,html.vd-theme .compact-media-item-image,html.vd-theme ytm-shorts-lockup-view-model .shortsLockupViewModelHostThumbnailContainer,html.vd-theme ytm-reel-item-renderer .video-thumbnail-container-vertical{border-radius:20px!important;overflow:hidden!important}html.vd-theme .yt-core-image,html.vd-theme img.video-thumbnail-img{border-radius:20px!important}html.vd-theme ytm-chip-cloud-chip-renderer .chip-container,html.vd-theme ytm-chip-cloud-chip-renderer,html.vd-theme yt-chip-cloud-chip-renderer,html.vd-theme ytmusic-chip-cloud-chip-renderer a,html.vd-theme ytmusic-chip-cloud-chip-renderer .gradient-box{background:rgba(255,255,255,.08)!important;border:1px solid rgba(196,165,255,.18)!important;border-radius:999px!important;color:#e9e1ff!important}html.vd-theme ytm-chip-cloud-chip-renderer[selected] .chip-container,html.vd-theme ytm-chip-cloud-chip-renderer .chip-container.selected,html.vd-theme ytm-chip-cloud-chip-renderer[aria-selected='true'] .chip-container,html.vd-theme ytm-chip-cloud-chip-renderer .chip-container[aria-selected='true'],html.vd-theme ytmusic-chip-cloud-chip-renderer[is-selected] a,html.vd-theme ytmusic-chip-cloud-chip-renderer[chip-style='STYLE_PRIMARY'] a{background:linear-gradient(135deg,#a855f7,#6366f1)!important;border-color:transparent!important;color:#fff!important}html.vd-theme ytm-feed-filter-chip-bar-renderer,html.vd-theme ytm-chip-cloud-renderer,html.vd-theme ytmusic-chip-cloud-renderer{background:transparent!important}html.vd-theme ytm-mobile-topbar-renderer,html.vd-theme .mobile-topbar-header,html.vd-theme ytmusic-nav-bar,html.vd-theme ytmusic-nav-bar #nav-bar-background{background:rgba(18,10,38,.96)!important;border-bottom:1px solid rgba(196,165,255,.10)!important}html.vd-theme ytm-pivot-bar-renderer{background:rgba(22,12,46,.96)!important;-webkit-backdrop-filter:none!important;backdrop-filter:none!important;border-top:1px solid rgba(196,165,255,.14)!important;border-radius:22px 22px 0 0!important}html.vd-theme ytm-pivot-bar-item-renderer [aria-selected='true'],html.vd-theme ytm-pivot-bar-item-renderer .pivot-bar-item-tab[aria-selected='true'],html.vd-theme ytm-pivot-bar-item-renderer [aria-selected='true'] *{color:#d6bcff!important;fill:#d6bcff!important}html.vd-theme ytmusic-player-bar{background:rgba(26,14,52,.97)!important;border-top:1px solid rgba(196,165,255,.14)!important;border-radius:20px 20px 0 0!important}html.vd-theme .yt-spec-button-shape-next--tonal,html.vd-theme .yt-spec-button-shape-next--mono.yt-spec-button-shape-next--tonal,html.vd-theme ytm-slim-owner-renderer button,html.vd-theme ytmusic-player-page tp-yt-paper-icon-button{background:rgba(255,255,255,.09)!important;border:1px solid rgba(196,165,255,.16)!important}html.vd-theme .yt-spec-button-shape-next--filled,html.vd-theme .yt-spec-button-shape-next--call-to-action,html.vd-theme .yt-spec-button-shape-next--mono.yt-spec-button-shape-next--filled{background:linear-gradient(135deg,#a855f7,#6366f1)!important;color:#fff!important;border:0!important}html.vd-theme ytmusic-play-button-renderer,html.vd-theme ytmusic-player-bar #play-pause-button{--ytmusic-play-button-background-color:#a855f7;--ytmusic-play-button-icon-color:#fff}html.vd-theme .ytp-play-progress,html.vd-theme .ytp-swatch-background-color,html.vd-theme .ytProgressBarLineProgressBarPlayed,html.vd-theme .YtProgressBarLineProgressBarPlayed,html.vd-theme .ytm-progress-bar-played,html.vd-theme .thumbnail-overlay-resume-playback-progress,html.vd-theme .ytThumbnailOverlayProgressBarHostWatchedProgressBarSegment{background:linear-gradient(90deg,#a855f7,#6366f1)!important}html.vd-theme .ytp-scrubber-button,html.vd-theme .ytProgressBarPlayheadProgressBarPlayheadDot{background:#e0c3ff!important}html.vd-theme ytm-slim-video-metadata-section-renderer,html.vd-theme ytm-comments-entry-point-header-renderer,html.vd-theme ytm-slim-video-action-bar-renderer,html.vd-theme ytm-slim-owner-renderer,html.vd-theme ytm-structured-description-content-renderer,html.vd-theme ytm-engagement-panel-section-list-renderer{background:transparent!important}html.vd-theme ytm-comments-entry-point-header-renderer,html.vd-theme ytm-slim-video-description-renderer{display:block;margin:8px 12px!important;padding:12px!important;background:rgba(255,255,255,.05)!important;border:1px solid rgba(196,165,255,.12)!important;border-radius:22px!important}html.vd-theme ytmusic-two-row-item-renderer img,html.vd-theme ytmusic-two-row-item-renderer yt-img-shadow,html.vd-theme ytmusic-responsive-list-item-renderer yt-img-shadow,html.vd-theme ytmusic-responsive-list-item-renderer img,html.vd-theme ytmusic-player #song-image img{border-radius:16px!important}html.vd-theme ytmusic-responsive-list-item-renderer{border-radius:16px!important}html.vd-theme a.yt-core-attributed-string__link,html.vd-theme .yt-core-attributed-string--link-inherit-color a{color:#c4a5ff!important}html.vd-theme ::selection{background:rgba(168,85,247,.45)}html.vd-theme{--vi-home:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M3.5 10.8 12 3.8l8.5 7v8.4a1.3 1.3 0 0 1-1.3 1.3H15v-6.2H9v6.2H4.8a1.3 1.3 0 0 1-1.3-1.3z' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-shorts:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M13.2 2.8 5 13.6h6.4l-.8 7.6 8.4-11h-6.6z' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-subs:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M5 8.5h14a1.5 1.5 0 0 1 1.5 1.5v8.5A1.5 1.5 0 0 1 19 20H5a1.5 1.5 0 0 1-1.5-1.5V10A1.5 1.5 0 0 1 5 8.5zM6.5 5.5h11M8.5 3h7' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3Cpath d='M10.5 11.6v5.3l4.4-2.65z' fill='%23000'/%3E%3C/svg%3E\");--vi-you:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 12.2a4.1 4.1 0 1 0 0-8.2 4.1 4.1 0 0 0 0 8.2zM4.5 20.5a7.5 7.5 0 0 1 15 0' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-search:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M10.8 17.6a6.8 6.8 0 1 0 0-13.6 6.8 6.8 0 0 0 0 13.6zM20.5 20.5l-4.9-4.9' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-back:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M15 4.5 7.5 12l7.5 7.5' fill='none' stroke='%23000' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-close:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M6.5 6.5l11 11M17.5 6.5l-11 11' fill='none' stroke='%23000' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-more:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M5.5 12h.01M12 12h.01M18.5 12h.01' fill='none' stroke='%23000' stroke-width='3.4' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-menu:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M4 7h16M4 12h16M4 17h10' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-explore:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18z' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3Cpath d='M15.8 8.2 13.6 13.6 8.2 15.8 10.4 10.4z' fill='%23000'/%3E%3C/svg%3E\");--vi-mic:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 14.5a3 3 0 0 0 3-3V6a3 3 0 0 0-6 0v5.5a3 3 0 0 0 3 3zM18.5 11a6.5 6.5 0 0 1-13 0M12 17.5V21' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-cast:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M3.5 17.5a3.5 3.5 0 0 1 3 3M3.5 13.5a7.5 7.5 0 0 1 7 7M3.5 9V6.5A1.5 1.5 0 0 1 5 5h14a1.5 1.5 0 0 1 1.5 1.5v11A1.5 1.5 0 0 1 19 19h-5' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-bell:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M18 16.5V11a6 6 0 0 0-12 0v5.5L4.5 18h15zM10 21h4' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-settings:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M4 7h9M17 7h3M4 17h3M11 17h9M15 4.5v5M9 14.5v5' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-like:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 20.2s-7.2-4.3-8.9-9.1A4.9 4.9 0 0 1 12 7.3a4.9 4.9 0 0 1 8.9 3.8C19.2 15.9 12 20.2 12 20.2z' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-dislike:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 20.2s-7.2-4.3-8.9-9.1A4.9 4.9 0 0 1 12 7.3a4.9 4.9 0 0 1 8.9 3.8C19.2 15.9 12 20.2 12 20.2zM12.6 9.6l-2 3.4h3l-2 3.6' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-share:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 3.5v12M7.5 8 12 3.5 16.5 8M5 13.5v5.2A1.8 1.8 0 0 0 6.8 20.5h10.4a1.8 1.8 0 0 0 1.8-1.8v-5.2' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-save:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M7 3.5h10a1 1 0 0 1 1 1v16l-6-4-6 4v-16a1 1 0 0 1 1-1z' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-download:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 3.5v11.5M7.5 10.5 12 15l4.5-4.5M5 20.5h14' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-clip:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M6.5 9a2.8 2.8 0 1 0 0-5.6 2.8 2.8 0 0 0 0 5.6zM6.5 20.6a2.8 2.8 0 1 0 0-5.6 2.8 2.8 0 0 0 0 5.6zM8.8 7.6 20 18.5M8.8 16.4 20 5.5' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-remix:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M11 3.5l1.7 4.9 4.9 1.7-4.9 1.7L11 16.7l-1.7-4.9-4.9-1.7 4.9-1.7zM18 15l.8 2.2 2.2.8-2.2.8L18 21l-.8-2.2-2.2-.8 2.2-.8z' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-comment:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M5 4.5h14A1.5 1.5 0 0 1 20.5 6v9A1.5 1.5 0 0 1 19 16.5H9.5l-5 4V6A1.5 1.5 0 0 1 5 4.5z' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-play:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M7.5 4.6v14.8a1.2 1.2 0 0 0 1.8 1l11.6-7.4a1.2 1.2 0 0 0 0-2L9.3 3.6a1.2 1.2 0 0 0-1.8 1z' fill='%23000'/%3E%3C/svg%3E\");--vi-pause:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M7.6 4h2.3A1.1 1.1 0 0 1 11 5.1v13.8A1.1 1.1 0 0 1 9.9 20H7.6a1.1 1.1 0 0 1-1.1-1.1V5.1A1.1 1.1 0 0 1 7.6 4zM14.1 4h2.3a1.1 1.1 0 0 1 1.1 1.1v13.8a1.1 1.1 0 0 1-1.1 1.1h-2.3a1.1 1.1 0 0 1-1.1-1.1V5.1A1.1 1.1 0 0 1 14.1 4z' fill='%23000'/%3E%3C/svg%3E\");--vi-next:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M5 5.4v13.2a1 1 0 0 0 1.6.8l9-6.6a1 1 0 0 0 0-1.6l-9-6.6A1 1 0 0 0 5 5.4z' fill='%23000'/%3E%3Cpath d='M19 5v14' fill='none' stroke='%23000' stroke-width='2.4' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-prev:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M19 5.4v13.2a1 1 0 0 1-1.6.8l-9-6.6a1 1 0 0 1 0-1.6l9-6.6a1 1 0 0 1 1.6.8z' fill='%23000'/%3E%3Cpath d='M5 5v14' fill='none' stroke='%23000' stroke-width='2.4' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-fullscreen:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M4 9V5.2A1.2 1.2 0 0 1 5.2 4H9M15 4h3.8A1.2 1.2 0 0 1 20 5.2V9M20 15v3.8a1.2 1.2 0 0 1-1.2 1.2H15M9 20H5.2A1.2 1.2 0 0 1 4 18.8V15' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-exitfs:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M9 4v3.8A1.2 1.2 0 0 1 7.8 9H4M20 9h-3.8A1.2 1.2 0 0 1 15 7.8V4M15 20v-3.8a1.2 1.2 0 0 1 1.2-1.2H20M4 15h3.8A1.2 1.2 0 0 1 9 16.2V20' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-cc:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M5 5.5h14A1.5 1.5 0 0 1 20.5 7v10a1.5 1.5 0 0 1-1.5 1.5H5A1.5 1.5 0 0 1 3.5 17V7A1.5 1.5 0 0 1 5 5.5zM10.5 10.2a2 2 0 1 0 0 3.6M17 10.2a2 2 0 1 0 0 3.6' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-shuffle:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M3.5 7h2.8c4.2 0 6 10 10.2 10h4M3.5 17h2.8c1.6 0 2.7-1.4 3.7-3.1M14 10.1C15 8.4 16.1 7 17.7 7h2.8M18 4.2 20.8 7 18 9.8M18 14.2l2.8 2.8-2.8 2.8' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-repeat:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M17 2.8 19.8 5.6 17 8.4M4.5 11.2V9.6a4 4 0 0 1 4-4h11.3M7 21.2 4.2 18.4 7 15.6M19.5 12.8v1.6a4 4 0 0 1-4 4H4.2' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-volume:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M4.5 9.2h3.3l4.7-4v13.6l-4.7-4H4.5zM16 9.2a4 4 0 0 1 0 5.6M18.8 6.4a8 8 0 0 1 0 11.2' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-queue:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M4 6h12M4 11.5h12M4 17h7' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3Cpath d='M15 14.2v6.6l5.4-3.3z' fill='%23000'/%3E%3C/svg%3E\");--vi-plus:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 5v14M5 12h14' fill='none' stroke='%23000' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-flag:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M5.5 21V4M5.5 4.5h11l-2.2 4 2.2 4h-11' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-check:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M5 12.5l4.6 4.5L19 7.5' fill='none' stroke='%23000' stroke-width='2.2' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-playlist:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M4 6.5h11M4 11.5h11M4 16.5h6M18.5 16.5a2 2 0 1 1-2-2 2 2 0 0 1 2 2zM18.5 16.5V7l2.5-1' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-radio:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 13.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3zM8.5 15.5a5 5 0 0 1 0-7M15.5 8.5a5 5 0 0 1 0 7M5.6 18.4a9 9 0 0 1 0-12.8M18.4 5.6a9 9 0 0 1 0 12.8' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-trash:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M4.5 7h15M9.5 7V4.8h5V7M6.5 7l.8 12.2a1.5 1.5 0 0 0 1.5 1.3h6.4a1.5 1.5 0 0 0 1.5-1.3L17.5 7' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-eyeoff:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M3.5 3.5l17 17M10.6 6.2A9.8 9.8 0 0 1 12 6c5.5 0 9 6 9 6a15.6 15.6 0 0 1-2.8 3.4M6.3 7.9A15.7 15.7 0 0 0 3 12s3.5 6 9 6a8.8 8.8 0 0 0 3.6-.8M9.9 10a3 3 0 0 0 4.1 4.1' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-speed:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 20.5a8.5 8.5 0 1 1 8.5-8.5M12 12l4.5-3.5' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-help:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM9.6 9.4a2.5 2.5 0 0 1 4.8.9c0 1.7-2.4 2.2-2.4 3.7M12 17h.01' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");--vi-info:url(\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 24 24'%3E%3Cpath d='M12 21a9 9 0 1 0 0-18 9 9 0 0 0 0 18zM12 11v6M12 7.5h.01' fill='none' stroke='%23000' stroke-width='2.0' stroke-linecap='round' stroke-linejoin='round'/%3E%3C/svg%3E\");}html.vd-theme ytmusic-player-page,html.vd-theme ytmusic-player-page #main-panel,html.vd-theme ytmusic-player-page #side-panel{background:transparent!important}html.vd-theme ytmusic-carousel-shelf-basic-header-renderer h2,html.vd-theme ytmusic-carousel-shelf-basic-header-renderer .title,html.vd-theme ytmusic-shelf-renderer h2,html.vd-theme ytmusic-header-renderer h2{font-weight:700!important;font-size:22px!important;letter-spacing:-.2px!important;color:#f4efff!important}html.vd-theme ytmusic-carousel-shelf-basic-header-renderer .strapline{color:#c4a5ff!important;text-transform:uppercase;letter-spacing:1.2px!important;font-weight:600!important}html.vd-theme ytmusic-player-page .title.ytmusic-player-controls,html.vd-theme ytmusic-player-page yt-formatted-string.title{font-size:22px!important;font-weight:700!important}html.vd-theme ytmusic-player-page .byline,html.vd-theme ytmusic-player-page .subtitle{color:#c4a5ff!important}html.vd-theme ytmusic-two-row-item-renderer{background:rgba(255,255,255,.05)!important;border:1px solid rgba(196,165,255,.12)!important;border-radius:22px!important;padding:8px!important;box-sizing:border-box}html.vd-theme ytmusic-responsive-list-item-renderer{background:rgba(255,255,255,.035)!important;border-radius:16px!important;margin:3px 6px!important}html.vd-theme ytmusic-player-queue-item{border-radius:16px!important;margin:2px 6px!important}html.vd-theme ytmusic-player-queue-item[selected],html.vd-theme ytmusic-player-queue-item[play-button-state='playing'],html.vd-theme ytmusic-player-queue-item[play-button-state='paused']{background:rgba(168,85,247,.22)!important;border:1px solid rgba(196,165,255,.25)!important}html.vd-theme tp-yt-paper-tabs #selectionBar{display:none!important}html.vd-theme tp-yt-paper-tabs tp-yt-paper-tab.iron-selected,html.vd-theme tp-yt-paper-tabs tp-yt-paper-tab[aria-selected='true']{background:linear-gradient(135deg,#a855f7,#6366f1)!important;border-radius:999px!important;color:#fff!important;margin:6px 2px!important}html.vd-theme ytmusic-description-shelf-renderer .description,html.vd-theme ytmusic-description-shelf-renderer yt-formatted-string.non-expandable{font-size:20px!important;line-height:1.65!important;font-weight:600!important;color:#f4efff!important;text-align:center!important;text-shadow:0 0 18px rgba(168,85,247,.35)}html.vd-theme ytmusic-player-bar{left:8px!important;right:8px!important;width:auto!important;border:1px solid rgba(196,165,255,.18)!important;border-radius:22px!important}html.vd-theme ytmusic-pivot-bar-renderer,html.vd-theme ytmusic-app-layout #pivot-bar{background:rgba(22,12,46,.96)!important;border-top:1px solid rgba(196,165,255,.14)!important}html.vd-theme ytmusic-pivot-bar-item-renderer.iron-selected,html.vd-theme ytmusic-pivot-bar-item-renderer[aria-selected='true']{color:#d6bcff!important}html.vd-theme ytmusic-player-page #play-pause-button,html.vd-theme ytmusic-player-page .play-pause-button{background:linear-gradient(135deg,#a855f7,#6366f1)!important;border-radius:999px!important;color:#fff!important;box-shadow:0 8px 24px rgba(124,58,237,.45)!important}html.vd-theme ytmusic-play-button-renderer{--ytmusic-play-button-background-color:#a855f7!important;--ytmusic-play-button-active-background-color:#7c3aed!important;--ytmusic-play-button-icon-color:#fff!important}html.vd-theme ytmusic-player #song-image img,html.vd-theme ytmusic-player #song-image yt-img-shadow{border-radius:24px!important}html.vd-theme ytmusic-player:not(.vd-fixed) #song-image{border-radius:24px!important;box-shadow:0 20px 50px rgba(8,2,24,.55)!important}.vd-ico>*,.vd-ico svg{opacity:0!important}html.vd-theme ytmusic-player-page{background:linear-gradient(180deg,rgba(13,7,32,.55),rgba(13,7,32,.88)),var(--vd-art,none) center/cover no-repeat!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='like' i],[aria-label^='tetszik' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-like) center/contain no-repeat!important;mask:var(--vi-like) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='like' i],[aria-label^='tetszik' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='like' i],[aria-label^='tetszik' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='dislike' i],[aria-label^='nem tetszik' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-dislike) center/contain no-repeat!important;mask:var(--vi-dislike) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='dislike' i],[aria-label^='nem tetszik' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='dislike' i],[aria-label^='nem tetszik' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='share' i],[aria-label^='megoszt\u00e1s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-share) center/contain no-repeat!important;mask:var(--vi-share) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='share' i],[aria-label^='megoszt\u00e1s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='share' i],[aria-label^='megoszt\u00e1s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='save' i],[aria-label^='ment\u00e9s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-save) center/contain no-repeat!important;mask:var(--vi-save) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='save' i],[aria-label^='ment\u00e9s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='save' i],[aria-label^='ment\u00e9s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='download' i],[aria-label^='let\u00f6lt\u00e9s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-download) center/contain no-repeat!important;mask:var(--vi-download) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='download' i],[aria-label^='let\u00f6lt\u00e9s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='download' i],[aria-label^='let\u00f6lt\u00e9s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='clip' i],[aria-label^='klip' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-clip) center/contain no-repeat!important;mask:var(--vi-clip) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='clip' i],[aria-label^='klip' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='clip' i],[aria-label^='klip' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='remix' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-remix) center/contain no-repeat!important;mask:var(--vi-remix) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='remix' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='remix' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='report' i],[aria-label^='jelent\u00e9s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-flag) center/contain no-repeat!important;mask:var(--vi-flag) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='report' i],[aria-label^='jelent\u00e9s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='report' i],[aria-label^='jelent\u00e9s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='more actions' i],[aria-label^='action menu' i],[aria-label^='tov\u00e1bbi' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-more) center/contain no-repeat!important;mask:var(--vi-more) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='more actions' i],[aria-label^='action menu' i],[aria-label^='tov\u00e1bbi' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='more actions' i],[aria-label^='action menu' i],[aria-label^='tov\u00e1bbi' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='captions' i],[aria-label^='subtitles' i],[aria-label^='feliratok' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-cc) center/contain no-repeat!important;mask:var(--vi-cc) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='captions' i],[aria-label^='subtitles' i],[aria-label^='feliratok' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='captions' i],[aria-label^='subtitles' i],[aria-label^='feliratok' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='settings' i],[aria-label^='player settings' i],[aria-label^='be\u00e1ll\u00edt\u00e1sok' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-settings) center/contain no-repeat!important;mask:var(--vi-settings) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='settings' i],[aria-label^='player settings' i],[aria-label^='be\u00e1ll\u00edt\u00e1sok' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='settings' i],[aria-label^='player settings' i],[aria-label^='be\u00e1ll\u00edt\u00e1sok' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='full screen' i],[aria-label^='enter full screen' i],[aria-label^='teljes k\u00e9perny\u0151' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-fullscreen) center/contain no-repeat!important;mask:var(--vi-fullscreen) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='full screen' i],[aria-label^='enter full screen' i],[aria-label^='teljes k\u00e9perny\u0151' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='full screen' i],[aria-label^='enter full screen' i],[aria-label^='teljes k\u00e9perny\u0151' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='exit full screen' i],[aria-label^='kil\u00e9p\u00e9s a teljes' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-exitfs) center/contain no-repeat!important;mask:var(--vi-exitfs) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='exit full screen' i],[aria-label^='kil\u00e9p\u00e9s a teljes' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='exit full screen' i],[aria-label^='kil\u00e9p\u00e9s a teljes' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='play video' i],[aria-label^='play (' i],[aria-label^='lej\u00e1tsz\u00e1s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-play) center/contain no-repeat!important;mask:var(--vi-play) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='play video' i],[aria-label^='play (' i],[aria-label^='lej\u00e1tsz\u00e1s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='play video' i],[aria-label^='play (' i],[aria-label^='lej\u00e1tsz\u00e1s' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='pause' i],[aria-label^='sz\u00fcnet' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-pause) center/contain no-repeat!important;mask:var(--vi-pause) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='pause' i],[aria-label^='sz\u00fcnet' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='pause' i],[aria-label^='sz\u00fcnet' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='next' i],[aria-label^='k\u00f6vetkez\u0151' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-next) center/contain no-repeat!important;mask:var(--vi-next) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='next' i],[aria-label^='k\u00f6vetkez\u0151' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='next' i],[aria-label^='k\u00f6vetkez\u0151' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='previous' i],[aria-label^='el\u0151z\u0151' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon){-webkit-mask:var(--vi-prev) center/contain no-repeat!important;mask:var(--vi-prev) center/contain no-repeat!important;background-color:currentColor!important}:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='previous' i],[aria-label^='el\u0151z\u0151' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon))>*,:is(html.vd-theme :is(ytm-slim-video-action-bar-renderer,#player-control-container,.player-controls-content,.html5-video-player) :is(button,a,[role='button']):is([aria-label^='previous' i],[aria-label^='el\u0151z\u0151' i]) :is(.yt-spec-button-shape-next__icon,c3-icon,yt-icon)) svg{opacity:0!important}";
  CSS+="@media (min-width:820px) and (max-width:1099px){html.vd-2p ytm-watch{display:grid!important;grid-template-columns:calc((100vw - 34px) * 0.6) minmax(0,1fr)!important;grid-template-rows:calc((100vw - 34px) * 0.3375) auto auto!important;column-gap:12px!important;row-gap:0!important;align-items:start!important;padding:0 11px 12px!important;box-sizing:border-box!important}html.vd-2p ytm-watch>div.player-size{grid-column:1!important;grid-row:1!important;width:100%!important;height:calc((100vw - 34px) * 0.3375)!important;position:static!important;overflow:visible!important}html.vd-2p ytm-watch>div.watch-below-the-player,html.vd-2p ytm-watch>div.watch-below-the-player>ytm-single-column-watch-next-results-renderer{display:contents!important}html.vd-2p ytm-watch>.watch-below-the-player>.below-the-player-cinematic-container{display:none!important}html.vd-2p ytm-watch ytm-companion-slot{display:none!important}html.vd-2p ytm-watch ytm-slim-video-metadata-section-renderer{grid-column:1!important;grid-row:2!important;margin-top:6px!important}html.vd-2p ytm-watch ytm-item-section-renderer.scwnr-content{grid-column:1!important;grid-row:3!important}html.vd-2p ytm-watch div.related-items-container{grid-column:2!important;grid-row:1 / span 3!important;position:sticky!important;top:0!important;width:auto!important;max-height:calc(100vh - 56px)!important;overflow-y:auto!important;overscroll-behavior:contain;padding:0 2px 10px!important}html.vd-2p #player-container-id{margin-left:11px!important;right:auto!important;border-radius:18px!important;overflow:hidden!important}html.vd-2p ytm-watch ytm-slim-video-metadata-section-renderer{min-width:0!important;max-width:100%!important}html.vd-2p ytm-watch ytm-slim-video-metadata-section-renderer *{max-width:100%!important;min-width:0!important}html.vd-2p ytm-watch .ytVideoMetadataViewModelTitle,html.vd-2p ytm-watch .ytVideoMetadataViewModelTitle *{white-space:normal!important;overflow:visible!important;text-overflow:clip!important}html.vd-2p ytm-watch .ytVideoMetadataViewModelTitle{-webkit-line-clamp:3!important;display:-webkit-box!important;-webkit-box-orient:vertical!important}html.vd-2p div.related-items-container .media-item-thumbnail-container{width:132px!important;min-width:132px!important;max-width:132px!important;flex:0 0 132px!important}html.vd-2p div.related-items-container .media-item-details{flex:1 1 auto!important;min-width:0!important;width:auto!important;max-width:none!important}}";
  CSS+="@media (min-width:600px){html.vd-wide ytm-rich-grid-renderer{display:block!important;width:auto!important}html.vd-wide .rich-grid-renderer-contents{display:grid!important;grid-template-columns:repeat(auto-fill,minmax(290px,1fr))!important;gap:12px!important;width:auto!important;padding:0 12px 12px!important;box-sizing:border-box!important}html.vd-wide ytm-rich-item-renderer{display:block!important;width:auto!important;min-width:0!important;max-width:none!important;margin:0!important;box-sizing:border-box!important}html.vd-wide .rich-grid-renderer-contents > :not(ytm-rich-item-renderer){grid-column:1 / -1!important}}";
  CSS+="@media (min-width:600px) and (max-width:1099px){html.vd-wide div.related-items-container lazy-list{display:block!important;width:auto!important}html.vd-wide div.related-items-container ytm-video-with-context-renderer{width:auto!important;max-width:none!important;display:block!important;margin:0 12px!important}html.vd-wide div.related-items-container ytm-media-item{width:auto!important;max-width:none!important}}";
  CSS+="html.vd-theme ytmusic-menu-popup-renderer,html.vd-theme ytm-menu-popup-renderer,html.vd-theme ytm-multi-page-menu-renderer,html.vd-theme ytmusic-multi-page-menu-renderer,html.vd-theme ytmusic-popup-container .dropdown-content,html.vd-theme tp-yt-iron-dropdown .dropdown-content{background:rgba(26,14,52,.985)!important;border:1px solid rgba(196,165,255,.22)!important;border-radius:24px!important;box-shadow:0 24px 60px rgba(4,1,14,.6)!important;overflow:hidden!important}html.vd-theme ytmusic-menu-service-item-renderer,html.vd-theme ytmusic-menu-navigation-item-renderer,html.vd-theme ytm-menu-service-item-renderer,html.vd-theme ytm-menu-navigation-item-renderer{border-radius:14px!important;margin:2px 6px!important}html.vd-theme ytmusic-menu-service-item-renderer:hover,html.vd-theme ytmusic-menu-navigation-item-renderer:hover,html.vd-theme ytm-menu-service-item-renderer:hover{background:rgba(168,85,247,.16)!important}";
  function put(){
    try{
      var t=document.getElementById('vd-theme');
      if(!t){t=document.createElement('style');t.id='vd-theme';t.textContent=CSS;}
      var h=document.head||document.documentElement;
      if(t.parentNode!==h||h.lastElementChild!==t)h.appendChild(t);   // mindig az utolsó stílus: ez nyer
      var f=null;try{f=localStorage.getItem('vd_font_css');}catch(x){}
      if(f&&!document.getElementById('vd-font')){var fs=document.createElement('style');fs.id='vd-font';fs.textContent=f;h.appendChild(fs);}
    }catch(e){}
  }
  window.__vdFontSet=function(c){try{localStorage.setItem('vd_font_css',c);}catch(x){}var o=document.getElementById('vd-font');if(o)o.textContent=c;else{put();if(!document.getElementById('vd-font')){var fs=document.createElement('style');fs.id='vd-font';fs.textContent=c;(document.head||document.documentElement).appendChild(fs);}}};
  put();
  document.addEventListener('DOMContentLoaded',put);
  setTimeout(put,800);setTimeout(put,3000);
})();
// Shorts: lapozáskor NINCS befagyasztott kép – a YouTube saját, folyamatos görgetése látszik
// Alsó menüsor (Kezdőlap / Shorts): a képernyő legaljára kerül, felesleges alsó térköz nélkül
setInterval(function(){
  try{
    var p=document.querySelector('ytm-pivot-bar-renderer');
    if(!p||!p.getBoundingClientRect)return;
    var r=p.getBoundingClientRect();
    if(r.height<10||r.width<10)return;
    function set(e,k,v){if(e.style.getPropertyValue(k)!==v||e.style.getPropertyPriority(k)!=='important')e.style.setProperty(k,v,'important');}
    var c=p,fx=null;
    for(var i=0;i<6&&c&&c!==document.body;i++){
      set(c,'margin-bottom','0px');set(c,'padding-bottom','0px');
      var cs=getComputedStyle(c);
      if(cs.position==='fixed'||cs.position==='sticky'){fx=c;set(c,'bottom','0px');break;}
      c=c.parentElement;
    }
    if(fx){
      // ha még mindig marad hely alatta (pl. lebegő "üveg" sáv), lejjebb toljuk
      var g=Math.round(window.innerHeight-p.getBoundingClientRect().bottom);
      var cur=parseFloat(fx.__vdDy||0);
      if(Math.abs(g)>1){var dy=Math.max(0,cur+g);fx.__vdDy=dy;set(fx,'transform','translateY('+dy+'px)');}
    }
  }catch(e){}
},700);
// Shorts-váltás: amint az URL másik reelre vált, azonnal jelezzük
setInterval(function(){
  try{
    if(window.__vdLP===location.pathname)return;
    window.__vdLP=location.pathname;
    if(location.pathname.indexOf('/shorts/')===0){try{VidaraApp.reelUrl(location.href);}catch(e){}}
    var hp=location.pathname||'/';
    document.documentElement.classList.toggle('vd-home',location.hostname==='m.youtube.com'&&(hp==='/'||hp===''||hp==='/feed/trending'));
  }catch(e){}
},100);
// Shorts: a következő videók poszterképeit előre letöltjük (gyors, fekete mentes váltás)
setInterval(function(){
  try{
    if(location.pathname.indexOf('/shorts')!==0)return;
    var as=document.querySelectorAll('a[href*="/shorts/"]'), out=[];
    for(var i=0;i<as.length&&out.length<6;i++){
      var m=(as[i].getAttribute('href')||'').match(/\/shorts\/([A-Za-z0-9_-]{6,})/);
      if(m&&out.indexOf(m[1])<0)out.push(m[1]);
    }
    if(out.length)try{VidaraApp.preload(out.join(','));}catch(e){}
  }catch(e){}
},900);
// Kategória-sáv (All / Gaming / Live …) a kezdőlapon: CSAK a lap tetején, a helyén látszik.
// Görgetéskor azonnal (CSS-osztállyal, MINDEN példányra) elrejtjük – a visszalépés után a YouTube új sávot rajzol.
window.__vdChipEl=function(){
  try{
    var cs=document.querySelectorAll('ytm-chip-cloud-chip-renderer,ytm-chip-cloud-renderer');
    for(var i=0;i<cs.length;i++){var r=cs[i].getBoundingClientRect();if(r.height>4&&r.width>4)return cs[i];}
  }catch(e){}
  return null;
};
(function(){
  var lastTop=0;
  function home(){if(location.hostname!=='m.youtube.com')return false;var p=location.pathname||'/';return p==='/'||p===''||p==='/feed/trending';}
  function scrolled(){
    var se=document.scrollingElement||document.documentElement;
    return Math.max(window.scrollY||0,se?se.scrollTop:0,lastTop);
  }
  function upd(){
    try{
      var de=document.documentElement;
      if(!home()){if(de.classList.contains('vd-chipoff'))de.classList.remove('vd-chipoff');return;}
      var off=scrolled()>8;
      if(!off){
        var G=window.__vdChipTop,c=window.__vdChipEl();
        if(c&&G!=null&&G>4){
          if(!(window.__vdShift>0))off=true;
          else if(Math.abs(Math.round(c.getBoundingClientRect().top)-G)>2)off=true;
        }
      }
      if(de.classList.contains('vd-chipoff')!==off)de.classList.toggle('vd-chipoff',off);
    }catch(e){}
  }
  window.__vdChipUpd=upd;
  window.addEventListener('scroll',function(e){
    try{var t=e.target;if(t&&t!==document&&t.nodeType===1&&t.scrollHeight>t.clientHeight+50)lastTop=t.scrollTop||0;else lastTop=0;}catch(x){}
    upd();   // azonnal, ugyanabban a képkockában
  },{passive:true,capture:true});
  setInterval(function(){if(!document.hidden)upd();},900);
})();
// ---- A felfedezés-menü (iránytű gomb az "All" mellett): nincs YouTube név és logó.
// "YouTube Music" -> "VidaraMusic" saját logóval; "YouTube Kids" és "YouTube Create" eltűnik.
(function(){
  var RX=/^(YouTube|Vidara)\s*(Music|Kids|Create|Studio|TV|Premium)$/i;
  function row(el){
    // a sor: az a legkisebb szülő, amelyben ikon is van, és még nem az egész menü
    var cur=el,best=el;
    for(var i=0;i<7;i++){
      var p=cur.parentElement;if(!p||p===document.body)break;
      var r=p.getBoundingClientRect();
      if(r.height>90)break;
      best=p;cur=p;
      if(p.matches&&p.matches('a,button,[role="menuitem"],[role="link"],ytm-compact-link-renderer,ytm-guide-entry-renderer,ytm-menu-navigation-item-renderer'))break;
    }
    return best;
  }
  function icon(r){return r.querySelector('yt-icon,c3-icon,svg,img:not(.vd-vm-logo),.yt-core-image');}
  // VidaraMusic (music.youtube.com) oldalsó menüje (☰): YouTube Music logó -> VidaraMusic logó, "Open app" el
  function music(root){
    if(location.hostname!=='music.youtube.com')return;
    var R=(root&&root.isConnected)?root:document.body;if(!R)return;
    var DR='tp-yt-app-drawer,ytmusic-guide-renderer,#guide-wrapper';
    var doLogo=!root||(root.matches&&root.matches(DR))||(root.closest&&root.closest(DR));
    var L=window.__vdVMLogo||'/__vidara/vmusic.png';
    var cs=doLogo?document.querySelectorAll('img[src*="logo"],ytmusic-logo,.ytmusic-logo,a.logo,#logo,[class*="logo"],[aria-label*="YouTube Music"],[title*="YouTube Music"]'):[];
    var cand=[];
    for(var i=0;i<cs.length;i++){
      var e=cs[i];
      if(e.__vdVM||e.classList&&e.classList.contains('vd-vm-logo')||e.closest('.vd-vm-box'))continue;
      var r=e.getBoundingClientRect();
      if(r.width<8||r.height<8||r.height>70||r.width>260||r.top>140||r.left>320)continue;
      cand.push({e:e,r:r});
    }
    // egymásba ágyazottak közül csak a legkülső marad
    cand=cand.filter(function(c){for(var j=0;j<cand.length;j++){if(cand[j]!==c&&cand[j].e.contains(c.e))return false;}return true;});
    // egy sorban lévő darabok (ikon + "Music" felirat) EGY csoport -> EGY VidaraMusic logó
    cand.sort(function(x,y){return x.r.left-y.r.left;});
    var groups=[];
    for(var g=0;g<cand.length;g++){
      var c=cand[g],cy=c.r.top+c.r.height/2,put=false;
      for(var q=0;q<groups.length;q++){var gy=groups[q].cy;if(Math.abs(gy-cy)<24){groups[q].list.push(c);put=true;break;}}
      if(!put)groups.push({cy:cy,list:[c]});
    }
    for(var q2=0;q2<groups.length;q2++){
      var gl=groups[q2].list,first=gl[0];
      // már van ebben a sorban VidaraMusic logó (korábbi futásból): csak elrejtjük a maradékot
      var have=false,bx=document.querySelectorAll('.vd-vm-box');
      for(var bi=0;bi<bx.length;bi++){var br=bx[bi].getBoundingClientRect();if(br.height>0&&Math.abs(br.top+br.height/2-groups[q2].cy)<24){have=true;break;}}
      if(!have){
        var h=Math.round(Math.min(28,Math.max(20,first.r.height)));
        var box=document.createElement('span');
        box.className='vd-vm-box';
        box.style.cssText='display:inline-flex;align-items:center;gap:8px;white-space:nowrap;pointer-events:none;flex:none';
        var im=document.createElement('img');im.className='vd-vm-logo';im.src=L;
        im.style.cssText='width:'+h+'px;height:'+h+'px;border-radius:'+Math.round(h*0.22)+'px;object-fit:cover;flex:none';
        var tx=document.createElement('span');tx.textContent='VidaraMusic';
        tx.style.cssText='color:#fff;font:700 18px/1 Roboto,Arial,sans-serif;letter-spacing:-0.3px';
        box.appendChild(im);box.appendChild(tx);
        first.e.parentNode.insertBefore(box,first.e);
      }
      for(var k=0;k<gl.length;k++){gl[k].e.__vdVM=true;gl[k].e.style.setProperty('display','none','important');}
    }
    // biztonsági háló: soronként CSAK EGY VidaraMusic logó (a legszélesebb, felirattal együtti marad)
    try{
      var all=[].slice.call(document.querySelectorAll('.vd-vm-box,img.vd-vm-logo')).filter(function(x){if(x.tagName==='IMG'&&x.closest('.vd-vm-box'))return false;var rr=x.getBoundingClientRect();return rr.width>0&&rr.height>0;});
      var rows=[];
      all.forEach(function(x){var rr=x.getBoundingClientRect(),cy=rr.top+rr.height/2,f=null;for(var z=0;z<rows.length;z++){if(Math.abs(rows[z].cy-cy)<24){f=rows[z];break;}}if(!f){f={cy:cy,l:[]};rows.push(f);}f.l.push({x:x,w:rr.width});});
      rows.forEach(function(rw2){if(rw2.l.length<2)return;rw2.l.sort(function(a1,b1){return b1.w-a1.w;});for(var z=1;z<rw2.l.length;z++)rw2.l[z].x.style.setProperty('display','none','important');});
    }catch(e){}
    // ⋮ menü: "Get Music Premium" és "Terms and privacy policy" el; Beállítások: "About" el
    var RM=/^\s*(get music premium|music premium beszerzése|szerezd meg a music premiumot|terms and privacy policy|feltételek és adatvédelem|általános szerződési feltételek és adatvédelmi irányelvek|feltételek és adatvédelmi irányelvek)\s*$/i;
    var RA=/^\s*(about|névjegy|rólunk)\s*$/i;
    var tw=document.createTreeWalker(R,NodeFilter.SHOW_TEXT,{acceptNode:function(n){var v=n.nodeValue||'';return (v.length<80&&(RM.test(v)||RA.test(v)))?NodeFilter.FILTER_ACCEPT:NodeFilter.FILTER_SKIP;}}),tn,hits=[];
    while((tn=tw.nextNode()))hits.push(tn);
    for(var hi=0;hi<hits.length;hi++){
      var te=hits[hi].parentElement;if(!te)continue;
      if(RA.test(hits[hi].nodeValue||'')){
        // csak a Beállítások ablakban (ne pl. egy előadó "About" részében)
        var dlg=te.closest('tp-yt-paper-dialog,ytmusic-dialog,[role="dialog"],ytmusic-settings-page,ytmusic-setting-category-collection-renderer'),okS=false;
        var up2=dlg||te;
        for(var u2=0;u2<8&&up2&&!okS;u2++){if(/language and location|privacy and data|nyelv és hely|adatvédelem/i.test(up2.textContent||''))okS=true;up2=up2.parentElement;}
        if(!okS)continue;
      }
      var itm=te.closest('ytmusic-menu-navigation-item-renderer,ytmusic-menu-service-item-renderer,ytmusic-toggle-menu-service-item-renderer,tp-yt-paper-item,tp-yt-paper-tab,[role="menuitem"],[role="tab"],[role="button"],button,a')||row(te);
      var it2=itm.closest('ytmusic-menu-navigation-item-renderer,ytmusic-menu-service-item-renderer,ytmusic-toggle-menu-service-item-renderer,ytmusic-setting-category-collection-renderer > *');
      if(it2&&it2.getBoundingClientRect().height<120)itm=it2;
      itm.style.setProperty('display','none','important');
    }
    // Sign-in promo in the guide/sidebar: Vidara never asks for an account
    try{
      var promo=document.querySelectorAll('ytmusic-guide-signin-promo-renderer,#sign-in-promo,[class*="signin-promo" i],[class*="SignInPromo" i]');
      for(var pi=0;pi<promo.length;pi++)promo[pi].style.setProperty('display','none','important');
      var ptx=document.createTreeWalker(R,NodeFilter.SHOW_TEXT,{acceptNode:function(n){return /^\s*(sign in to|sign in and|jelentkezz be|jelentkezzen be|bejelentkez\u00e9s)/i.test(n.nodeValue||'')?NodeFilter.FILTER_ACCEPT:NodeFilter.FILTER_SKIP;}}),pn;
      while((pn=ptx.nextNode())){
        var pe=pn.parentElement;if(!pe)continue;
        var pb=pe.closest('ytmusic-guide-signin-promo-renderer,#sign-in-promo,[class*="signin" i],ytmusic-guide-entry-renderer,ytmusic-guide-section-renderer,tp-yt-paper-item,[role="button"],button,a');
        if(pb)pb.style.setProperty('display','none','important');
      }
    }catch(e){}
    // "Tell us which artists you like" onboarding shelf (it leads to an account): hidden as well
    try{
      var ob=document.createTreeWalker(R,NodeFilter.SHOW_TEXT,{acceptNode:function(n){return /^\s*tell us which artists/i.test(n.nodeValue||'')?NodeFilter.FILTER_ACCEPT:NodeFilter.FILTER_SKIP;}}),on2;
      while((on2=ob.nextNode())){
        var oe=on2.parentElement;if(!oe)continue;
        var os=oe.closest('ytmusic-tastebuilder-shelf-renderer,ytmusic-carousel-shelf-renderer,ytmusic-shelf-renderer,ytmusic-item-section-renderer');
        if(os)os.style.setProperty('display','none','important');
      }
    }catch(e){}
    // "Open app" (Google Play-t nyitná): eltűnik
    var w=document.createTreeWalker(R,NodeFilter.SHOW_TEXT,{acceptNode:function(n){return /^\s*(open app|alkalmazás megnyitása|megnyitás az alkalmazásban|get the app)\s*$/i.test(n.nodeValue||'')?NodeFilter.FILTER_ACCEPT:NodeFilter.FILTER_SKIP;}}),n;
    while((n=w.nextNode())){
      var el=n.parentElement;if(!el)continue;
      var it=el.closest('ytmusic-guide-entry-renderer,tp-yt-paper-item,a,button,[role="link"],[role="menuitem"],[role="button"]')||row(el);
      it.style.setProperty('display','none','important');
    }
  }
  function scan(){
    try{music();}catch(e){}
    if(location.hostname==='music.youtube.com')return;   // a VidaraMusic-ot a music() intézi (különben 2 logó lett)
    try{
      if(!document.body)return;
      // teljes bejárás csak nyitott menünél azonnal; egyébként legfeljebb 1,2 mp-enként (görgetés-lag ellen)
      var opn=document.querySelector('ytm-multi-page-menu-renderer,ytm-menu-popup-renderer,tp-yt-iron-dropdown,tp-yt-paper-dialog,[role="menu"],[role="dialog"],ytm-guide-renderer');
      if(!opn&&Date.now()-(window.__vdScanT||0)<1200)return;
      window.__vdScanT=Date.now();
      var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,{acceptNode:function(n){var t=n.nodeValue;return (t&&t.length<30&&/YouTube|Vidara/.test(t))?NodeFilter.FILTER_ACCEPT:NodeFilter.FILTER_SKIP;}}),n,list=[];
      while((n=w.nextNode()))list.push(n);
      for(var i=0;i<list.length;i++){
        var tn=list[i],t=(tn.nodeValue||'').trim(),m=t.match(RX);
        if(!m)continue;
        var el=tn.parentElement;if(!el)continue;
        var rw=row(el);
        if(/^music$/i.test(m[2])){
          tn.nodeValue='VidaraMusic';
          if(!rw.__vdVM){
            var ic=icon(rw);
            if(ic){
              var b=ic.getBoundingClientRect(),sz=Math.round(Math.max(20,Math.min(b.width||24,b.height||24)));
              var im=document.createElement('img');
              im.className='vd-vm-logo';im.src=window.__vdVMLogo||'/__vidara/vmusic.png';
              im.style.cssText='width:'+sz+'px;height:'+sz+'px;border-radius:'+Math.round(sz*0.22)+'px;object-fit:cover;flex:none;display:inline-block;vertical-align:middle';
              ic.parentNode.insertBefore(im,ic);
              ic.style.setProperty('display','none','important');
            }
            rw.__vdVM=true;
          }
          var a=rw.closest?rw.closest('a'):null;if(a){if(a.getAttribute('aria-label'))a.setAttribute('aria-label','VidaraMusic');if(a.getAttribute('title'))a.setAttribute('title','VidaraMusic');}
        }else{
          rw.style.setProperty('display','none','important');
        }
      }
    }catch(e){}
  }
  window.__vdMenuScan=scan;
  if(location.hostname==='music.youtube.com'){
    var pend=false,POP='ytmusic-popup-container,tp-yt-iron-dropdown,ytmusic-menu-popup-renderer,tp-yt-paper-dialog,ytmusic-dialog,[role="dialog"],[role="menu"],tp-yt-app-drawer,ytmusic-guide-renderer,#guide-wrapper';
    var roots=new Set();
    try{new MutationObserver(function(ms){
      for(var i=0;i<ms.length;i++){
        var t=ms[i].target;if(t&&t.nodeType!==1)t=t.parentElement;
        if(!t||!t.closest)continue;
        var pr=t.closest(POP);if(pr)roots.add(pr);
      }
      if(roots.size&&!pend){pend=true;queueMicrotask(function(){pend=false;var l=Array.from(roots);roots.clear();for(var k=0;k<l.length;k++){try{music(l[k]);}catch(e){}try{window.__vdIconRows&&window.__vdIconRows(l[k]);}catch(e){}}});}
    }).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['opened','aria-hidden','hidden']});}catch(e){}
  }
  if(location.hostname==='music.youtube.com'){setTimeout(scan,800);setTimeout(scan,2500);setInterval(function(){try{music();}catch(e){}},6000);}
  // a menü koppintásra nyílik: utána néhányszor átnézzük (a teljes lapot nem figyeljük folyamatosan)
  document.addEventListener('click',function(e){if(window.__vdVidTap(e))return;[250,900].forEach(function(d){setTimeout(scan,d);});},true);
  try{new MutationObserver(function(ms){
    for(var i=0;i<ms.length;i++){var ad=ms[i].addedNodes;for(var j=0;j<ad.length;j++){var nd=ad[j];
      if(nd.nodeType===1&&/(DIALOG|DRAWER|GUIDE|MULTI-PAGE-MENU|BOTTOM-SHEET|POPUP|NAV-BAR)/.test(nd.tagName)){setTimeout(scan,30);setTimeout(scan,250);return;}}}
  }).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
})();
// ---- VidaraMusic: a felugró lap (Up next / Lyrics / Comments / Related) LEHÚZÁSSAL bezárható
(function(){
  if(location.hostname!=='music.youtube.com')return;
  var TABS=/^(up next|lyrics|comments|related|következő|dalszöveg|hozzászólások|megjegyzések|kapcsolódó)$/i;
  var tlCache=null;
  function tablist(){
    if(tlCache&&tlCache.isConnected){var rc=tlCache.getBoundingClientRect();if(rc.height>=10)return tlCache;}
    tlCache=null;
    var ls=document.querySelectorAll('tp-yt-paper-tabs,[role="tablist"]');
    for(var i=0;i<ls.length;i++){
      var r=ls[i].getBoundingClientRect();if(r.height<10||r.width<100)continue;
      var t=ls[i].querySelectorAll('tp-yt-paper-tab,[role="tab"]');
      for(var j=0;j<t.length;j++){if(TABS.test((t[j].textContent||'').trim())){tlCache=ls[i];return ls[i];}}
    }
    return null;
  }
  function sheetOf(tl){
    var tr=tl.getBoundingClientRect(),e=tl,best=null;
    for(var i=0;i<12&&e&&e!==document.body;i++){
      var r=e.getBoundingClientRect();
      if(r.top<tr.top-60||r.height>=window.innerHeight-2)break;
      if(r.width>=window.innerWidth*0.9)best=e;
      e=e.parentElement;
    }
    return best;
  }
  function openSheet(){
    var tl=tablist();if(!tl)return null;
    var sh=sheetOf(tl);if(!sh)return null;
    var r=sh.getBoundingClientRect();
    return r.height>window.innerHeight*0.45?{tl:tl,sh:sh,r:r}:null;
  }
  function scrollParent(el,stop){
    for(var e=el;e&&e!==stop;e=e.parentElement){
      if(e.scrollHeight>e.clientHeight+4){var oy=getComputedStyle(e).overflowY;if(oy==='auto'||oy==='scroll')return e;}
    }
    return null;
  }
  function closeSheet(st){
    // a lap fölötti kis lejátszó-sorra koppintunk (bal oldalt: kép/cím, NEM a szünet gomb)
    var y=Math.max(4,st.r.top-24),x=Math.round(window.innerWidth*0.3);
    var el=document.elementFromPoint(x,y);
    if(el&&!st.sh.contains(el)){try{el.click();}catch(e){}}
    setTimeout(function(){
      var again=openSheet();
      if(again){   // tartalék: a kiválasztott fülre koppintás
        var sel=again.tl.querySelector('[aria-selected="true"],.iron-selected');if(sel)try{sel.click();}catch(e){}
      }
    },450);
  }
  var st=null,y0=0,x0=0,dy=0,drag=false,ok=false;
  document.addEventListener('touchstart',function(e){
    st=null;drag=false;ok=false;dy=0;
    if(!e.touches||e.touches.length!==1)return;
    if(!document.querySelector('tp-yt-paper-tabs,[role="tablist"]'))return;
    var s=openSheet();if(!s)return;
    var t=e.touches[0];
    if(t.clientY<s.r.top||t.clientY>s.r.bottom)return;
    var onHead=t.clientY<s.tl.getBoundingClientRect().bottom+8;
    var sp=scrollParent(e.target,s.sh);
    ok=onHead||!sp||sp.scrollTop<=0;
    if(!ok)return;
    st=s;y0=t.clientY;x0=t.clientX;
  },{capture:true,passive:true});
  document.addEventListener('touchmove',function(e){
    if(!st||!ok)return;
    var t=e.touches[0];dy=t.clientY-y0;
    if(!drag){
      if(dy>10&&Math.abs(dy)>Math.abs(t.clientX-x0)){drag=true;}
      else if(dy<-6||Math.abs(t.clientX-x0)>14){st=null;return;}
      else return;
    }
    st.sh.style.setProperty('transition','none','important');
    st.sh.style.setProperty('transform','translateY('+Math.max(0,dy)+'px)','important');
  },{capture:true,passive:true});
  function end(){
    if(!st)return;
    var s=st;st=null;
    if(!drag)return;
    drag=false;
    var h=s.r.height;
    s.sh.style.setProperty('transition','transform 180ms ease-out','important');
    if(dy>Math.max(80,h*0.18)){
      s.sh.style.setProperty('transform','translateY('+h+'px)','important');
      setTimeout(function(){closeSheet(s);},170);
      setTimeout(function(){s.sh.style.removeProperty('transform');s.sh.style.removeProperty('transition');},650);
    }else{
      s.sh.style.setProperty('transform','translateY(0px)','important');
      setTimeout(function(){s.sh.style.removeProperty('transform');s.sh.style.removeProperty('transition');},220);
    }
  }
  document.addEventListener('touchend',end,{capture:true,passive:true});
  document.addEventListener('touchcancel',end,{capture:true,passive:true});
})();
// koppintás videóra / Shortsra / videóoldalon: semmi háttérmunka (a videó induljon a lehető leggyorsabban)
window.__vdVidTap=function(e){
  try{
    var t=e&&e.target;
    // menü gomb (⋮) a videókártyán: az felugró ablakot nyit, ott kell az üveg
    if(t&&t.closest&&t.closest('ytm-menu-renderer,button,[role="button"],[aria-haspopup]')&&!t.closest('a[href]'))return false;
    if(t&&t.closest&&t.closest('ytmusic-responsive-list-item-renderer,ytmusic-two-row-item-renderer,ytmusic-player-queue-item,ytmusic-player-bar,ytmusic-play-button-renderer,ytmusic-player,ytmusic-carousel-shelf-renderer'))return true;
    if(t&&t.closest&&t.closest('a[href*="watch"],a[href*="/shorts/"],ytm-media-item,ytm-video-with-context-renderer,ytm-compact-video-renderer,ytm-reel-item-renderer,ytm-shorts-lockup-view-model,.html5-video-player,ytm-rich-item-renderer'))return true;
    var p=location.pathname||'';
    if(location.hostname==='m.youtube.com'&&(p.indexOf('/watch')===0||p.indexOf('/shorts')===0)&&t&&t.closest&&t.closest('#player-container-id,.player-container,ytm-watch .player-size'))return true;
  }catch(x){}
  return false;
};
// ---- Üveg UI MINDENHOL: bármilyen felugró ablak / menü / oldalsó menü / alsó lap automatikusan
// homályos, áttetsző, lekerekített panellé alakul (Vidara és VidaraMusic) – a YouTube elemneveitől függetlenül.
(function(){
  var TAG=/(DIALOG|DRAWER|GUIDE|MENU|SHEET|POPUP|DROPDOWN|OVERLAY|PANEL|MODAL|TOAST|LISTBOX)/;
  var SKIP='ytm-pivot-bar-renderer,ytmusic-nav-bar,ytm-mobile-topbar-renderer,ytm-chip-cloud-renderer,ytm-feed-filter-chip-bar-renderer,.html5-video-player,ytmusic-player-bar,ytm-slim-video-metadata-section-renderer';
  var HIDE=/^\s*(sign in to create[\s\S]*|jelentkezz be[\s\S]{0,160}|privacy policy|terms of service|adatvédelmi irányelvek|általános szerződési feltételek|felhasználási feltételek)\s*$/i;
  function alpha(c){if(!c||c==='transparent')return 0;var m=c.match(/rgba?\(([^)]+)\)/);if(!m)return 1;var p=m[1].split(',');return p.length>3?parseFloat(p[3]):1;}
  var OURS='tp-yt-app-drawer #contentContainer,tp-yt-app-drawer .contentContainer,ytm-engagement-panel,.engagement-panel-container';
  var NOT=/(THUMBNAIL|REEL|SHORTS|PLAYER|CHIP|VIDEO|LOCKUP|SHELF|ITEM|C3-OVERLAY|SCRIM|BACKDROP|BADGE|BUTTON|ICON|AVATAR)/;
  function isRoot(e){
    if(!e||e.nodeType!==1)return false;
    if(NOT.test(e.tagName))return false;
    var r=e.getAttribute&&e.getAttribute('role');
    return TAG.test(e.tagName)||r==='dialog'||r==='alertdialog'||r==='menu';
  }
  function hideTexts(root){
    try{
      var w=document.createTreeWalker(root,NodeFilter.SHOW_TEXT,{acceptNode:function(n){var v=n.nodeValue||'';return (v.length<220&&HIDE.test(v))?NodeFilter.FILTER_ACCEPT:NodeFilter.FILTER_SKIP;}}),n,l=[];
      while((n=w.nextNode()))l.push(n);
      for(var i=0;i<l.length;i++){
        var el=l[i].parentElement;if(!el)continue;
        var tgt=el;
        // a lábléc (Privacy Policy · Terms of Service) egészét, a bejelentkezés-szöveg blokkját rejtjük
        for(var k=0;k<6;k++){
          var p=tgt.parentElement;if(!p||p===root)break;
          var r=p.getBoundingClientRect();if(r.height>140)break;
          if(p.querySelector('a[href*="/feed/"],a[href^="/"],a[href*="channel"],yt-icon svg,ytm-guide-entry-renderer,ytmusic-guide-entry-renderer'))break;
          tgt=p;
        }
        tgt.style.setProperty('display','none','important');
        hid.add(tgt);
      }
    }catch(e){}
  }
  var hid=new Set();
  setInterval(function(){hid.forEach(function(e){if(!e.isConnected){hid.delete(e);return;}if(e.style.getPropertyValue('display')!=='none')e.style.setProperty('display','none','important');});},700);
  function glassify(R){
    try{
      if(!R.isConnected||R.closest(SKIP))return;
      var anc=R.parentElement&&R.parentElement.closest('.vd-glass');if(anc&&anc!==R)return;
      // már üveg panelen belül van (pl. az oldalsó menü tartalma): nem teszünk üveget az üvegre
      for(var a2=R.parentElement,k2=0;a2&&k2<10;a2=a2.parentElement,k2++){if(a2.hasAttribute('data-vdglass')||(a2.matches&&a2.matches(OURS)))return;}
      var rr=R.getBoundingClientRect(),VW=window.innerWidth,VH=window.innerHeight;
      if(rr.width<120||rr.height<60)return;
      var RA=rr.width*rr.height,full=VW*VH;
      hideTexts(R);
      try{window.__vdIconRows&&window.__vdIconRows(R);}catch(e){}
      // az átlátszatlan "lap" rétegek: a legkülsőből üveg, a többi átlátszó
      var q=[R],seen=0,target=null,ops=[];
      while(q.length&&seen<350){
        var e=q.shift();seen++;
        if(e.nodeType!==1||e.tagName==='IMG'||e.tagName==='VIDEO'||e.tagName==='svg')continue;
        var b=e.getBoundingClientRect(),A=b.width*b.height;
        if(A>=RA*0.55&&A<full*0.97){
          var cs=getComputedStyle(e);
          var al=alpha(cs.backgroundColor);
          if(al>=0.85||(cs.backgroundImage!=='none'&&e!==R&&!/gradient/.test(cs.backgroundImage))){ops.push(e);}
        }
        if(A>=RA*0.55)for(var c=0;c<e.children.length;c++)q.push(e.children[c]);
      }
      // a szürke "lap" lehet a menü SZÜLŐJE is (más nevű burkoló elem): felfelé is megnézzük
      var up=[];
      for(var pa=R.parentElement,kk=0;pa&&kk<6&&pa!==document.body&&pa!==document.documentElement;pa=pa.parentElement,kk++){
        if(pa.closest(SKIP))break;
        var pb=pa.getBoundingClientRect(),PA=pb.width*pb.height;
        if(PA>=full*0.97||PA>RA*2.2)break;
        var pcs=getComputedStyle(pa);
        if(alpha(pcs.backgroundColor)>=0.85)up.push(pa);
      }
      if(up.length){ops=up.reverse().concat(ops);}   // a legkülső tömör réteg lesz az üveg
      if(ops.length){target=ops[0];}else if(RA<full*0.97){target=R;}
      if(!target)return;
      var tb0=target.getBoundingClientRect();
      var isL=tb0.left<=2&&tb0.height>=VH*0.8, isB=Math.abs(tb0.bottom-VH)<=3&&tb0.width>=VW*0.9;
      if(tb0.width*tb0.height>full*0.85&&!isL&&!isB)return;   // (szinte) teljes képernyős réteg: nem panel
      for(var i=0;i<ops.length;i++){if(ops[i]!==target){clear(ops[i]);cleared.add(ops[i]);}}
      paint(target);
      glassed.add(target);
    }catch(e){}
  }
  // az üveg KÖZVETLENÜL az elem stílusára kerül (a YouTube az osztálylistát újraírja, a jelölést letörölte)
  var glassed=new Set();
  function paint(t){
    try{
      if(!t.isConnected){glassed.delete(t);return;}
      t.__vdP=Date.now();
      var tb=t.getBoundingClientRect(),VW=window.innerWidth,VH=window.innerHeight;
      if(tb.width<10||tb.height<10)return;
      var left=tb.left<=VW*0.05&&tb.height>=VH*0.7&&tb.width<VW*0.97, bottom=Math.abs(tb.bottom-VH)<=4&&tb.width>=VW*0.9&&tb.height<VH*0.97;
      var rad=left?'0 24px 24px 0':bottom?'24px 24px 0 0':'22px';
      if(!t.__vdL){t.__vdL=1;learn(t,rad);}
      var st=t.style,want={'background':'rgba(34,18,64,0.58)','background-color':'rgba(34,18,64,0.58)','background-image':'none','-webkit-backdrop-filter':'blur(18px) saturate(140%)','backdrop-filter':'blur(18px) saturate(140%)','border-radius':rad,'border':'1px solid rgba(196,165,255,0.20)','box-shadow':'0 12px 40px rgba(0,0,0,0.45)','overflow':'hidden'};
      for(var k in want){if(st.getPropertyValue(k)!==want[k]||st.getPropertyPriority(k)!=='important')st.setProperty(k,want[k],'important');}
      if(left&&st.getPropertyValue('border-left')!=='0px')st.setProperty('border-left','0px','important');
      if(bottom&&st.getPropertyValue('border-bottom')!=='0px')st.setProperty('border-bottom','0px','important');
      if(!t.hasAttribute('data-vdglass'))t.setAttribute('data-vdglass','1');
    }catch(e){}
  }
  var cleared=new Set();
  function clear(e){
    try{
      if(!e.isConnected){cleared.delete(e);return;}
      if(!e.__vdL){e.__vdL=1;learn(e,'c');}
      e.__vdP=Date.now();
      var st=e.style;
      if(st.getPropertyValue('background')!=='transparent'||st.getPropertyPriority('background')!=='important')st.setProperty('background','transparent','important');
      if(st.getPropertyValue('box-shadow')!=='none')st.setProperty('box-shadow','none','important');
    }catch(x){}
  }
  // ha a YouTube felülírja (stílus/osztály), azonnal visszatesszük
  setInterval(function(){
    // VidaraMusic: az üvegek újrafestése 600 ms-onként felesleges újrarajzolást okozott – ott ritkábban
    if(location.hostname==='music.youtube.com'&&Date.now()-(window.__vdGt||0)<1500)return;
    window.__vdGt=Date.now();
    glassed.forEach(function(t){paint(t);});cleared.forEach(function(t){clear(t);});
  },600);
  var LS='vd_glass_v3',learned={};try{localStorage.removeItem('vd_glass_v1');localStorage.removeItem('vd_glass_v2');}catch(e){}
  try{learned=JSON.parse(localStorage.getItem(LS)||'{}')||{};}catch(e){learned={};}
  var lst=document.createElement('style');
  function writeLearned(){
    var css='';
    for(var k in learned){
      var v=learned[k];
      if(v==='c')css+=k+'{background:transparent!important;box-shadow:none!important}';
      else if(v==='p')css+=k+'{background:rgba(255,255,255,.10)!important;-webkit-backdrop-filter:blur(18px) saturate(140%)!important;backdrop-filter:blur(18px) saturate(140%)!important;border-radius:999px!important;border:1px solid rgba(255,255,255,.16)!important;box-shadow:none!important;overflow:hidden!important}';
      else css+=k+'{background:rgba(34,18,64,.58)!important;-webkit-backdrop-filter:blur(18px) saturate(140%)!important;backdrop-filter:blur(18px) saturate(140%)!important;border-radius:'+v+'!important;border:1px solid rgba(196,165,255,.20)!important;box-shadow:0 12px 40px rgba(0,0,0,.45)!important;overflow:hidden!important}';
    }
    lst.textContent=css;
    if(!lst.isConnected)(document.head||document.documentElement).appendChild(lst);
  }
  try{writeLearned();}catch(e){}
  function selOf(e){
    try{
      var GEN=/(open|show|visible|active|animat|transit|enter|leave|hidden|focus|hover|selected|opened|closing|opening)/i;
      function one(x){
        var t=x.tagName.toLowerCase(),out=t;
        if(x.id&&!/\d{3,}/.test(x.id))out+='#'+x.id;
        var cl=(typeof x.className==='string'?x.className:'').trim().split(/\s+/).filter(function(c){return c&&!GEN.test(c)&&!/^vd-/.test(c)&&c.length<40;}).slice(0,2);
        if(cl.length)out+='.'+cl.join('.');
        return out;
      }
      var me=one(e);
      if(e.tagName.indexOf('-')>0&&e.id)return me;
      // biztos, ami biztos: a legközelebbi saját (kötőjeles) elem alá kötjük
      for(var a=e.parentElement,k=0;a&&k<6;a=a.parentElement,k++){if(a.tagName.indexOf('-')>0)return one(a)+' '+me;}
      return (me.indexOf('.')>0||me.indexOf('#')>0)?me:null;
    }catch(x){return null;}
  }
  function learn(e,v){
    var sl=selOf(e);if(!sl||learned[sl]===v)return;
    // csak elég egyedi szabályt tanulunk meg (ne legyen üveg/átlátszó máshol az appban)
    try{var n=document.querySelectorAll(sl).length;if(n<1||n>3)return;}catch(x){return;}
    if(Object.keys(learned).length>40)return;
    learned[sl]=v;
    try{localStorage.setItem(LS,JSON.stringify(learned));}catch(x){}
    writeLearned();
  }
  var pend=false,cand=new Set();
  function run(){
    pend=false;
    var list=Array.from(cand);cand.clear();
    for(var i=0;i<list.length;i++)glassify(list[i]);
  }
  function add(e){
    for(var k=0;k<7&&e&&e!==document.body;k++){
      if(isRoot(e)){cand.add(e);}
      e=e.parentElement;
    }
  }
  try{new MutationObserver(function(ms){
    for(var i=0;i<ms.length;i++){
      var m=ms[i],t=m.target;if(t&&t.nodeType!==1)t=t.parentElement;
      if(m.type==='childList'){
        if(m.addedNodes.length>40)continue;   // nagy tömeges betöltés (videóoldal, lista): nem nézzük végig
        for(var j=0;j<m.addedNodes.length;j++){var n=m.addedNodes[j];if(n.nodeType===1&&isRoot(n))cand.add(n);}
      }
      else if(t)add(t);
    }
    if(cand.size&&!pend){pend=true;queueMicrotask(run);}
  }).observe(document.documentElement,{childList:true,subtree:true,attributes:true,attributeFilter:['opened','hidden','aria-hidden','open','dialog-shown']});}catch(e){}
  // ha a megnyitás csak stílus/osztály váltás (nincs új elem): koppintás után is átnézzük
  function sweepAll(){
    try{
      var all=document.querySelectorAll('[role="dialog"],[role="menu"],[role="alertdialog"],tp-yt-paper-dialog,tp-yt-iron-dropdown,tp-yt-app-drawer,ytmusic-menu-popup-renderer,ytm-menu-popup-renderer,ytm-multi-page-menu-renderer,bottom-sheet-container,ytm-bottom-sheet-renderer,ytm-engagement-panel,ytm-guide-renderer,ytmusic-guide-renderer,c3-overlay ~ *,dialog');
      for(var i=0;i<all.length&&i<60;i++){var r=all[i].getBoundingClientRect();if(r.width>0&&r.height>0)glassify(all[i]);}
    }catch(e){}
  }
  var PTS=[[0.25,0.3],[0.25,0.55],[0.5,0.5],[0.5,0.8]],before=[];
  function fixedAt(x,y){
    var el=document.elementFromPoint(x,y);
    for(var e=el,k=0;e&&k<25&&e!==document.body&&e!==document.documentElement;e=e.parentElement,k++){if(getComputedStyle(e).position==='fixed')return e;}
    return null;
  }
  // koppintás ELŐTT feljegyezzük, mi volt ott (ami már ott volt, az nem felugró ablak)
  document.addEventListener('pointerdown',function(e){
    before=[];
    if(location.hostname==='music.youtube.com'||window.__vdVidTap(e))return;
    try{var VW=window.innerWidth,VH=window.innerHeight;for(var i=0;i<PTS.length;i++)before.push(document.elementFromPoint(VW*PTS[i][0],VH*PTS[i][1]));}catch(e){}
  },{capture:true,passive:true});
  var MAIN='ytmusic-browse-response,ytmusic-section-list-renderer,ytmusic-tabbed-search-results-renderer,ytm-browse,ytm-rich-grid-renderer,ytm-single-column-browse-results-renderer,ytm-search,ytmusic-player-bar,ytmusic-nav-bar';
  function atPoints(){
    try{
      var VW=window.innerWidth,VH=window.innerHeight,full=VW*VH,pts=PTS;
      for(var p=0;p<pts.length;p++){
        var el=document.elementFromPoint(VW*pts[p][0],VH*pts[p][1]);if(!el)continue;
        // csak rögzített (lebegő) rétegen belül: a normál oldalhoz nem nyúlunk
        var chain=[],fixed=null;
        for(var e=el,k=0;e&&k<25&&e!==document.body&&e!==document.documentElement;e=e.parentElement,k++){
          chain.push(e);
          var ps=getComputedStyle(e).position;
          if(ps==='fixed'){fixed=e;break;}
        }
        if(!fixed||fixed.closest(SKIP)||NOT.test(fixed.tagName))continue;
        var was=false;for(var bi=0;bi<before.length;bi++){var be=before[bi];if(be&&be.isConnected&&fixed.contains(be)){was=true;break;}}
        if(was)continue;                       // már a koppintás előtt is ott volt: ez az oldal, nem ablak
        if(fixed.querySelector(MAIN)||fixed.closest(MAIN))continue;  // a fő tartalom / lejátszósáv soha
        // lejátszó / videó / a saját betöltő takarónk: soha
        if(fixed===window.__vdLd||fixed.querySelector('video')||fixed.closest('.vd-fixed,#player-container-id,ytm-watch,ytmusic-player,ytm-reel-player-renderer,ytm-shorts-player'))continue;
        var solid=[];
        for(var i=0;i<chain.length;i++){
          var c=chain[i],b=c.getBoundingClientRect(),A=b.width*b.height;
          if(A<full*0.15||A>=full*0.97)continue;
          if(alpha(getComputedStyle(c).backgroundColor)>=0.85)solid.push(c);
        }
        if(!solid.length)continue;
        var tgt=solid[solid.length-1];   // a legkülső tömör panel
        if(tgt.hasAttribute('data-vdglass'))continue;
        for(var j=0;j<solid.length-1;j++){clear(solid[j]);cleared.add(solid[j]);}
        hideTexts(fixed);
        try{window.__vdIconRows&&window.__vdIconRows(fixed);}catch(e){}
        paint(tgt);glassed.add(tgt);
      }
    }catch(e){}
  }
  // ---- Keresősáv (VidaraMusic nagyító, Vidara találati oldal): üveg "kapszula" + üveg javaslatlista
  function pillGlass(t){
    try{
      t.__vdP=Date.now();
      var st=t.style,want={'background':'rgba(255,255,255,0.10)','background-color':'rgba(255,255,255,0.10)','background-image':'none','-webkit-backdrop-filter':'blur(18px) saturate(140%)','backdrop-filter':'blur(18px) saturate(140%)','border-radius':'999px','border':'1px solid rgba(255,255,255,0.16)','box-shadow':'none','overflow':'hidden'};
      for(var k in want){if(st.getPropertyValue(k)!==want[k]||st.getPropertyPriority(k)!=='important')st.setProperty(k,want[k],'important');}
      if(!t.hasAttribute('data-vdglass'))t.setAttribute('data-vdglass','1');
      if(!t.__vdL){t.__vdL=1;learnPill(t);}
    }catch(e){}
  }
  var pills=new Set();
  function learnPill(e){
    var sl=selOf(e);if(!sl||learned[sl])return;
    try{var n=document.querySelectorAll(sl).length;if(n<1||n>3)return;}catch(x){return;}
    learned[sl]='p';try{localStorage.setItem(LS,JSON.stringify(learned));}catch(x){}writeLearned();
  }
  function searchBox(inp){
    try{
      var VW=window.innerWidth,ir=inp.getBoundingClientRect();
      if(ir.top>window.innerHeight*0.5)return;   // csak a felső keresősáv
      var box=null,inner=[];
      for(var e=inp,k=0;e&&k<8&&e!==document.body;e=e.parentElement,k++){
        var r=e.getBoundingClientRect();
        if(r.height>90)break;
        var al=alpha(getComputedStyle(e).backgroundColor);
        if(al>0.05&&r.width>=VW*0.45){if(box)inner.push(box);box=e;}
      }
      if(!box)return;
      for(var i=0;i<inner.length;i++){clear(inner[i]);cleared.add(inner[i]);}
      if(box!==inp&&getComputedStyle(inp).backgroundColor!=='rgba(0, 0, 0, 0)'){inp.style.setProperty('background','transparent','important');}
      pillGlass(box);pills.add(box);
      // javaslatlista a sáv alatt
      setTimeout(function(){suggest(box);},280);
    }catch(e){}
  }
  function suggest(box){
    try{
      if(!box.isConnected)return;
      var VW=window.innerWidth,VH=window.innerHeight,b=box.getBoundingClientRect();
      var el=document.elementFromPoint(VW*0.5,Math.min(VH-5,b.bottom+60));if(!el||box.contains(el))return;
      var best=null;
      for(var e=el,k=0;e&&k<12&&e!==document.body;e=e.parentElement,k++){
        if(e.closest(SKIP)||e.querySelector('video'))return;
        var r=e.getBoundingClientRect();
        if(r.top<b.top-4||r.height>=VH*0.97)break;
        if(r.width>=VW*0.8&&alpha(getComputedStyle(e).backgroundColor)>=0.85)best=e;
      }
      if(best&&!best.hasAttribute('data-vdglass')){paint(best);glassed.add(best);}
    }catch(e){}
  }
  document.addEventListener('focusin',function(e){
    var t=e.target;if(!t||!(t.tagName==='INPUT'||t.tagName==='TEXTAREA'))return;
    var ty=(t.getAttribute('type')||'text').toLowerCase();if(ty!=='text'&&ty!=='search')return;
    requestAnimationFrame(function(){searchBox(t);});
    setTimeout(function(){searchBox(t);},300);
  },true);
  document.addEventListener('input',function(e){
    var t=e.target;if(!t||t.tagName!=='INPUT')return;
    clearTimeout(window.__vdSugT);window.__vdSugT=setTimeout(function(){var bx=null;pills.forEach(function(p){if(p.contains(t))bx=p;});if(bx)suggest(bx);},350);
  },true);
  setInterval(function(){pills.forEach(function(p){if(!p.isConnected){pills.delete(p);return;}pillGlass(p);});},700);
  window.__vdAtPoints=atPoints;
  document.addEventListener('click',function(e){if(location.hostname==='music.youtube.com'||window.__vdVidTap(e))return;[260,900].forEach(function(d){setTimeout(function(){requestAnimationFrame(function(){sweepAll();atPoints();});},d);});},true);
  window.__vdGlass=sweepAll;

})();
// Alsó menü: MINDIG csak két gomb (Kezdőlap + Shorts). Figyelő a menüsoron: ha a YouTube
// visszarakja a "Te/You" (vagy bármi más) gombot – pl. videóból visszalépve –, azonnal eltűnik.
(function(){
  function kind(it){
    var a=it.querySelector?it.querySelector('a'):null;
    var h=a?(a.getAttribute('href')||''):'';
    try{if(h)h=new URL(h,location.href).pathname;}catch(e){}
    var t=(it.textContent||'').trim().toLowerCase();
    if(h.indexOf('/shorts')===0||t==='shorts')return 'keep';
    if(h==='/'||h===''&&/^(home|kezdőlap|főoldal)$/.test(t)||/^(home|kezdőlap|főoldal)$/.test(t))return 'keep';
    return 'hide';
  }
  function fix(p){
    try{
      var it=p.querySelectorAll('ytm-pivot-bar-item-renderer');
      if(!it.length)it=p.children;
      var kept=0,any=false;
      for(var j=0;j<it.length;j++){if(kind(it[j])==='keep'){any=true;break;}}
      // ha a gombokat nem ismerjük fel (más felépítés), csak a "Te/You/Könyvtár" gombot rejtjük
      if(!any){
        for(var m=0;m<it.length;m++){
          var a2=it[m].querySelector?it[m].querySelector('a'):null,h2=a2?(a2.getAttribute('href')||''):'',t2=(it[m].textContent||'').trim().toLowerCase();
          if(/\/feed\/(you|library)|account/.test(h2)||/^(you|te|könyvtár|library)$/.test(t2))it[m].style.setProperty('display','none','important');
        }
        return;
      }
      for(var i=0;i<it.length;i++){
        var k=kind(it[i]);
        if(k==='keep'&&kept<2){kept++;if(it[i].style.display==='none')it[i].style.removeProperty('display');}
        else if(it[i].style.getPropertyValue('display')!=='none')it[i].style.setProperty('display','none','important');
      }
    }catch(e){}
  }
  var cur=null,mo=null;
  function attach(){
    try{
      var p=document.querySelector('ytm-pivot-bar-renderer');
      if(!p)return;
      if(p!==cur){
        cur=p;if(mo)mo.disconnect();
        mo=new MutationObserver(function(){fix(p);});
        mo.observe(p,{childList:true,subtree:true});
      }
      fix(p);
    }catch(e){}
  }
  try{new MutationObserver(function(ms){
    if(cur&&cur.isConnected)return;
    for(var i=0;i<ms.length;i++){var ad=ms[i].addedNodes;for(var j=0;j<ad.length;j++){var n=ad[j];if(n.nodeType===1&&(n.tagName==='YTM-PIVOT-BAR-RENDERER'||(n.querySelector&&n.querySelector('ytm-pivot-bar-renderer')))){attach();return;}}}
  }).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  setInterval(function(){if(!document.hidden)attach();},800);
  attach();
})();
window.__vdAuto=function(){
  window.__vdAutoUntil=Date.now()+20000;   // 20 mp-ig figyeljük: a lejátszó azonnal induljon
  window.__vdPlay(window.__vdVideo());
};
setInterval(function(){
  if(Date.now()>window.__vdAutoUntil)return;
  try{
    var v=window.__vdVideo();
    if(!v)return;
    if(v.paused&&v.currentTime<0.8){
      // ha a szám már elindult, akkor ez szándékos szünet (ne indítsuk újra)
      if(window.__vdUP&&window.__vdStarted===location.href){window.__vdAutoUntil=0;return;}
      window.__vdTryKick=(window.__vdTryKick||0)+1;
      window.__vdPlay(v);
      // tartalék: ha nem indul, koppintást szimulálunk a lejátszón
      if(window.__vdTryKick===2||window.__vdTryKick===3||window.__vdTryKick===5||window.__vdTryKick===8||window.__vdTryKick===12){
        if(location.hostname==='music.youtube.com'){
          // VidaraMusic: a lejátszó SAJÁT gombja, de csak ha épp "Play" áll rajta (így soha nem állítja meg a zenét)
          var pb=document.querySelector('ytmusic-player-bar .play-pause-button,ytmusic-player .play-pause-button,ytmusic-player-page .play-pause-button');
          if(!pb)pb=document.querySelector('[aria-label^="Play"],[aria-label^="Lejátszás"]');
          if(pb&&pb.offsetParent&&/^(Play|Lejátszás)/i.test((pb.getAttribute('aria-label')||pb.getAttribute('title')||'').trim())){try{pb.click();}catch(e){}}
        }else{
          try{v.click();}catch(e){}
          try{var b=document.querySelector('.ytp-large-play-button,.ytp-play-button,button[aria-label*="Play"],button[aria-label*="Lejátsz"]');if(b&&b.offsetParent)b.click();}catch(e){}
        }
      }
    }
    else if(!v.paused){window.__vdAutoUntil=0;window.__vdTryKick=0;}
  }catch(e){}
},600);
// (a reklámot a fenti tick kezeli 250 ms-onként: némítás + 16x + a vége felé tekerés)
// ---- Vidara feliratozás: ahol a YouTube neve szerepel a lapon, ott Vidara legyen
(function(){
  if(window.__vdBrandOn)return;window.__vdBrandOn=true;
  function fix(){
    try{
      if(!document.body)return;
      var w=document.createTreeWalker(document.body,NodeFilter.SHOW_TEXT,null),n,c=0;
      while((n=w.nextNode())&&c<400){
        var t=n.nodeValue;
        if(t&&t.indexOf('YouTube')>=0){n.nodeValue=t.split('YouTube').join('Vidara');c++;}
      }
      var els=document.querySelectorAll('[aria-label*="YouTube"],[title*="YouTube"],[placeholder*="YouTube"]');
      for(var i=0;i<els.length&&i<150;i++){
        for(var k=0;k<3;k++){
          var a=['aria-label','title','placeholder'][k];
          var v=els[i].getAttribute(a);
          if(v&&v.indexOf('YouTube')>=0)els[i].setAttribute(a,v.split('YouTube').join('Vidara'));
        }
      }
      if(document.title&&document.title.indexOf('YouTube')>=0)document.title=document.title.split('YouTube').join('Vidara');
    }catch(e){}
  }
  function noLogin(){
    try{
      var p=location.pathname||'';
      if(p.indexOf('/signin')>=0||p.indexOf('ServiceLogin')>=0){history.back();}
      var b=document.querySelectorAll('a[href*="/signin"],a[href*="ServiceLogin"],ytm-identity-button-renderer,ytm-sign-in-prompt-renderer');
      for(var i=0;i<b.length;i++){b[i].style.display='none';}
      // a "You"/"Library" fül vendégként csak bejelentkezés-zárolót mutat: kivesszük a menüből
      var pivot=document.querySelector('ytm-pivot-bar-renderer');
      if(pivot){
        var items=pivot.children;
        for(var j=0;j<items.length;j++){
          var it=items[j],a=it.querySelector?it.querySelector('a'):null;
          var h=a?(a.getAttribute('href')||''):'';
          var tx=(it.textContent||'').trim().toLowerCase();
          if(h.indexOf('/feed/you')>=0||h.indexOf('/feed/library')>=0||tx==='you'||tx==='library'||tx==='te'||tx==='könyvtár'){it.style.display='none';}
        }
      }
      // a Shorts-polc fölötti "Shorts" felirat ne legyen ott
      var sh=document.querySelectorAll('ytm-reel-shelf-renderer');
      for(var s=0;s<sh.length;s++){
        var nodes=sh[s].querySelectorAll('div,span,h2,ytm-shelf-header-layout');
        for(var q=0;q<nodes.length&&q<14;q++){
          var kt=(nodes[q].textContent||'').trim().toLowerCase();
          if(kt==='shorts'){nodes[q].style.display='none';}
        }
      }
      // "Sign in" gombok/feliratok elrejtése
      var btn=document.querySelectorAll('yt-button-renderer,ytm-button-renderer,button,a');
      for(var k=0;k<btn.length&&k<500;k++){
        var t=(btn[k].textContent||'').trim().toLowerCase();
        if(t==='sign in'||t==='bejelentkezés'){btn[k].style.display='none';}
      }
    }catch(e){}
  }
  function openApp(){
    try{
      var bs=document.querySelectorAll('a,button,ytm-button-renderer');
      if(bs.length>400)return;
      for(var bi=0;bi<bs.length;bi++){var bt=bs[bi];if(!bt.__vdh&&bt.textContent&&bt.textContent.length<20&&/^\s*open app\s*$/i.test(bt.textContent)){bt.style.setProperty('display','none','important');bt.__vdh=true;}}
    }catch(e){}
  }
  // FONTOS: csak oldalbetöltés után EGYSZER fut (a folyamatos átvizsgálás megakasztotta a görgetést)
  window.__vdCosmetics=function(){fix();noLogin();openApp();try{window.__vdSweep();}catch(e){}};
  setTimeout(function(){window.__vdCosmetics();},1200);
  // Szöveg alapú, árnyék-DOM-ot is bejáró elrejtés: YouTube-logó, "Open App", Shorts-fejléc.
  window.__vdSweep=function(){
    var seen=0;
    function hide(e){
      try{
        if(!e||!e.style)return;
        e.style.setProperty('display','none','important');
        var p=e.parentElement;
        if(p&&p.style){
          p.style.setProperty('padding-top','0','important');
          p.style.setProperty('padding-bottom','0','important');
          p.style.setProperty('margin-top','0','important');
          p.style.setProperty('margin-bottom','0','important');
          p.style.setProperty('min-height','0','important');
        }
      }catch(x){}
    }
    function closestAny(e,sel){
      var c=e;
      while(c){
        if(c.nodeType===1&&c.matches&&c.matches(sel))return c;
        c=c.parentElement||(c.parentNode&&c.parentNode.host)||null;
      }
      return null;
    }
    function blockAbove(e){
      var cur=e,last=e;
      for(var i=0;i<4;i++){
        var p=cur.parentElement||(cur.parentNode&&cur.parentNode.host);
        if(!p)break;
        var r=p.getBoundingClientRect?p.getBoundingClientRect():null;
        if(r&&r.height>130)break;
        last=p;cur=p;
        // ha ebben a blokkban van ikon (a Shorts-logó), azt rejtjük el
        if(p.querySelector&&p.querySelector('svg,img'))return p;
      }
      return last;
    }
    function scan(root){
      var els;
      try{els=root.querySelectorAll('*');}catch(err){return;}
      for(var i=0;i<els.length&&seen<1500;i++){
        var el=els[i];
        if(el.shadowRoot){seen++;scan(el.shadowRoot);}
        if(el.__vdchk)continue;
        el.__vdchk=1;
        if(el.childElementCount!==0)continue;
        var t=(el.textContent||'').trim();
        if(t==='YouTube'||t==='Open App'){
          var tgt=closestAny(el,'a,button,ytm-button-renderer,yt-button-renderer')||el;
          hide(tgt);
          var row=tgt.parentElement&&tgt.parentElement.parentElement;
          if(row&&row.style){row.style.setProperty('height','46px','important');row.style.setProperty('min-height','0','important');row.style.setProperty('padding','0','important');}
        }else if(t==='Shorts'){
          if(!closestAny(el,'ytm-pivot-bar-renderer,ytm-pivot-bar-item-renderer'))hide(blockAbove(el));
        }
      }
    }
    scan(document);
    // a lap saját felső sora (logó + kereső) a főoldalon nem kell: a Vidara-sávban van kereső
    try{
      var pth=location.pathname||'/';
      if(pth==='/'||pth===''||pth==='/feed/trending'){
        var tb=document.querySelector('ytm-mobile-topbar-renderer,ytm-topbar-renderer');
        if(tb&&tb.style){
          tb.style.setProperty('display','none','important');
          // a felső sor helyét is lenullázzuk (ne maradjon üres fekete sáv)
          var pp=tb.parentElement;
          if(pp&&pp.style){
            pp.style.setProperty('padding-top','0','important');
            pp.style.setProperty('padding-bottom','0','important');
            pp.style.setProperty('margin-top','0','important');
            pp.style.setProperty('margin-bottom','0','important');
          }
          // a maradék üres sáv: megmérjük, mennyivel vannak lejjebb a chipek, és natívan feljebb toljuk a lapot
          try{
            var chip=window.__vdChipEl?window.__vdChipEl():null;   // a LÁTHATÓ sáv (visszalépés után a régi lap is a DOM-ban lehet)
            var top=chip?Math.round(chip.getBoundingClientRect().top):0;
            var pth=(location.pathname||'');
            var onVideo=(pth.indexOf('/watch')===0||pth.indexOf('/shorts')===0||pth.indexOf('/embed')===0);
            if(!onVideo&&top>4&&top<140&&(window.scrollY||0)<5){window.__vdChipTop=top;try{VidaraApp.gap(top);}catch(e){}}
            else if(onVideo){try{VidaraApp.gap(0);}catch(e){}}   // videó-oldalon nincs extra eltolás
            // egyébként (görgetés közben / nincs mérhető sáv) NEM nullázzuk az igazítást – ettől ugrott le a sáv
          }catch(err){}
        }
      }
    }catch(e){}
  };
  setTimeout(function(){try{window.__vdSweep();}catch(e){}},1500);
  setTimeout(function(){try{window.__vdSweep();}catch(e){}},3500);
  setTimeout(function(){try{window.__vdSweep();}catch(e){}},6500);
  setInterval(function(){try{if(!document.hidden&&location.hostname!=='music.youtube.com')window.__vdSweep();}catch(e){}},18000);
  // Ha a Home üres (fekete) marad, egyszer újratöltjük – ne kelljen várni
  setInterval(function(){
    try{
      var pth=location.pathname||'/';
      // CSAK a Vidara (m.youtube.com) kezdőlapján – a VidaraMusic (music.youtube.com) lapját soha nem dobjuk vissza
      if(location.hostname!=='m.youtube.com')return;
      if(pth!=='/'&&pth!=='')return;
      var items=document.querySelectorAll('ytm-video-with-context-renderer,ytm-compact-video-renderer,ytm-rich-item-renderer,ytm-reel-shelf-renderer,ytm-media-item');
      // betöltő vázak / folytatás látszik: a YouTube még tölt, NEM töltjük újra az egészet (az csak lassít)
      var loading=document.querySelectorAll('ytm-continuation-item-renderer,[class*="skeleton"],ytm-skeleton-renderer,ytm-spinner').length>0;
      if(items.length>0){window.__vdEmptyAt=0;return;}
      if(!window.__vdEmptyAt){window.__vdEmptyAt=Date.now();return;}
      if(Date.now()-window.__vdEmptyAt>(loading?12000:6000)&&(!window.__vdLastReload||Date.now()-window.__vdLastReload>20000)){
        window.__vdLastReload=Date.now();window.__vdEmptyAt=0;
        location.href='https://m.youtube.com/';
      }
    }catch(e){}
  },3000);
})();
})();
// ---- Vidara: a menü "Send feedback" és "Help" sora NEM a Google-hoz megy, hanem saját Vidara-ablakot kap
(function(){
  if(window.__vdFbOn)return;window.__vdFbOn=true;
  var FB=/^(send feedback|feedback|visszajelz[e\u00e9]s( k[u\u00fc]ld[e\u00e9]se)?)$/i;
  var HP=/^(help|s[u\u00fa]g[o\u00f3]( [e\u00e9]s visszajelz[e\u00e9]s)?|seg[i\u00ed]ts[e\u00e9]g)$/i;
  var ROWS='ytd-compact-link-renderer,ytm-compact-link-renderer,ytmusic-compact-link-renderer,ytmusic-menu-service-item-renderer,ytmusic-menu-navigation-item-renderer,ytm-menu-service-item-renderer,ytm-menu-navigation-item-renderer,yt-list-item-view-model,tp-yt-paper-item,[role="menuitem"]';
  var POP='ytmusic-popup-container,tp-yt-iron-dropdown,ytmusic-menu-popup-renderer,ytm-menu-popup-renderer,ytm-multi-page-menu-renderer,ytmusic-multi-page-menu-renderer,tp-yt-paper-dialog,ytmusic-dialog,tp-yt-app-drawer,ytmusic-guide-renderer,ytm-guide-renderer,[role="dialog"],[role="menu"]';
  var HELP=[
    ['Playback','Tap a video or a song to start. The player bar at the bottom has pause, next and previous; long-press the video for playback speed.'],
    ['Without ads','Vidara runs the real player with ad-free requests. If an ad still slips through, Vidara skips it automatically after a second.'],
    ['VidaraMusic','The compass icon next to the chips opens VidaraMusic: songs, albums and radio without ads, in Vidara colours.'],
    ['Shorts','Swipe up for the next short. The bottom bar always keeps only Home and Shorts, nothing else.'],
    ['Screen sizes','Phone: one column. Tablet and foldable: filled grid plus a two-pane video page. Rotating the device keeps the same layout.'],
    ['Updates','You – Search for updates installs the newest version from GitHub. Your settings and history stay in place.'],
    ['Privacy','No analytics, no tracking of its own. Playback happens in the real player on your own connection.'],
    ['Something wrong?','Pick "Send feedback to Vidara" in this menu: describe what you did and what happened, and it goes straight to the developer.']
  ];
  function rowOf(t){try{return t&&t.closest?t.closest(ROWS):null;}catch(e){return null;}}
  function txtOf(e){return (e&&e.textContent?e.textContent:'').replace(/\s+/g,' ').trim();}
  function el(tag,css,text){var e=document.createElement(tag);if(css)e.style.cssText=css;if(text!=null)e.textContent=text;return e;}
  function close(){
    try{var os=document.querySelectorAll('#vd-fb');for(var i=0;i<os.length;i++)os[i].remove();document.documentElement.classList.remove('vd-fbopen');}catch(e){}
  }
  window.__vdFbClose=close;
  function shell(title,sub){
    close();
    document.documentElement.classList.add('vd-fbopen');
    var w=el('div','position:fixed;left:0;top:0;width:100vw;height:100vh;z-index:2147483647;background:rgba(6,2,20,.58);-webkit-backdrop-filter:blur(8px);backdrop-filter:blur(8px);display:flex;align-items:center;justify-content:center;padding:18px;box-sizing:border-box;font-family:VidaraOutfit,Roboto,system-ui,sans-serif');
    w.id='vd-fb';
    var c=el('div','width:100%;max-width:560px;max-height:84vh;display:flex;flex-direction:column;background:linear-gradient(160deg,rgba(42,22,82,.985),rgba(19,10,40,.99));border:1px solid rgba(196,165,255,.28);border-radius:26px;box-shadow:0 26px 80px rgba(4,1,14,.7);color:#f4efff;box-sizing:border-box;padding:20px 20px 16px');
    c.id='vd-fbc';
    var h=el('div','font-weight:700;font-size:20px;line-height:1.25;color:#fff;margin:0 0 6px;flex:0 0 auto',title);
    var s=el('div','font-size:13.5px;line-height:1.5;color:#c3b4e4;margin:0 0 14px;flex:0 0 auto',sub||'');
    var b=el('div','overflow:auto;overscroll-behavior:contain;-webkit-overflow-scrolling:touch;flex:1 1 auto;min-height:0');
    var f=el('div','display:flex;gap:10px;justify-content:flex-end;flex-wrap:wrap;margin-top:14px;flex:0 0 auto');
    c.appendChild(h);c.appendChild(s);c.appendChild(b);c.appendChild(f);
    w.appendChild(c);
    w.addEventListener('click',function(e){if(e.target===w)close();},false);
    (document.body||document.documentElement).appendChild(w);
    return {card:c,body:b,foot:f};
  }
  function btn(label,primary,fn){
    var b=el('button','appearance:none;border:0;cursor:pointer;font:600 15px/1 VidaraOutfit,Roboto,system-ui,sans-serif;padding:13px 20px;border-radius:999px;'+(primary?'background:linear-gradient(135deg,#a855f7,#6366f1);color:#fff;box-shadow:0 10px 26px rgba(124,58,237,.45)':'background:rgba(255,255,255,.09);color:#e7ddff;border:1px solid rgba(196,165,255,.22)'),label);
    b.addEventListener('click',function(e){e.stopPropagation();fn&&fn();},false);
    return b;
  }
  function feedbackDialog(){
    var d=shell('Send feedback to Vidara','Tell us what is wrong or what you would like to see. Your message goes straight to the Vidara developer - no Google account, no tracking.');
    var ta=el('textarea','width:100%;box-sizing:border-box;min-height:130px;resize:vertical;background:rgba(12,6,28,.85);border:1px solid rgba(196,165,255,.24);border-radius:18px;color:#f4efff;font:400 15px/1.45 VidaraOutfit,Roboto,system-ui,sans-serif;padding:13px 14px;outline:none');
    ta.placeholder='What happened, and where? (video, screen, device)';
    ta.maxLength=4000;
    d.body.appendChild(ta);
    d.body.appendChild(el('div','font-size:12px;color:#a497c6;margin:8px 2px 0','The app version and your screen size are attached automatically.'));
    d.foot.appendChild(btn('Cancel',false,close));
    d.foot.appendChild(btn('Send',true,function(){
      var v=(ta.value||'').trim();
      if(v.length<3){ta.focus();ta.style.borderColor='rgba(248,113,113,.8)';return;}
      var loc='',ua='';
      try{loc=location.href;}catch(e){}
      try{ua=navigator.userAgent;}catch(e){}
      var msg=v+'\n\n---\nPage: '+loc+'\nApp: Vidara '+((window.__vdVer)||'')+'\nScreen: '+innerWidth+'x'+innerHeight+'  '+ua;
      var cc=document.getElementById('vd-fbc');
      if(cc){
        cc.innerHTML='';
        cc.appendChild(el('div','font-weight:700;font-size:20px;color:#fff;margin:0 0 8px;flex:0 0 auto','Thank you!'));
        cc.appendChild(el('div','font-size:14px;line-height:1.5;color:#c3b4e4;flex:1 1 auto','Your mail app opens with the message ready - just press send.'));
        var r2=el('div','display:flex;justify-content:flex-end;margin-top:14px;flex:0 0 auto');r2.appendChild(btn('OK',true,close));cc.appendChild(r2);
      }
      // a levelezőt kicsit később indítjuk, hogy a „Thank you" kép előbb megjelenjen
      setTimeout(function(){try{VidaraApp.feedback(msg);}catch(e){}},80);
    }));
    try{setTimeout(function(){ta.focus();},120);}catch(e){}
  }
  function helpDialog(){
    var d=shell('Vidara Help','Everything Vidara does, in eight short points.');
    for(var i=0;i<HELP.length;i++){
      var box=el('div','background:rgba(255,255,255,.05);border:1px solid rgba(196,165,255,.14);border-radius:18px;padding:12px 14px;margin:0 0 9px');
      box.appendChild(el('div','font-weight:700;font-size:14.5px;color:#e9ddff;margin:0 0 3px',HELP[i][0]));
      box.appendChild(el('div','font-size:13.5px;line-height:1.5;color:#c3b4e4',HELP[i][1]));
      d.body.appendChild(box);
    }
    d.foot.appendChild(btn('Send feedback to Vidara',false,feedbackDialog));
    d.foot.appendChild(btn('OK',true,close));
  }
  // A menü soraiban a felirat: "Send feedback" -> "Send feedback to Vidara", "Help" -> "Vidara Help"
  function relabel(root){
    try{
      if(!root||!root.querySelectorAll)return;
      var rs=root.querySelectorAll(ROWS);
      for(var i=0;i<rs.length&&i<80;i++){
        var r=rs[i],t=txtOf(r);
        if(!t||t.length>40)continue;
        var want=FB.test(t)?'Send feedback to Vidara':(HP.test(t)?'Vidara Help':null);
        if(!want||t===want)continue;
        var w=document.createTreeWalker(r,NodeFilter.SHOW_TEXT,null),n,hit=null;
        while((n=w.nextNode())){var nt=(n.nodeValue||'').trim();if(nt&&(FB.test(nt)||HP.test(nt))){hit=n;break;}}
        if(hit)hit.nodeValue=want;else r.setAttribute('aria-label',want);
      }
    }catch(e){}
  }
  window.__vdFbRows=relabel;
  // A menü koppintásra nyílik: a megnyílt lapot nézzük át (nem az egész oldalt)
  try{new MutationObserver(function(ms){
    for(var i=0;i<ms.length;i++){
      var ad=ms[i].addedNodes;
      for(var j=0;j<ad.length;j++){
        var nd=ad[j];
        if(nd.nodeType!==1)continue;
        if(/(MENU|POPUP|DROPDOWN|DIALOG|BOTTOM-SHEET|LIST)/.test(nd.tagName)){setTimeout(function(){relabel(document);},20);setTimeout(function(){relabel(document);},160);return;}
      }
    }
  }).observe(document.documentElement,{childList:true,subtree:true});}catch(e){}
  document.addEventListener('click',function(){setTimeout(function(){relabel(document);},60);},true);
  // A YouTube saját visszajelzés/Súgó lapja ne maradjon ott: a menüt és a saját lapját bezárjuk
  function killYt(){
    try{
      var sc=document.querySelector('tp-yt-iron-overlay-backdrop,ytd-backdrop');
      if(sc&&!sc.closest('#vd-fb'))sc.click();
    }catch(e){}
    try{
      var sel='yt-triggered-feedback-dialog,ytm-triggered-feedback-dialog,ytd-feedback-dialog-renderer,yt-help-panel-renderer,ytd-help-panel-renderer,ytm-feedback-dialog,ytmusic-feedback-dialog,tp-yt-paper-dialog';
      var els=document.querySelectorAll(sel);
      for(var i=0;i<els.length;i++){
        var e=els[i];
        if(e.id==='vd-fb')continue;
        if(e.closest&&e.closest('#vd-fb'))continue;
        var t=(e.textContent||'').toLowerCase();
        if(/feedback|help|s[u\u00fa]g[o\u00f3]|visszajelz/.test(t)){
          e.style.setProperty('display','none','important');
          var p=e.parentElement;
          if(p&&/IRON-DROPDOWN|OVERLAY/.test(p.tagName))p.style.setProperty('display','none','important');
        }
      }
    }catch(e){}
  }
  window.__vdFbKillYt=killYt;
  function openDlg(kind){
    try{window.__vdFbT=Date.now();}catch(e){}
    killYt();
    try{
      var t0=Date.now();
      var iv=setInterval(function(){killYt();if(Date.now()-t0>2600)clearInterval(iv);},400);
    }catch(e){}
    if(kind==='help')helpDialog();else feedbackDialog();
  }
  // A koppintás ELKAPÁSA: a YouTube semmilyen egér/ujj-eseményt ne kapjon ezeken a sorokon
  ['pointerdown','pointerup','mousedown','mouseup','touchstart','touchend','click'].forEach(function(n){
    window.addEventListener(n,function(e){
      try{
        var t=e.target;if(!t||!t.closest)return;
        if(t.closest('#vd-fb'))return;
        var r=rowOf(t);if(!r)return;
        var tx=txtOf(r);
        if(tx.length>40)return;
        var isFb=FB.test(tx)||/^send feedback to vidara$/i.test(tx);
        var isHp=!isFb&&(HP.test(tx)||/^vidara help$/i.test(tx));
        if(!isFb&&!isHp)return;
        // a YouTube ne kapja meg a koppintást; a le-fázist csak elzárjuk (preventDefault nélkül,
        // különben a WebView nem generálja a touchend/pointerup végét)
        e.stopImmediatePropagation();e.stopPropagation();
        if(n==='pointerup'||n==='touchend'||n==='mouseup'||n==='click')e.preventDefault();
        if(n==='click'||n==='mouseup'||n==='pointerup'||n==='touchend'){
          if(Date.now()-(window.__vdFbT||0)<700)return;
          openDlg(isHp?'help':'feedback');
        }
      }catch(x){}
    },{capture:true,passive:false});
  });
  // Az ablak érintéseit is elkapjuk (a YouTube saját kezelői ne fussanak alatta)
  ['touchstart','touchmove','pointerdown','pointerup','mousedown','mouseup'].forEach(function(n){
    window.addEventListener(n,function(e){try{var t=e.target;if(t&&t.closest&&t.closest('#vd-fb'))e.stopImmediatePropagation();}catch(x){}},{capture:true,passive:true});
  });
  window.__vdHelp=helpDialog;window.__vdFeedback=feedbackDialog;
})();

"""
}

private class BgWebView(ctx: Context) : WebView(ctx) {
    // Ha az ablak láthatatlanná válik (kikapcsol a képernyő), a lejátszás ne álljon le.
    override fun onWindowVisibilityChanged(visibility: Int) {
        if (visibility != View.GONE) super.onWindowVisibilityChanged(visibility)
    }
}

/** A webes lejátszó rétege a főablak fölött – nem külön oldal, hanem az app része. */
@SuppressLint("SetJavaScriptEnabled", "ViewConstructor")
class WebLayer(private val act: Activity, private val embedded: Boolean = false) : FrameLayout(act) {
    private val web: WebView
    private val bar: LinearLayout
    private val cover: FrameLayout
    private val thumb: ImageView
    private val spinner: ProgressBar
    private val miniControls: LinearLayout
    private val miniPlay: ImageView
    private val miniClose: ImageView
    private lateinit var musicExit: LinearLayout
    /** A fejléc bal felső címkéje: Vidara / VidaraMusic. */
    private lateinit var brand: TextView
    private val fsExit: ImageView
    private val fsBar: LinearLayout
    private val fsSeek: SeekBar
    private val fsTime: TextView
    private val fsPlay: ImageView
    private lateinit var embedBack: ImageView
    private lateinit var searchRow: LinearLayout
    private lateinit var searchField: EditText
    /** Keresési előzmények legördülő listája (eszközönként tárolva). */
    private lateinit var historyPanel: LinearLayout
    private lateinit var pipArt: ImageView
    private var pipArtId = ""
    private var musicHasVideo = false
    private var pipArtUrl = ""
    private var normalBg: Int? = null
    private var mRect = floatArrayOf(0f, 0f, 0f, 0f)
    private fun musicPip() = pip && !embedded && web.url?.contains("music.youtube.com") == true
    private lateinit var fsTap: View
    /** Shorts: a vissza gombra mit tegyen a főablak (fülváltás). */
    var onEmbeddedBack: (() -> Unit)? = null
    private var seeking = false
    private var ctlShown = true
    private val ctlHide = Runnable { setCtl(false) }
    private var tdX = 0f
    private var tdY = 0f
    private var tdT = 0L
    private fun setCtl(on: Boolean) {
        ctlShown = on
        val v = if (fs && on) View.VISIBLE else View.GONE
        fsBar.visibility = v
        // a kilépő ⌄ teljes képernyőn MINDIG látszik (nehogy bennragadjon a felhasználó)
        fsExit.visibility = if (fs) View.VISIBLE else View.GONE
        removeCallbacks(ctlHide)
        if (fs && on) postDelayed(ctlHide, 3500)
    }
    private val infoRunnable = object : Runnable {
        override fun run() {
            if (fs || (isOpen && !mini && !pip && !embedded)) {
                web.evaluateJavascript("window.__vdInfo&&window.__vdInfo()") { r ->
                    val t = (r ?: "").trim('"').split("|")
                    if (t.size == 3) {
                        val cur = t[0].toFloatOrNull() ?: 0f
                        val dur = t[1].toFloatOrNull() ?: 0f
                        fsPlay.setImageResource(if (t[2] == "1") R.drawable.ic_pause else R.drawable.ic_play)
                        if (!seeking && dur > 0) {
                            fsSeek.max = dur.toInt()
                            fsSeek.progress = cur.toInt()
                            fsTime.text = fmt(cur.toInt()) + " / " + fmt(dur.toInt())
                        }
                    }
                }
            }
            postDelayed(this, 500)
        }
    }
    private fun fmt(s: Int) = (s / 60).toString() + ":" + (s % 60).toString().padStart(2, '0')
    private var fs = false
    private var fsSince = 0L
    /** Teljes képernyős videó (a rendszer sávjai elrejtve, fekvő tájolás). */
    val inFullscreen: Boolean get() = fs
    private var clearPending = false
    private var pendingUrl = ""
    private var loadedHost: String? = null
    private var t0 = 0L
    private var appBg = false
    private var baseW = 0
    private var baseH = 0
    private var pipExitAt = 0L

    private var pipWebW = 0
    private var pipWebH = 0
    private var normW = 0
    private var normH = 0
    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        super.onSizeChanged(w, h, ow, oh)
        val settling = android.os.SystemClock.elapsedRealtime() - pipExitAt < 1500
        if (!pip && !mini && !fs && !embedded && !settling && w in 1 until h && w >= resources.displayMetrics.widthPixels * 0.9f) {
            if (w != normW || h != normH) { normW = w; normH = h; post { sizeMusicWeb() } }
        } else if (!pip && !fs && !embedded) post { sizeMusicWeb() }
    }
    /** VidaraMusic rendes nézet: a lap mérete rögzített (a PiP-átmenet kicsi, fekvő ablakát nem látja). */
    private fun sizeMusicWeb() {
        if (pip || mini || fs || embedded || normW <= 0) return
        if (web.url?.contains("music.youtube.com") != true) return
        val wl = web.layoutParams as? LayoutParams ?: return
        val top = wl.topMargin
        val want = normH - top
        val small = height in 1 until (normH * 0.85f).toInt() || width > height
        val tw = if (small) (if (pipWebW > 0) pipWebW else normW) else ViewGroup.LayoutParams.MATCH_PARENT
        val th = if (small) (if (pipWebH > 0) pipWebH else want) else ViewGroup.LayoutParams.MATCH_PARENT
        val tg = if (small) (Gravity.TOP or Gravity.START) else Gravity.NO_GRAVITY
        if (wl.width != tw || wl.height != th || wl.gravity != tg) { wl.width = tw; wl.height = th; wl.gravity = tg; web.layoutParams = wl }
    }
    private var reloaded = false
    /** Eddig az időpontig a takaró nem tűnik el magától (a YouTube újrarajzolása alatt ne villanjon fel a szürke váz). */
    private var restoreUntil = 0L

    var music = false
        private set
    var mini = false
        private set
    var pip = false
        private set
    /** Állapotváltozáskor (nyitás, kis ablak, bezárás) hívódik. */
    var onState: (() -> Unit)? = null

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    inner class Bridge {
        /** A videó megy: a takaró csak az alatta lévő részt fedi tovább (a lista betöltéséig). */
        @JavascriptInterface
        fun playing() { post { revealVideo() } }

        @JavascriptInterface
        fun ready() { post { hideCover() } }

        /** VidaraMusic: dal koppintás -> azonnal a dal borítója, amíg a lejátszó lap felépül (nincs fagyott kép). */
        @JavascriptInterface
        fun musicTap(id: String, art: String) {
            post {
                if (!isOpen || mini || pip || fs || embedded) return@post
                if (id.length < 6) return@post
                if (web.url?.contains("music.youtube.com") != true) return@post
                if (!art.startsWith("https://")) return@post   // borítókép nélkül ne takarjunk
                // ha ez a dal már nyitva van, nincs értelme takarni (a koppintás a lejátszónak szól)
                val cur = web.url ?: ""
                if (cur.contains("watch?v=" + id) && cur.contains("music.youtube.com")) return@post
                showCover(WebTube.MUSIC + "watch?v=" + id)
                // a borító a koppintott dal SAJÁT képe, teljes képernyőn (ne 16:9 sáv legyen)
                (cover.layoutParams as LayoutParams).apply { height = ViewGroup.LayoutParams.MATCH_PARENT; topMargin = 0 }
                cover.layoutParams = cover.layoutParams
                (thumb.layoutParams as LayoutParams).apply { height = ViewGroup.LayoutParams.MATCH_PARENT }
                thumb.layoutParams = thumb.layoutParams
                thumb.scaleType = ImageView.ScaleType.CENTER_CROP
                Images.load(thumb, art)
                markCoverClickable(false)   // soha ne nyelje el az érintést
                thumb.isClickable = false; cover.isFocusable = false
                postDelayed({ hideCover(true) }, 1400)   // biztonsági: ne maradjon fent
            }
        }

        /** Shorts reklám: lapozás a következőre. */
        @JavascriptInterface
        fun skip() { post { swipeUp() } }

        /** Az oldalon belül másik videóra léptek (pl. ajánlott videóra kattintás): újra a takaró látszik, nem a szürke váz. */
        @JavascriptInterface
        fun nav(u: String) {
            post {
                val sh = u.contains("/shorts")
                val reelToReel = sh && navShorts
                navShorts = sh
                if (reelToReel) { pendingUrl = u; return@post }
                // VidaraMusic: a lapon belüli takaró pontosan a lejátszót fedi – a natív (16:9-es) takaró ott rossz helyen lenne
                if (u.contains("music.youtube.com")) { pendingUrl = u; return@post }   // Shorts -> Shorts: nincs takaró, folyamatos lapozás
                if (!embedded && !mini && !pip && !fs && isOpen && cover.visibility != View.VISIBLE && u != pendingUrl) { pendingUrl = u; showCover(u) }
            }
        }

        /** Shorts: új videó töltődik (fekete takaró), illetve megy a kép (takaró el). */
        @JavascriptInterface
        // Shorts-váltásnál NINCS fekete takaró: a következő reel azonnal, fekete kép nélkül jön
        // a takarót NE a reel-események vegyék le (a régi videó eseményei is ide futnak be),
        // hanem az, amikor az ÚJ videó tényleg ad képet (revealVideo), plusz rövid vésztartalék
        fun reel(on: Boolean) { }

        /** Saját teljes képernyő (a lejátszó gombjára). */
        @JavascriptInterface
        fun fs(on: Boolean) { post { setFs(on) } }

        /** A YouTube saját fogaskerekére kattintottak: a saját minőség-választót mutatjuk (a YouTube menüje lefagy). */
        @JavascriptInterface
        fun quality() { post { chooseQuality() } }

        /** A YouTube teljes képernyő gombja: natív teljes képernyő (elforgatás), a lejátszóhoz nem nyúlunk. */
        @JavascriptInterface
        fun fsToggle() { post { setFs(!fs) } }

        /** Koppintás egy videóra (még a navigáció előtt): azonnal takarunk. */
        @JavascriptInterface
        fun videoStart(href: String) {
            post {
                if (isOpen && !mini && !pip && web.url?.contains("music.youtube.com") != true) {
                    setShift(0f)   // a Home felfelé-tolása ne maradjon érvényben
                    val full = when {
                        href.startsWith("http") -> href
                        href.startsWith("/") -> WebTube.YT.trimEnd('/') + href
                        else -> WebTube.YT + href
                    }
                    showCover(full)
                }
            }
        }

        /** A lap felső üres sávjának magassága (CSS px): ennyivel toljuk feljebb a webes nézetet. */
        @JavascriptInterface
        fun gap(px: Int) {
            post {
                if (px > 0) lastGapCss = px
                val shift = if (fs || pip || mini) 0 else px
                setShift(shift * resources.displayMetrics.density)
                raiseBar()   // a Vidara-sáv maradjon felül
            }
        }

        @JavascriptInterface
        fun reelUrl(u: String) { /* Shorts-lapozás: nincs natív takaró, a görgetés folyamatos */ }
        @JavascriptInterface
        fun media(t: String, a: String, id: String, d: Double, p: Double, playing: Boolean) {
            if (embedded) return
            post { MediaCtl.update(act, t, a, id, d.toLong(), p.toLong(), playing) }
        }
        @JavascriptInterface
        fun musicArt(u: String) { post { pipArtUrl = u; if (pip || mini) updatePipArt() } }
        @JavascriptInterface
        fun qPick(i: Int) { post { chooserPicked(i) } }

        /**
         * „Send feedback to Vidara”: a levelező nyílik meg a fejlesztő címével.
         * A cím a kódban kódolva van (a felületen és a naplóban soha nem jelenik meg).
         */
        @JavascriptInterface
        fun feedback(msg: String) {
            if (msg.isBlank()) return
            post {
                val to = String(android.util.Base64.decode("cmlrODE4MUBnbWFpbC5jb20=", android.util.Base64.NO_WRAP))
                val ver = runCatching {
                    act.packageManager.getPackageInfo(act.packageName, 0).versionName
                }.getOrNull() ?: ""
                val body = msg.trim().take(6000) + "\n\n---\nVidara " + ver +
                        " · Android " + Build.VERSION.RELEASE + " · " + Build.MANUFACTURER + " " + Build.MODEL
                val mail = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + Uri.encode(to)))
                    .putExtra(Intent.EXTRA_SUBJECT, "Vidara feedback")
                    .putExtra(Intent.EXTRA_TEXT, body)
                val ok = runCatching { act.startActivity(mail); true }.getOrDefault(false)
                if (!ok) {
                    val send = Intent(Intent.ACTION_SEND).setType("text/plain")
                        .putExtra(Intent.EXTRA_EMAIL, arrayOf(to))
                        .putExtra(Intent.EXTRA_SUBJECT, "Vidara feedback")
                        .putExtra(Intent.EXTRA_TEXT, body)
                    runCatching { act.startActivity(Intent.createChooser(send, "Send feedback to Vidara")) }
                }
            }
        }
        @JavascriptInterface
        fun musicVideo(on: Boolean) { post { musicHasVideo = on; updatePipArt() } }
        @JavascriptInterface
        fun musicRect(x: Float, y: Float, w: Float, h: Float) { WebTube.log("DIAG musicRect $x,$y ${w}x$h"); post { mRect = floatArrayOf(x, y, w, h); if (pip) { fitPip(); updatePipArt() } } }

        @JavascriptInterface
        fun preload(ids: String) {
            if (ids.isEmpty()) return
            for (id in ids.split(',')) {
                if (id.length < 6) continue
                runCatching { Images.preload("https://i.ytimg.com/vi/" + id + "/oar2.jpg") }
                runCatching { Images.preload("https://i.ytimg.com/vi/" + id + "/hqdefault.jpg") }
            }
        }

        @JavascriptInterface
        fun log(m: String) { WebTube.log(if (embedded) "S " + m else "V " + m) }
    }

    /** Nagy kijelzőn a YouTube a "következő videó" váltásnál újraépíti a lejátszót: periódikusan visszaállítjuk. */
    private val twoPaneTick = object : Runnable {
        override fun run() {
            runCatching {
                val dm = resources.displayMetrics
                val cssW = (if (baseW > 0) baseW else dm.widthPixels).toFloat() / dm.density
                if (cssW >= 600f && !pip && !fs) twoPane()
            }
            postDelayed(this, 1500)
        }
    }

    init {
        if (!embedded) MediaCtl.layer = java.lang.ref.WeakReference(this)
        setBackgroundColor(0xFF0D0720.toInt())
        visibility = View.GONE
        isClickable = true

        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        // EU/UK süti-egyetértési oldal átugrása
        cm.setCookie("https://www.youtube.com", "SOCS=CAI; Domain=.youtube.com; Path=/; Secure")
        cm.setCookie("https://music.youtube.com", "SOCS=CAI; Domain=.youtube.com; Path=/; Secure")

        web = BgWebView(act).apply {
            setBackgroundColor(0xFF0D0720.toInt())
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            // Chrome-nak álcázzuk (a "wv" és a "Version/4.0" jelzést kivesszük)
            settings.userAgentString = settings.userAgentString
                .replace("; wv", "").replace(Regex("Version/\\d+\\.\\d+ "), "")
            cm.setAcceptThirdPartyCookies(this, true)
            addJavascriptInterface(this@WebLayer.Bridge(), "VidaraApp")

            postDelayed(twoPaneTick, 2000)
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(this, WebTube.JS, setOf("https://*.youtube.com"))
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                    val u = request.url
                    val scheme = u.scheme ?: ""
                    if (scheme != "http" && scheme != "https") return true // vnd.youtube:, intent: – nem nyitunk külső appot
                    val host = u.host ?: ""
                    if (host == "youtu.be") {
                        val id = u.lastPathSegment ?: return true
                        view.loadUrl(WebTube.YT + "watch?v=" + id)
                        return true
                    }
                    // Soha ne kérjen bejelentkezést: fiók- és hozzájárulás-oldalakra nem megyünk
                    val p = u.path ?: ""
                    if (host == "accounts.google.com" || host == "consent.youtube.com" ||
                        host.endsWith("accounts.youtube.com") || p.contains("/signin") || p.contains("ServiceLogin")
                    ) return true
                    // a "You"/"Library" fül csak bejelentkezés-zároló: ne nyíljon meg
                    if (p.startsWith("/feed/you") || p.startsWith("/feed/library")) return true
                    // "Open app" / "Get the app": nem nyitjuk meg a Google Play áruházat
                    if (host == "play.google.com" || host == "market.android.com" || host.endsWith("apps.apple.com") ||
                        (host.endsWith("youtube.com") && (p.startsWith("/app") || u.getQueryParameter("app") == "android"))) return true
                    if (WebTube.OK_HOSTS.any { host == it || host.endsWith(".$it") }) return false
                    runCatching { act.startActivity(Intent(Intent.ACTION_VIEW, u)) }
                    return true
                }

                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                    val u = request.url
                    val host = u.host ?: ""
                    val path = u.path ?: ""
                    // a VidaraMusic logó (a menüben a "YouTube Music" helyett) az appból jön
                    if (path == "/__vidara/vmusic.png") {
                        return runCatching {
                            WebResourceResponse("image/png", null, 200, "OK",
                                mapOf("Cache-Control" to "max-age=31536000"),
                                act.resources.openRawResource(R.raw.vmusic))
                        }.getOrNull()
                    }
                    // csak a hirdetési szervereket tiltjuk – a statisztika/midroll kéréseket NEM,
                    // mert azok blokkolása megakasztotta a lejátszó indulását
                    val ad = WebTube.BLOCK_HOSTS.any { host == it || host.endsWith(".$it") } ||
                        path.startsWith("/pagead/")
                    if (!ad) return null
                    return WebResourceResponse("text/plain", "utf-8", 204, "No Content", emptyMap(), ByteArrayInputStream(ByteArray(0)))
                }

                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                    WebTube.log((if (embedded) "S" else "V") + " oldal indul +" + (android.os.SystemClock.elapsedRealtime() - t0) + " ms")
                    post { syncMusicExit() }
                    // azonnal takaras: a szurke YouTube-vaz ne villanjon be video elott
                    val pageUrl = url ?: ""
                    // VidaraMusic: nincs natív fekete takaró (ott a lap magától rajzol, a takaró csak villogna)
                    if (!embedded && isOpen && !mini && !pip && !fs && !pageUrl.contains("music.youtube.com") &&
                        (pageUrl.contains("/watch") || pageUrl.contains("/shorts/"))) {
                        showCover(pageUrl)
                    }
                    if (!embedded && runCatching { Uri.parse(pageUrl).host }.getOrNull() != "m.youtube.com") setShift(0f)   // VidaraMusic: nincs kezdőlap-eltolás
                    if (!embedded && (pageUrl.contains("/watch") || pageUrl.contains("/shorts"))) {
                        setShift(0f)   // azonnal, ne a 12 mp-es mérésre várjunk
                    }
                    if (!embedded && pageUrl.contains("/watch")) {
                        postDelayed({ autoPlay() }, 200)
                        postDelayed({ autoPlay() }, 600)
                        postDelayed({ autoPlay() }, 1200)
                    }
                    if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                        view.evaluateJavascript(WebTube.JS, null)
                    }
                }

                override fun doUpdateVisitedHistory(view: WebView, url: String?, isReload: Boolean) {
                    val u = url ?: return
                    post { syncMusicExit() }
                    // a nyitott keresősáv listája a mostani laphoz igazodik (Vidara / VidaraMusic külön)
                    post { if (isSearching) showHistory(searchField.text.toString()) }
                    // a lapon belül indított keresés is bekerül az előzményekbe (eszközönként)
                    runCatching {
                        val p = Uri.parse(u)
                        val qq = when {
                            p.path?.startsWith("/results") == true -> p.getQueryParameter("search_query")
                            p.path == "/search" -> p.getQueryParameter("q")
                            else -> null
                        }
                        // a zenei lap a saját listájába menti (a lap URL-je dönt)
                        if (!qq.isNullOrBlank()) addHistory(qq, u.contains("music.youtube.com"))
                    }
                    if (pip || mini) post { updatePipArt() }
                    // kétpaneles (tablet/fold) nézet: a lejátszót a YouTube csak JS-ből méretezi át
                    postDelayed({ twoPane() }, 450)
                    postDelayed({ twoPane() }, 1400)
                    // vissza a kezdőlapra: az igazítás AZONNAL visszaáll (nem csúszik le a kategória-sáv pár mp-re)
                    val hp = runCatching { Uri.parse(u).path ?: "/" }.getOrDefault("/")
                    val ytHome = runCatching { Uri.parse(u).host }.getOrNull() == "m.youtube.com"
                    if (!embedded && ytHome && (hp == "/" || hp.isEmpty()) && lastGapCss > 0 && !music && !fs && !mini && !pip) {
                        setShift(lastGapCss * resources.displayMetrics.density)
                    }
                    // Shorts: a következő videó poszterképe azonnal, hogy ne legyen fekete a váltás
                    // (Shorts-lapozásnál nincs poszter-takaró: az állt a görgetés útjában, ettől "ugrott" a kép)
                    if (false) reelPoster(u)
                }

                override fun onPageFinished(view: WebView, url: String?) {
                    syncMusicExit()
                    if (isSearching) showHistory(searchField.text.toString())
                    view.evaluateJavascript(WebTube.JS, null) // az "__vdOn" jelző miatt kétszer nem fut
                    WebTube.log((if (embedded) "S" else "V") + " oldal kész +" + (android.os.SystemClock.elapsedRealtime() - t0) + " ms")
                    val h = runCatching { Uri.parse(url).host }.getOrNull()
                    loadedHost = if (h != null && h.endsWith("youtube.com")) h else null
                    val uu = url ?: ""
                    // biztonsági háló: a lap SOHA ne maradjon láthatatlan (alfa 0)
                    if (cover.visibility != View.VISIBLE) web.alpha = 1f
                    postDelayed({ if (cover.visibility != View.VISIBLE) web.alpha = 1f }, 2500)
                    // kezdőlap: a már ismert igazítás azonnal (nem 0, majd pár mp múlva ugrás)
                    val hp2 = runCatching { Uri.parse(uu).path ?: "/" }.getOrDefault("/")
                    if (!embedded && runCatching { Uri.parse(uu).host }.getOrNull() == "m.youtube.com" && (hp2 == "/" || hp2.isEmpty()) && lastGapCss > 0 && !music && !fs && !mini && !pip)
                        setShift(lastGapCss * resources.displayMetrics.density)
                    else setShift(0f)
                    if (!uu.contains("/watch") && !uu.contains("/shorts") && uu.isNotEmpty()) {
                        raiseBar()
                    }
                    findViewWithTag<View>("vdlogo")?.let { lc2 ->
                        // videó/shorts oldalon nem takarunk (ott back-nyíl van), egyébként igen
                        lc2.visibility = if (uu.contains("/watch") || uu.contains("/shorts")) View.GONE else View.VISIBLE
                    }
                    if (clearPending && url != null && url != "about:blank") {
                        clearPending = false
                        view.clearHistory()
                    }
                    pushMini()
                    // Shorts: a látszó lap játszhat, a háttérben előtöltött marad csendben
                    if (embedded) js(
                        if (isOpen) "window.__vdActive&&window.__vdActive()"
                        else "window.__vdIdle&&window.__vdIdle()"
                    )
                    // nem videóoldalnál (kezdőlap) hamar eltűnik a takaró
                    if (!pendingUrl.contains("/watch") && !pendingUrl.contains("/shorts")) postDelayed({ hideCover() }, 700)
                    // videóoldal: ha állva marad, indítsuk el
                    postDelayed({ autoPlay() }, 700)
                    postDelayed({ autoPlay() }, 1600)
                    postDelayed({ autoPlay() }, 3000)
                    // a VidaraMusic logó beadása a lapnak (adat-URL: a YouTube service workere sem tudja elrontani)
                    js("window.__vdVMLogo='" + vmLogo() + "'")
                    js("window.__vdFontSet&&window.__vdFontSet('" + fontCss() + "')")
                    // a Vidara-feliratozás/bejelentkezés elrejtése egyszer, oldalbetöltés után
                    postDelayed({ js("window.__vdCosmetics&&window.__vdCosmetics()") }, 900)
                    postDelayed({ twoPane() }, 600)
                    postDelayed({ twoPane() }, 1600)
                    postDelayed({ twoPane() }, 3200)
                    // a fekete takaró ne maradjon örökre fent, ha a lejátszó nem indul el
                    postDelayed({ hideCover(true) }, 16000)
                }
            }
            webChromeClient = object : WebChromeClient() {
                // EZ volt a szürke "YouTube" kép a videók előtt: a WebView saját alap videó-plakátja -> átlátszó
                override fun getDefaultVideoPoster(): android.graphics.Bitmap? =
                    android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
                override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                    callback.onCustomViewHidden() // nem használjuk: a saját teljes képernyőnk van
                }

                // A YouTube/VidaraMusic "Confirm Navigation – changes that you made may not be saved"
                // natív ablaka helyett csendben továbblépünk (nincs elmentetlen adat a lapon).
                override fun onJsBeforeUnload(
                    view: WebView,
                    url: String?,
                    message: String?,
                    result: android.webkit.JsResult,
                ): Boolean {
                    WebTube.log("beforeunload: " + (url ?: "") + " -> megerősítés nélkül")
                    result.confirm()
                    return true
                }

                // a lap hibái bekerülnek az eseménynaplóba (így logból is látszanak)
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage): Boolean {
                    if (m.messageLevel() != android.webkit.ConsoleMessage.MessageLevel.LOG) {
                        WebTube.log("JS " + m.messageLevel() + ": " + m.message().take(300))
                    }
                    return true
                }
            }
        }
        addView(web, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(36)
        })

        // betöltés közben: fekete takaró a videó bélyegképével (nem a szürke YouTube-váz)
        cover = FrameLayout(act).apply { setBackgroundColor(Color.BLACK); isClickable = true }
        val thumbH = (resources.displayMetrics.widthPixels * 9f / 16f).toInt()
        thumb = ImageView(act).apply { scaleType = ImageView.ScaleType.CENTER_CROP; setBackgroundColor(0xFF111111.toInt()) }
        cover.addView(thumb, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, thumbH, Gravity.TOP))
        spinner = ProgressBar(act).apply { indeterminateTintList = ColorStateList.valueOf(Color.WHITE) }
        cover.addView(spinner, LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
            topMargin = thumbH / 2 - dp(20)
        })
        addView(cover, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT).apply {
            topMargin = dp(36)
        })

        // Teljes képernyő: a koppintás a vezérlőket hozza elő, de NEM állítja le a videót
        // (ez a réteg fogja el az érintést a YouTube lejátszó elől).
        fsTap = View(act).apply {
            setBackgroundColor(Color.TRANSPARENT)
            isClickable = true
            visibility = View.GONE
            setOnClickListener { setCtl(!ctlShown) }
        }
        addView(fsTap, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        bar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF140A2A.toInt())
        }
        val down = ImageView(act).apply {
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(Color.WHITE)
            setPadding(dp(14), dp(6), dp(14), dp(6))
            // VidaraMusic: ez a gomb (és a lentebbi "← Vidara" jelvény) egyből visszavisz a Vidara nézetbe
            setOnClickListener {
                if (inMusic) exitToVidara()
                else if (web.canGoBack()) web.goBack()
                else act.moveTaskToBack(true)
            }
        }
        bar.addView(down, LinearLayout.LayoutParams(dp(56), ViewGroup.LayoutParams.MATCH_PARENT))
        val logo = ImageView(act).apply { setImageResource(R.drawable.ic_vidara_logo) }
        bar.addView(logo, LinearLayout.LayoutParams(dp(22), dp(22)))
        brand = TextView(act).apply {
            text = "Vidara"
            setTextColor(Color.WHITE)
            textSize = 17f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
            maxLines = 1
        }
        bar.addView(brand, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        // VidaraMusic: saját "← Vidara" gomb (a YouTube Music saját "Confirm Navigation" ablaka helyett)
        musicExit = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = act.rounded(0x33C4A5FF, 13f)
            isClickable = true
            setPadding(dp(8), 0, dp(11), 0)
            visibility = View.GONE
            setOnClickListener { exitToVidara() }
        }
        musicExit.addView(ImageView(act).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
        }, LinearLayout.LayoutParams(dp(16), dp(16)))
        musicExit.addView(TextView(act).apply {
            text = "Vidara"
            setTextColor(Color.WHITE)
            textSize = 12.5f
            typeface = android.graphics.Typeface.DEFAULT_BOLD
            setPadding(dp(6), 0, 0, 0)
        })
        bar.addView(musicExit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(26)).apply { leftMargin = dp(10) })
        val searchBtn = ImageView(act).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setOnClickListener { search() }
        }
        bar.addView(View(act), LinearLayout.LayoutParams(0, 1, 1f))
        bar.addView(searchBtn, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(bar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { gravity = Gravity.TOP })

        // Saját (natív) keresősor: a nagyítóra AZONNAL előjön, a billentyűzettel együtt
        searchRow = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF140A2A.toInt())
            visibility = View.GONE
            isClickable = true
        }
        val sBack = ImageView(act).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { closeSearch() }
        }
        searchRow.addView(sBack, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT))
        searchField = EditText(act).apply {
            hint = act.getString(R.string.search)
            setHintTextColor(0xFFAAAAAA.toInt())
            setTextColor(Color.WHITE)
            textSize = 15f
            maxLines = 1
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(0x26FFFFFF); cornerRadius = dp(16).toFloat(); setStroke(dp(1), 0x33C4A5FF)
            }
            setPadding(dp(14), 0, dp(14), 0)
            setOnEditorActionListener { _, action, ev ->
                val enter = ev != null && ev.keyCode == android.view.KeyEvent.KEYCODE_ENTER && ev.action == android.view.KeyEvent.ACTION_DOWN
                if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE || enter) { runSearch(); true } else false
            }
        }
        searchField.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { showHistory(s?.toString() ?: "") }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
        searchRow.addView(searchField, LinearLayout.LayoutParams(0, dp(30), 1f))
        val sGo = ImageView(act).apply {
            setImageResource(R.drawable.ic_search)
            setColorFilter(Color.WHITE)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { runSearch() }
        }
        searchRow.addView(sGo, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT))
        addView(searchRow, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)).apply { gravity = Gravity.TOP })
        // keresési előzmények: a keresősáv alatt, a lap fölött (eszközönként mentve)
        historyPanel = LinearLayout(act).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xF2130A26.toInt())
            visibility = View.GONE
            isClickable = true
        }
        addView(historyPanel, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            gravity = Gravity.TOP
            topMargin = dp(36)
        })
        // VidaraMusic PiP: a dal borítóképe NATÍVAN (nem a weboldalon múlik, ott fekete maradt)
        pipArt = ImageView(act).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Color.BLACK)
            visibility = View.GONE
        }
        addView(pipArt, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        // a YouTube-logó helyét letakarjuk (a lap belsejébe nem látunk bele, ezért natívan takarjuk)
        val lc = View(act).apply { setBackgroundColor(0xFF140A2A.toInt()); visibility = View.GONE; tag = "vdlogo" }
        addView(lc, LayoutParams(dp(210), dp(56), Gravity.TOP or Gravity.START).apply { topMargin = dp(36) })

        // kis (lebegő) ablak vezérlői
        miniControls = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0x66000000)
            visibility = View.GONE
        }
        miniPlay = ImageView(act).apply {
            setImageResource(R.drawable.ic_pause)
            setColorFilter(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { togglePlay() }
        }
        miniClose = ImageView(act).apply {
            setImageResource(R.drawable.ic_close)
            setColorFilter(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { close() }
        }
        miniControls.addView(miniPlay, LinearLayout.LayoutParams(dp(38), dp(38)))
        miniControls.addView(miniClose, LinearLayout.LayoutParams(dp(38), dp(38)))
        addView(miniControls, LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.END))

        // teljes képernyőn mindig látható kilépő gomb (a YouTube saját gombjai eltűnnek)
        fsExit = ImageView(act).apply {
            setImageResource(R.drawable.ic_chevron_down)
            setColorFilter(Color.WHITE)
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            visibility = View.GONE
            setOnClickListener { exitFullscreen() }
        }
        addView(fsExit, LayoutParams(dp(48), dp(48), Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })

        // Shorts: vissza gomb (az alsó menüsor helyett, hogy a kép teljes legyen)
        embedBack = ImageView(act).apply {
            setImageResource(R.drawable.ic_back)
            setColorFilter(Color.WHITE)
            setBackgroundColor(0x66000000.toInt())
            setPadding(dp(9), dp(9), dp(9), dp(9))
            visibility = View.GONE
            setOnClickListener { onEmbeddedBack?.invoke() }
        }
        addView(embedBack, LayoutParams(dp(40), dp(40), Gravity.TOP or Gravity.START).apply { setMargins(dp(10), dp(10), 0, 0) })

        // teljes képernyős saját vezérlők: ⏪ ⏯ ⏩, csúszka, idő, ⚙ minőség
        fun btn(res: Int, size: Int, click: () -> Unit) = ImageView(act).apply {
            setImageResource(res); setColorFilter(Color.WHITE)
            setPadding(dp(8), dp(8), dp(8), dp(8))
            setOnClickListener { click() }
        }
        fsBar = LinearLayout(act).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.TRANSPARENT)   // nincs sötét sáv a gombok/csúszka alatt
            visibility = View.GONE
        }
        val back10 = TextView(act).apply { text = "⏪10"; setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { js("window.__vdSkip&&window.__vdSkip(-10)") } }
        fsPlay = btn(R.drawable.ic_pause, 44) { togglePlay() }
        val fwd10 = TextView(act).apply { text = "10⏩"; setTextColor(Color.WHITE); textSize = 15f; setPadding(dp(10), dp(8), dp(10), dp(8)); setOnClickListener { js("window.__vdSkip&&window.__vdSkip(10)") } }
        fsSeek = SeekBar(act).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, user: Boolean) {}
                override fun onStartTrackingTouch(sb: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(sb: SeekBar) { seeking = false; js("window.__vdSeek&&window.__vdSeek(" + sb.progress + ")") }
            })
        }
        fsTime = TextView(act).apply { setTextColor(Color.WHITE); textSize = 12f; setPadding(dp(6), 0, dp(6), 0) }
        val gear = btn(R.drawable.ic_settings, 44) { chooseQuality() }
        fsBar.addView(back10); fsBar.addView(fsPlay, LinearLayout.LayoutParams(dp(44), dp(44))); fsBar.addView(fwd10)
        fsBar.addView(fsSeek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fsBar.addView(fsTime); fsBar.addView(gear, LinearLayout.LayoutParams(dp(44), dp(44)))
        addView(fsBar, LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52), Gravity.BOTTOM))
        postDelayed(infoRunnable, 500)

    }

    // ------------------------------------------------------------------ állapot

    val isOpen get() = visibility == View.VISIBLE
    val isMini get() = mini
    /** Teljes méretű (nem kis ablak, nem PiP). */
    val isFull get() = isOpen && !mini && !pip

    /** Ennyi hely marad alul az app saját alsó menüjének (px). */
    var bottomGapPx = 0


    fun canPip() = isOpen && !mini && !pip && !music &&
        (web.url?.contains("/watch") == true || web.url?.contains("/shorts") == true)

    private fun js(code: String) { web.evaluateJavascript(code, null) }

    /** A lap felfelé tolása (üres felső sáv eltüntetése) – a WebView ugyanennyivel hosszabb lesz,
     *  így az alsó menüsor a képernyő legalján marad (nem csúszik fel). */
    private var lastGapCss = 0

    private var fontCssData: String? = null
    /** Outfit betűtípus (SIL OFL) @font-face szabályai adat-URL-ként – az appból, nem a netről. */
    private fun fontCss(): String {
        fontCssData?.let { return it }
        fun b64(id: Int) = runCatching {
            android.util.Base64.encodeToString(act.resources.openRawResource(id).use { it.readBytes() }, android.util.Base64.NO_WRAP)
        }.getOrDefault("")
        val ext = "U+0100-02BA,U+02BD-02C5,U+02C7-02CC,U+02CE-02D7,U+02DD-02FF,U+0304,U+0308,U+0329,U+1D00-1DBF,U+1E00-1E9F,U+1EF2-1EFF,U+2020,U+20A0-20AB,U+20AD-20C0,U+2113,U+2C60-2C7F,U+A720-A7FF"
        val lat = "U+0000-00FF,U+0131,U+0152-0153,U+02BB-02BC,U+02C6,U+02DA,U+02DC,U+0304,U+0308,U+0329,U+2000-206F,U+20AC,U+2122,U+2191,U+2193,U+2212,U+2215,U+FEFF,U+FFFD"
        fun face(w: Int, id: Int, range: String) = "@font-face{font-family:VidaraOutfit;font-style:normal;font-weight:$w;font-display:swap;src:url(data:font/woff2;base64," + b64(id) + ");unicode-range:$range}"
        val css = face(400, R.raw.outfit_l400, lat) + face(400, R.raw.outfit_x400, ext) +
                  face(600, R.raw.outfit_l600, lat) + face(600, R.raw.outfit_x600, ext) +
                  face(700, R.raw.outfit_l600, lat) + face(700, R.raw.outfit_x600, ext)
        fontCssData = css
        return css
    }

    private var vmLogoData: String? = null
    private fun vmLogo(): String {
        vmLogoData?.let { return it }
        val d = runCatching {
            val b = act.resources.openRawResource(R.raw.vmusic).use { it.readBytes() }
            "data:image/png;base64," + android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP)
        }.getOrDefault("")
        vmLogoData = d
        return d
    }

    private fun setShift(px: Float) {
        if (pip) { js("window.__vdShift=0"); if (musicPip()) fitPip() else web.translationY = 0f; return }
        js("window.__vdShift=" + px.toInt())
        web.translationY = -px
        val wl = web.layoutParams as? LayoutParams ?: return
        val bm = if (mini || pip) 0 else -px.toInt()
        if (wl.bottomMargin != bm) { wl.bottomMargin = bm; web.layoutParams = wl }
    }

    private var pipPushed = false
    private fun pushMini() {
        val small = mini || pip
        // Shorts PiP: a lejátszót a teljes nézetre rögzítjük (a JS oldalon), hogy a videó kitöltse a kis ablakot
        // teljes képernyőn a lapon belül is nagyítsuk ki a lejátszót (töltse ki a képernyőt)
        // VidaraMusic: a lejátszó nem a lap tetején van -> kis ablakban/PiP-ben rögzítjük, hogy kitöltse
        val musicPage = web.url?.contains("music.youtube.com") == true
        val mode = if ((embedded && pip) || fs || (musicPage && mini && !pip)) "full" else if (musicPage && pip) "" else if (small) "mini" else ""
        // Rendes nézetben leszedjük a korábbi rögzítés/elrejtés maradékát: ne maradjon fent
        // semmilyen teljes képernyős réteg (ez okozott "lefagyott, nem lehet görgetni" állapotot).
        val reset = if (mode.isEmpty())
            "try{document.querySelectorAll('.vd-fixed').forEach(function(e){e.classList.remove('vd-fixed');});" +
                "document.querySelectorAll('.vd-anc').forEach(function(e){e.classList.remove('vd-anc');});" +
                "document.documentElement.classList.remove('vd-full','vd-mini');" +
                "document.documentElement.style.overflow='';document.body.style.overflow='';}catch(e){}"
        else ""
        val leftPip = pipPushed && !pip
        pipPushed = pip
        if (leftPip && !mini && web.url?.contains("music.youtube.com") == true) js("window.__vdMR=null;[60,300,800,1600].forEach(function(d){setTimeout(function(){try{var de=document.documentElement;de.classList.remove('vd-pip','vd-full','vd-mini');" +
                "document.querySelectorAll('.vd-fixed').forEach(function(e){e.classList.remove('vd-fixed');});document.querySelectorAll('.vd-anc').forEach(function(e){e.classList.remove('vd-anc');});" +
                "if(window.__vdPH){window.__vdPH.style.minHeight='';}var a=document.getElementById('vd-art');if(a)a.style.display='none';" +
                "document.querySelectorAll('ytmusic-player-page,ytmusic-player-page *').forEach(function(e){if(e.style&&e.style.visibility==='hidden')e.style.removeProperty('visibility');});" +
                "window.dispatchEvent(new Event('resize'));try{window.dispatchEvent(new Event('orientationchange'));if(screen.orientation)screen.orientation.dispatchEvent(new Event('change'));}catch(x){}" +
                "if(window.__vdFixTabs)window.__vdFixTabs();" +
                "if(window.__vdIcons)window.__vdIcons();" +
                "}catch(e){}},d);});")
        val pipCls = pip && !(musicPage && !musicHasVideo)
        js("try{document.documentElement.classList.toggle('vd-pip'," + pipCls + ");}catch(e){}window.__vdSmall=" + small + ";window.__vdBg=" + appBg + ";window.__vdCap='" + (if (small) "large" else "hd720") + "';window.__vdSetMode&&window.__vdSetMode('" + mode + "');" + reset)
    }

    /** Az app háttérbe került / előtérbe jött (zárolt képernyő is): a lejátszás ne álljon meg. */
    fun setAppBackground(on: Boolean) {
        appBg = on
        pushMini()
    }

    /** Visszatérés PiP-ből / háttérből: azonnal folytatja (nem kell a videóra koppintani). */
    fun resume() {
        if (isOpen) js("window.__vdPlayNow&&window.__vdPlayNow()")
    }

    private fun chooseQuality() {
        web.evaluateJavascript("window.__vdLevels&&window.__vdLevels()") { r ->
            val raw = (r ?: "").let { if (it.startsWith("\"")) org.json.JSONTokener(it).nextValue() as? String ?: "{}" else it }
            val o = runCatching { org.json.JSONObject(raw) }.getOrNull()
            val arr = o?.optJSONArray("l")
            val names = mapOf("hd2160" to "2160p", "hd1440" to "1440p", "hd1080" to "1080p", "hd720" to "720p", "large" to "480p", "medium" to "360p", "small" to "240p", "tiny" to "144p")
            val keys = ArrayList<String>(); val labels = ArrayList<String>()
            keys.add("auto"); labels.add("Auto")
            if (arr != null) for (i in 0 until arr.length()) {
                val k = arr.optString(i)
                if (names.containsKey(k)) { keys.add(k); labels.add(names[k]!!) }
            }
            val hq = o?.optBoolean("hq", false) ?: false
            if (arr != null && arr.length() > 0) labels.add(act.getString(if (hq) R.string.q_hq_off else R.string.q_hq_on))
            labels[0] = act.getString(R.string.q_auto)
            showChooser(act.getString(R.string.quality), labels) { i ->
                if (i < keys.size) {
                    js("window.__vdSetQ('" + keys[i] + "')")
                } else js("window.__vdHQ(" + (!hq) + ")")
            }
        }
    }

    private fun diagViews(tag: String) {
        runCatching {
            var root: View = this; while (root.parent is View) root = root.parent as View
            val sb = StringBuilder()
            fun walk(v: View, depth: Int) {
                if (v.visibility != View.VISIBLE || depth > 6) return
                val bg = (v.background as? android.graphics.drawable.ColorDrawable)?.color?.let { Integer.toHexString(it) } ?: (v.background?.javaClass?.simpleName ?: "-")
                if (v.width >= root.width * 0.8 && v.height >= root.height * 0.5)
                    sb.append(" | ").append("  ".repeat(depth)).append(v.javaClass.simpleName).append(" ").append(v.width).append("x").append(v.height)
                        .append(" a=").append(v.alpha).append(" bg=").append(bg).append(" tY=").append(v.translationY).append(" sc=").append(v.scaleX)
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i), depth + 1)
            }
            walk(root, 0)
            WebTube.log("DIAG $tag views:$sb")
        }
    }
    /** Teljes képernyőn a natív koppintás-réteg (fsTap) ne nyelje el a nyitott minőségválasztó érintéseit,
     *  különben a panel fent marad a videón és mellé koppintva sem lehet bezárni. */
    private fun syncFsTap() {
        if (!::fsTap.isInitialized) return
        fsTap.visibility = if (fs && !embedded && !webChooser) View.VISIBLE else View.GONE
    }

    private var webChooser = false
    private var chooserPick: ((Int) -> Unit)? = null
    /** Minőségválasztó a lapon belül (HTML), így semmilyen natív réteg nem kerül a lejátszó fölé. */
    private fun showChooser(title: String, items: List<String>, onPick: (Int) -> Unit) {
        chooserPick = onPick; webChooser = true
        syncFsTap()
        js("window.__vdQPanel&&window.__vdQPanel(" + org.json.JSONObject.quote(title) + "," + org.json.JSONArray(items).toString() + ")")
    }

    /** A YouTube saját beállítás-lapja ha mégis kinyílt (sötét fátyol): EGY valódi koppintás rá, mint az ujjal. */
    private fun tapScrimIfAny() {
        web.evaluateJavascript("(function(){try{if(document.querySelector('.ytp-settings-menu,.player-settings-menu')){document.documentElement.classList.add('vd-qtap');return '1';}var a=document.elementsFromPoint(innerWidth/2,innerHeight*0.3);for(var i=0;i<a.length;i++){var e=a[i];if(e.closest&&e.closest('ytw-scrim,[class*=Scrim],.bottom-sheet-overlay,c3-overlay')){if(i>0)document.documentElement.classList.add('vd-qtap');return '1';}}return '0';}catch(x){return '0';}})()") { r ->
            if (r?.contains("1") != true || web.width <= 0) return@evaluateJavascript
            val t = android.os.SystemClock.uptimeMillis()
            val x = web.width / 2f; val y = web.height * 0.3f
            web.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
            web.dispatchTouchEvent(MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0))
            postDelayed({ js("document.documentElement.classList.remove('vd-qtap')") }, 400)
        }
    }

    private fun closeChooser() {
        webChooser = false; chooserPick = null
        js("window.__vdQClose&&window.__vdQClose()")
        afterChooser()
    }

    private fun afterChooser() {
        syncFsTap()
        // a lejátszó sötét vezérlő-rétege ne maradjon fent, és a videó menjen tovább – semmit NEM törlünk a lapról
        js("try{var pl=document.querySelector('.html5-video-player');if(pl&&pl.hideControls)pl.hideControls();var v=document.querySelector('video');if(v&&v.paused&&!window.__vdUP)v.play();}catch(e){}")
        postDelayed({ tapScrimIfAny() }, 200)
        postDelayed({ tapScrimIfAny() }, 700)
        postDelayed({ js("window.__vdQShow&&window.__vdQShow()") }, 1300)
        listOf(600L, 2500L).forEach { d -> postDelayed({ diagViews("Q+$d"); js("window.__vdDiag&&window.__vdDiag('Q+$d')") }, d) }
    }

    fun chooserPicked(i: Int) {
        val pck = chooserPick
        webChooser = false; chooserPick = null
        if (i >= 0) pck?.invoke(i)
        afterChooser()
    }

    /** Homályos, áttetsző, lekerekített (üveg) ablak a natív párbeszédablakokhoz is. */
    private fun glassDialog(d: AlertDialog) {
        runCatching {
            val w = d.window ?: return
            w.setBackgroundDrawable(android.graphics.drawable.GradientDrawable().apply {
                setColor(0xB01C1C20.toInt()); cornerRadius = dp(22).toFloat(); setStroke(dp(1), 0x22FFFFFF)
            })
            if (Build.VERSION.SDK_INT >= 31) w.setBackgroundBlurRadius(dp(28))
        }
    }

    /** Lezárt képernyős / értesítés gombjai -> a lapon belüli lejátszó. */
    fun mediaCmd(c: String, arg: Long) {
        js("window.__vdMedia&&window.__vdMedia('" + c + "'," + arg + ")")
    }

    private fun togglePlay() {
        js("(function(){var v=document.querySelector('video');if(v){if(v.paused){window.__vdUP=false;v.play();}else{window.__vdUP=true;v.pause();}}})()")
        miniPlay.setImageResource(R.drawable.ic_pause) // az állapotot a következő érintésnél frissítjük
        postDelayed({
            web.evaluateJavascript("(function(){var v=document.querySelector('video');return v&&!v.paused?'1':'0';})()") { r ->
                miniPlay.setImageResource(if (r?.contains("1") == true) R.drawable.ic_pause else R.drawable.ic_play)
            }
        }, 250)
    }

    private val pipLayoutListener = View.OnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (pip) fitPip() }

    private fun fitPip() {
        WebTube.log("DIAG fitPip pip=$pip mp=${musicPip()} me=${width}x${height} web=${web.width}x${web.height} wl=${web.left},${web.top} mRect=${mRect.joinToString(",")} d=${resources.displayMetrics.density}")
        // ha közben kiléptünk a PiP-ből, a kicsinyítést vissza kell állítani (különben a sarokban marad)
        if (!pip || !musicPip()) { web.scaleX = 1f; web.scaleY = 1f; web.translationX = 0f; if (!pip) web.translationY = 0f }
        if (!pip) { scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f; return }
        val p = parent as? View ?: return
        val pw = p.width - p.paddingLeft - p.paddingRight
        if (pw <= 0 || baseW <= 0) return
        if (musicPip()) {
            scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
            val ww = width.toFloat(); val hh = height.toFloat()
            val d = resources.displayMetrics.density
            val rw = mRect[2] * d; val rh = mRect[3] * d
            if (rw > 10f && rh > 10f && ww > 0f && hh > 0f) {
                val sc = minOf(ww / rw, hh / rh)
                web.pivotX = 0f; web.pivotY = 0f
                web.scaleX = sc; web.scaleY = sc
                web.translationX = (ww - rw * sc) / 2f - web.left - mRect[0] * d * sc
                web.translationY = (hh - rh * sc) / 2f - web.top - mRect[1] * d * sc
            }
            return
        }
        translationX = 0f; translationY = 0f
        val sc = pw.toFloat() / baseW
        pivotX = 0f
        pivotY = 0f
        scaleX = sc
        scaleY = sc
    }

    private fun applyLayout() {
        val lp = (layoutParams as? LayoutParams) ?: return
        val dm = resources.displayMetrics
        // PiP-ből kilépve a rendszer még a kicsi ablak méretét adja vissza: ilyenkor nem rögzítünk méretet
        val settling = android.os.SystemClock.elapsedRealtime() - pipExitAt < 1500
        // a méretet minden "rendes" állapotban kövessük (álló, fekvő, ablakos) – PiP/teljes képernyő alatt nem
        if (!pip && !fs && !settling && !mini && !embedded) { baseW = dm.widthPixels; baseH = dm.heightPixels }
        val bw = if (baseW > 0) baseW else dm.widthPixels
        val bh = if (baseH > 0) baseH else dm.heightPixels
        val smallMini = mini && !pip
        (parent as? View)?.removeOnLayoutChangeListener(pipLayoutListener)
        if (embedded && pip) {
            // Shorts PiP: a lap marad teljes méretű (nem számolódik újra), csak lekicsinyítjük
            lp.width = bw
            lp.height = bh
            lp.gravity = Gravity.TOP or Gravity.START
            lp.setMargins(0, 0, 0, 0)
            pivotX = 0f
            pivotY = 0f
            (parent as? View)?.addOnLayoutChangeListener(pipLayoutListener)
        } else if (embedded) {
            // Shorts: teljes kép (az alsó menüsor ilyenkor rejtve van)
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.NO_GRAVITY
            lp.setMargins(0, 0, 0, 0)
            scaleX = 1f; scaleY = 1f
        } else if (pip && web.url?.contains("music.youtube.com") == true) {
            // VidaraMusic PiP: a réteg kitölti az egész PiP ablakot (fekete), benne a teljes méretű lapot
            // nagyítjuk rá a lejátszóra – így körülötte semmi más (logó, gombok) nem látszik
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.NO_GRAVITY
            lp.setMargins(0, 0, 0, 0)
            scaleX = 1f; scaleY = 1f; translationX = 0f; translationY = 0f
            if (normalBg == null) normalBg = (background as? android.graphics.drawable.ColorDrawable)?.color
            setBackgroundColor(Color.BLACK)
            (parent as? View)?.addOnLayoutChangeListener(pipLayoutListener)
        } else if (pip) {
            // a lap ugyanakkora (16:9, teljes szélesség) marad, csak kicsinyítjük – így nem számolódik újra az elrendezés
            lp.width = bw
            lp.height = (bw * 9f / 16f).toInt()
            lp.gravity = Gravity.TOP or Gravity.START
            lp.setMargins(0, 0, 0, 0)
            (parent as? View)?.addOnLayoutChangeListener(pipLayoutListener)
        } else if (smallMini) {
            val sc = 0.56f
            lp.width = bw
            lp.height = (bw * 9f / 16f).toInt()
            lp.gravity = Gravity.BOTTOM or Gravity.END
            lp.setMargins(0, 0, dp(10), dp(64))
            pivotX = bw.toFloat()
            pivotY = lp.height.toFloat()
            scaleX = sc; scaleY = sc
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ViewGroup.LayoutParams.MATCH_PARENT
            lp.gravity = Gravity.NO_GRAVITY
            lp.setMargins(0, 0, 0, bottomGapPx)   // az app alsó menüje látható marad
            scaleX = 1f; scaleY = 1f
        }
        if (!pip) normalBg?.let { setBackgroundColor(it); normalBg = null }
        layoutParams = lp
        fitThumb(false)   // a takaró bélyegképe visszakapja a 16:9-es keretét
        val compact = mini || pip || embedded || fs
        bar.visibility = if (compact) View.GONE else View.VISIBLE
        twoPane()
        syncMusicExit()
        if (compact && ::searchRow.isInitialized && searchRow.visibility == View.VISIBLE) closeSearch()
        val wl = web.layoutParams as LayoutParams
        val musicArtPip = pip && !embedded && web.url?.contains("music.youtube.com") == true && !musicHasVideo
        if (musicArtPip) {
            // VidaraMusic PiP csak borítóval: a lapot NEM méretezzük át (különben a VidaraMusic újrarendezi magát)
            if (pipWebW <= 0 && web.width in 1 until web.height && web.width >= bw * 0.9f) { pipWebW = web.width; pipWebH = web.height }
            wl.width = if (pipWebW > 0) pipWebW else bw
            wl.height = if (pipWebH > 0) pipWebH else bh - wl.topMargin
            wl.gravity = Gravity.TOP or Gravity.START
            wl.bottomMargin = 0
        } else if (mini || pip) {
            // a lap teljes méretű marad (nem számolódik újra), a kis ablak csak a tetejét (a lejátszót) mutatja
            wl.width = bw
            wl.height = bh
            wl.gravity = Gravity.TOP or Gravity.START
            wl.topMargin = 0
            wl.bottomMargin = 0
        } else {
            wl.width = ViewGroup.LayoutParams.MATCH_PARENT
            wl.height = ViewGroup.LayoutParams.MATCH_PARENT
            wl.gravity = Gravity.NO_GRAVITY
            wl.topMargin = if (compact) 0 else dp(36)
            wl.bottomMargin = (-web.translationY).toInt()
        }
        web.layoutParams = wl
        sizeMusicWeb()
        val cl = cover.layoutParams as LayoutParams
        cl.topMargin = if (compact) 0 else dp(36)
        cover.layoutParams = cl
        spinner.visibility = View.GONE
        miniControls.visibility = if (smallMini) View.VISIBLE else View.GONE
        embedBack.visibility = if (embedded && !pip) View.VISIBLE else View.GONE
        // a gombok a kicsinyítés ellenére normál méretűek maradnak
        miniControls.pivotX = dp(76).toFloat()
        miniControls.pivotY = 0f
        miniControls.scaleX = if (smallMini) 1f / 0.56f else 1f
        miniControls.scaleY = if (smallMini) 1f / 0.56f else 1f
        syncFsTap()
        setCtl(fs)
        if (pip) post { fitPip() }
        // ne maradjon fent takaró: kilépés után az minden koppintást elnyelt ("lefagyás")
        hideCover()
        pushMini()
        updatePipArt()
    }

    /** VidaraMusic + PiP/kis ablak: a dal borítóképe tölti ki az ablakot. */
    private fun updatePipArt() {
        if (!::pipArt.isInitialized) return
        val u = web.url ?: ""
        val on = if (pip) (!embedded && u.contains("music.youtube.com") && mRect[2] < 10f)   // PiP: a lejátszóra nagyítunk; borító csak ha nem látszik
                 else (mini && !embedded && u.contains("music.youtube.com") && !musicHasVideo)
        if (!on) { if (pipArt.visibility != View.GONE) pipArt.visibility = View.GONE; return }
        val id = runCatching { Uri.parse(u).getQueryParameter("v") }.getOrNull()
        if (id.isNullOrEmpty()) { pipArt.visibility = View.GONE; return }
        // a dal saját (négyzetes) borítója, teljes egészében, fekete háttéren – nem levágott videó-bélyegkép
        val art = if (pipArtUrl.startsWith("http")) pipArtUrl else "https://i.ytimg.com/vi/$id/hqdefault.jpg"
        pipArt.scaleType = if (pipArtUrl.startsWith("http")) ImageView.ScaleType.FIT_CENTER else ImageView.ScaleType.CENTER_CROP
        if (art != pipArtId) { pipArtId = art; Images.load(pipArt, art) }
        // PiP-ben a (teljes méretű) lapnak csak a felső 16:9-es része látszik: a borító ott legyen
        val al = pipArt.layoutParams as LayoutParams
        val want = ViewGroup.LayoutParams.MATCH_PARENT
        if (al.height != want || al.gravity != Gravity.TOP) { al.height = want; al.gravity = Gravity.TOP; pipArt.layoutParams = al }
        pipArt.visibility = View.VISIBLE
        pipArt.bringToFront()
        if (mini && !pip) miniControls.bringToFront()
    }

    /** Teljes képernyőre váltás (és vissza) alatt a videó képe töltse ki a kijelzőt: ne látszódjon
     *  a köztes, még nem odavaló méret. A kép a váltás végén (elforgatás után) tűnik el. */
    private fun showFsCover() {
        if (embedded || mini || pip) return
        val id = runCatching { Uri.parse(web.url ?: "").getQueryParameter("v") }.getOrNull()
        fitThumb(true)
        val cl = cover.layoutParams as LayoutParams
        cl.topMargin = 0
        cl.height = ViewGroup.LayoutParams.MATCH_PARENT
        cover.layoutParams = cl
        thumb.scaleType = ImageView.ScaleType.CENTER_CROP
        if (id != null) Images.load(thumb, "https://i.ytimg.com/vi/$id/hq720.jpg", "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        cover.visibility = View.VISIBLE
        cover.alpha = 1f
        spinner.visibility = View.GONE
        markCoverClickable(false)
        postDelayed({ hideCover(true) }, 900)   // vésztartalék, ha nem jön tájolás-jelzés (pl. tablet)
    }

    private fun setFs(on: Boolean) {
        if (embedded) { js("window.__vdExitFs&&window.__vdExitFs()"); return }
        if (fs == on) return
        showFsCover()   // a váltás alatt a videó képe töltse ki a kijelzőt
        fs = on
        fsSince = android.os.SystemClock.elapsedRealtime()
        WebTube.log("fullscreen " + on)
        act.requestedOrientation = if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        val c = WindowCompat.getInsetsController(act.window, act.window.decorView)
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        if (on) c.hide(WindowInsetsCompat.Type.systemBars()) else c.show(WindowInsetsCompat.Type.systemBars())
        applyLayout()
        setCtl(on)   // a kilépő ⌄ azonnal látszik teljes képernyőn
        // a váltás (elforgatás) alatt a videó képe maradjon látszó, ne a szürke YouTube-felület
        if (isOpen && !mini && !pip) {
            // SOHA nem teszünk állóképet a lejátszóra: a videó folyamatosan megy tovább
            postDelayed({ hideCover(true) }, 40)
            postDelayed({ resume(); js("window.__vdAuto&&window.__vdAuto()") }, 200)
            postDelayed({ js("window.__vdAuto&&window.__vdAuto()") }, 600)
            postDelayed({ hideCover(true); js("window.__vdAuto&&window.__vdAuto()") }, 1200)
            postDelayed({ hideCover(true); js("window.__vdAuto&&window.__vdAuto()") }, 2200)
        }
    }

    private fun exitFullscreen() {
        if (!fs) return
        js("window.__vdExitFs&&window.__vdExitFs()")
        setFs(false)
    }

    private val hideRunnable = Runnable { hideCover() }

    private fun markCoverClickable(on: Boolean) { cover.isClickable = on }

    private fun showCover(url: String) {
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        val shortId = parsed?.pathSegments?.let { if (it.size >= 2 && it[0] == "shorts") it[1] else null }
        val id = runCatching { parsed?.getQueryParameter("v") }.getOrNull() ?: shortId
        val ccl = cover.layoutParams as LayoutParams
        ccl.topMargin = if (embedded || mini || pip || fs) 0 else dp(36)
        cover.layoutParams = ccl
        spinner.visibility = View.GONE
        val sl = spinner.layoutParams as LayoutParams
        if (id != null) {
            thumb.visibility = View.VISIBLE
            val tl = thumb.layoutParams as LayoutParams
            tl.height = if (shortId != null) ViewGroup.LayoutParams.MATCH_PARENT else videoH()
            tl.gravity = Gravity.TOP
            thumb.layoutParams = tl
            thumb.scaleType = if (shortId != null) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
            // 16:9 előnézet: pontosan akkora, mint a lejátszó képe (nincs nagyítás/levágás, nincs ugrás)
            if (shortId != null) Images.load(thumb, "https://i.ytimg.com/vi/$id/oar2.jpg")
            else Images.load(thumb, "https://i.ytimg.com/vi/$id/hq720.jpg", "https://i.ytimg.com/vi/$id/hqdefault.jpg")
            sl.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            sl.topMargin = (resources.displayMetrics.widthPixels * 9f / 16f).toInt() / 2 - dp(20)
        } else {
            thumb.setImageDrawable(null)
            thumb.visibility = View.GONE
            sl.gravity = Gravity.CENTER
            sl.topMargin = 0
        }
        spinner.layoutParams = sl
        // videóoldal: csak a lejátszó helyét takarjuk, alatta a lista/kommentek azonnal megjelenhetnek
        val watchOnly = shortId == null && id != null && !embedded && !mini && !pip && !fs
        if (watchOnly) ccl.height = videoH() else ccl.height = ViewGroup.LayoutParams.MATCH_PARENT
        cover.layoutParams = ccl
        cover.visibility = View.VISIBLE
        markCoverClickable(true)
        web.alpha = 1f   // a lap rajzolódik a takaró alatt (gyorsabb), a szürke vázat a lapon belüli takaró fedi
        t0 = android.os.SystemClock.elapsedRealtime()
        removeCallbacks(hideRunnable)
        // FONTOS: csak akkor tűnik el, ha a videó tényleg elindult (revealVideo).
        // A 12 mp csak vésztartalék, ha a lejátszó nem indul be egyáltalán.
        postDelayed({ hideCover(true) }, 12000)
    }

    /** Shorts-váltás: fekete takaró, amíg az új videónak nincs képe (a szürke YouTube-háttér soha nem látszik). */
    private fun reelCover() {
        if (embedded) {
            val cl = cover.layoutParams as LayoutParams
            cl.topMargin = 0
            cover.layoutParams = cl
        }
        thumb.setImageDrawable(null)
        thumb.visibility = View.GONE
        spinner.visibility = View.GONE   // lapozásnál nem villog pörgő ikon
        cover.visibility = View.VISIBLE
        markCoverClickable(true)
        t0 = android.os.SystemClock.elapsedRealtime()
        removeCallbacks(hideRunnable)
        postDelayed({ hideCover(true) }, 6000)   // biztonsági: ne maradjon végleg takarásban
    }

    /** Kis ablakból / PiP-ből visszanagyításkor a YouTube szürke vázát fekete takaró fedi, amíg a lejátszó a helyére nem ül. */
    private fun blackCover() {
        if (embedded || !isOpen) return
        val cl = cover.layoutParams as LayoutParams
        cl.topMargin = dp(36)
        cover.layoutParams = cl
        thumb.visibility = View.GONE
        spinner.visibility = View.GONE
        val sl = spinner.layoutParams as LayoutParams
        sl.gravity = Gravity.CENTER
        sl.topMargin = 0
        spinner.layoutParams = sl
        cover.visibility = View.VISIBLE
        js("window.__vdRdy='';window.__vdPS='';window.__vdStab=0;window.__vdPL='';")
        removeCallbacks(hideRunnable)
        postDelayed({ hideCover(true) }, 7000)
    }

    private fun revealVideo() {
        WebTube.log((if (embedded) "S" else "V") + " playing után " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms")
        web.alpha = 1f
        if (embedded || mini || pip || cover.layoutParams.height != ViewGroup.LayoutParams.MATCH_PARENT) { hideCover(true); return }
        thumb.visibility = View.GONE
        spinner.visibility = View.GONE
        val cl = cover.layoutParams as LayoutParams
        cl.topMargin = dp(36) + (if (baseW > 0) baseW else resources.displayMetrics.widthPixels) * 9 / 16
        cover.layoutParams = cl
        // a takaró itt már csak a videó alatti listát fedi: NE nyelje el az érintést (ez okozott "lefagyást")
        markCoverClickable(false)
        // a videó már megy: a takaró azonnal eltűnhet (ne maradjon fekete sáv az ajánlók felett)
        removeCallbacks(hideRunnable)
        postDelayed({ hideCover(true) }, 300)
    }

    /** Minőségváltás: a lejátszó helyére a videó saját képe kerül (nincs szürke YouTube-háttér, nincs pörgő kör). */
    private fun videoH() = (if (baseW > 0) baseW else resources.displayMetrics.widthPixels) * 9 / 16

    /**
     * Kétpaneles nézet nagy kijelzőn (tablet, hajlított telefon belső kijelzője): a lejátszó balra,
     * az ajánlott videók jobbra. Az elrendezést a befecskendezett CSS adja; a lejátszó belső méretét
     * a YouTube csak inline stílusból veszi át, ezért azt itt állítjuk.
     */
    private fun twoPane() {
        if (pip || fs) return   // PiP/teljes képernyő alatt nem nyúlunk az elrendezéshez
        val dm = resources.displayMetrics
        val cssW = (if (baseW > 0) baseW else dm.widthPixels).toFloat() / dm.density
        if (cssW < 600f) {
            js("(function(){try{document.documentElement.classList.remove('vd-2p','vd-wide');" +
               "var ids=['player-container-id','player','movie_player'];" +
               "for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);if(e){e.style.removeProperty('width');e.style.removeProperty('height');e.style.removeProperty('min-height')}}" +
               "var c=document.querySelector('.html5-video-container'),v=document.querySelector('video.video-stream');" +
               "[c,v].forEach(function(e){if(!e)return;e.style.removeProperty('width');e.style.removeProperty('height');e.style.removeProperty('top');e.style.removeProperty('left');e.style.removeProperty('max-height')})}catch(e){}}})()")
            return
        }
        web.evaluateJavascript("(function(){try{" +
           "var isYt=location.hostname==='m.youtube.com';" +
           "document.documentElement.classList.toggle('vd-wide',isYt&&innerWidth>=600);" +
           "if(!isYt||innerWidth<820||innerWidth>1099||!document.querySelector('ytm-watch')){document.documentElement.classList.remove('vd-2p');return 'no-page'}" +
           "document.documentElement.classList.add('vd-2p');" +
           "var w=Math.round((innerWidth-34)*0.6),h=Math.round(w*9/16);" +
           "var ids=['player-container-id','player','movie_player'];" +
           "for(var i=0;i<ids.length;i++){var e=document.getElementById(ids[i]);if(e){e.style.setProperty('width',w+'px','important');e.style.setProperty('height',h+'px','important');e.style.setProperty('min-height','0','important')}}" +
           "var c=document.querySelector('.html5-video-container'),v=document.querySelector('video.video-stream');" +
           "[c,v].forEach(function(e){if(!e)return;e.style.setProperty('width',w+'px','important');e.style.setProperty('height',h+'px','important');e.style.setProperty('top','0','important');e.style.setProperty('left','0','important');e.style.setProperty('max-height','none','important')});" +
           "return 'ok '+w+'x'+h" +
           "}catch(e){return 'err '+e.message}})()") { _ -> }
    }

    /** A takaró bélyegképe: teljes képernyőn a TELJES kijelzőt kitölti, egyébként a lejátszó 16:9-es keretét. */
    private fun fitThumb(full: Boolean) {
        val lp = thumb.layoutParams as LayoutParams
        val h = if (full) ViewGroup.LayoutParams.MATCH_PARENT else videoH()
        if (lp.height == h && lp.gravity == Gravity.TOP) return
        lp.width = ViewGroup.LayoutParams.MATCH_PARENT
        lp.height = h
        lp.gravity = Gravity.TOP
        thumb.layoutParams = lp
        thumb.scaleType = if (full) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
    }

    /** Minőségváltás: a lejátszó helyére a videó saját képe kerül (nincs szürke YouTube-háttér, nincs pörgő kör). */
    private fun qualitySwitchCover() {
        if (embedded || mini || pip) return
        val id = runCatching { Uri.parse(web.url ?: "").getQueryParameter("v") }.getOrNull() ?: return
        val cl = cover.layoutParams as LayoutParams
        if (fs) {
            // teljes képernyőn a kép a TELJES kijelzőt kitakarja (nem csak a felső sávot)
            cl.topMargin = 0
            cl.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            cl.topMargin = dp(36)
            cl.height = dp(36) + videoH()
        }
        cover.layoutParams = cl
        fitThumb(fs)
        thumb.visibility = View.VISIBLE
        Images.load(thumb, "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        spinner.visibility = View.GONE
        cover.visibility = View.VISIBLE
        markCoverClickable(false)   // a koppintás menjen át a lejátszóhoz
        restoreUntil = android.os.SystemClock.elapsedRealtime() + 4000
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, 4200)   // biztonsági
        postDelayed({ watchForFrames(0) }, 600)   // ha zökkenőmentes a váltás, hamar eltűnik
    }

    /** Visszatérés PiP-ből / kis ablakból: a videó képe takarja a lejátszót, amíg a YouTube újra nem rajzol. */
    private fun restoreCover() {
        if (embedded || !isOpen) return
        val id = runCatching { Uri.parse(web.url ?: "").getQueryParameter("v") }.getOrNull()
        val cl = cover.layoutParams as LayoutParams
        val h = videoH()
        if (fs) {
            cl.topMargin = 0
            cl.height = ViewGroup.LayoutParams.MATCH_PARENT
        } else {
            cl.topMargin = dp(36)
            cl.height = dp(36) + h
        }
        cover.layoutParams = cl
        fitThumb(fs)
        if (id != null) {
            thumb.visibility = View.VISIBLE
            Images.load(thumb, "https://i.ytimg.com/vi/$id/hqdefault.jpg")
        } else {
            thumb.setImageDrawable(null)
            thumb.visibility = View.GONE
        }
        val sl = spinner.layoutParams as LayoutParams
        sl.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        sl.topMargin = (if (fs) resources.displayMetrics.heightPixels else h) / 2 - dp(20)
        spinner.layoutParams = sl
        spinner.visibility = View.GONE
        cover.visibility = View.VISIBLE
        markCoverClickable(false)
        restoreUntil = android.os.SystemClock.elapsedRealtime() + 1800
        removeCallbacks(hideRunnable)
        postDelayed(hideRunnable, 2600)   // biztonsági: ne maradjon fent
        postDelayed({ watchForFrames(0) }, 400)
    }

    /// Minőségváltás / visszatérés után: amint újra van kép, a takaró azonnal eltűnik.
    private fun watchForFrames(attempt: Int) {
        if (cover.visibility != View.VISIBLE || attempt > 16) { hideCover(true); return }
        web.evaluateJavascript(
            "(function(){try{var v=document.querySelector('video');return (v&&v.readyState>=3&&!v.paused&&v.currentTime>0.1)?'1':'0';}catch(e){return '0';}})()"
        ) { r ->
            // két egymást követő mérés kell: az első még a régi (átméretezés előtti) képet látná
            if (r?.contains("1") == true && attempt >= 2) hideCover(true)
            else postDelayed({ watchForFrames(attempt + 1) }, 200)
        }
    }

    private fun swipeUp() {
        val t0 = android.os.SystemClock.uptimeMillis()
        val x = web.width / 2f
        val y0 = web.height * 0.75f
        val y1 = web.height * 0.25f
        fun ev(action: Int, y: Float, dt: Long) = MotionEvent.obtain(t0, t0 + dt, action, x, y, 0)
        web.dispatchTouchEvent(ev(MotionEvent.ACTION_DOWN, y0, 0))
        for (i in 1..6) postDelayed({ web.dispatchTouchEvent(ev(MotionEvent.ACTION_MOVE, y0 + (y1 - y0) * i / 6f, 25L * i)) }, 25L * i)
        postDelayed({ web.dispatchTouchEvent(ev(MotionEvent.ACTION_UP, y1, 170)) }, 170)
    }

    /** Ha a YouTube már be van töltve, oldalon belül lépünk (gyors); különben teljes betöltés. */
    private fun navigate(url: String) {
        val host = runCatching { Uri.parse(url).host }.getOrNull()
        if (!embedded && loadedHost != null && loadedHost == host && web.url?.startsWith("http") == true) {
            WebTube.log((if (embedded) "S" else "V") + " oldalon belüli váltás: " + url)
            js("window.__vdGo&&window.__vdGo(" + org.json.JSONObject.quote(url) + ")")
        } else {
            WebTube.log((if (embedded) "S" else "V") + " teljes betöltés: " + url)
            web.loadUrl(url)
        }
    }

    private fun hideCover(force: Boolean = false) {
        // PiP-ből / teljes képernyőből visszatérve a takaró addig marad, amíg a kép tényleg megvan
        // (különben a YouTube szürke váza villan fel). Koppintásra viszont mindig eltűnik.
        markCoverClickable(false)   // soha ne nyelje el az érintést, akkor sem, ha még fent marad
        if (!force && android.os.SystemClock.elapsedRealtime() < restoreUntil) return
        restoreUntil = 0
        fitThumb(false)
        // a takaró visszakapja a teljes méretét (a minőségváltásnál csak a lejátszó magasságát vesszük fel)
        val hl = cover.layoutParams as LayoutParams
        if (hl.height != ViewGroup.LayoutParams.MATCH_PARENT) {
            hl.height = ViewGroup.LayoutParams.MATCH_PARENT
            cover.layoutParams = hl
        }
        web.alpha = 1f
        reelPosterId = ""
        if (cover.visibility == View.VISIBLE) WebTube.log((if (embedded) "S" else "V") + " takaró eltűnt " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms")
        removeCallbacks(hideRunnable)
        cover.visibility = View.GONE
    }

    // ------------------------------------------------------------------ műveletek

    fun open(url: String) {
        music = url.startsWith(WebTube.MUSIC)
        mini = false
        pip = false
        clearPending = true
        pendingUrl = url
        visibility = View.VISIBLE
        applyLayout()
        if (!url.contains("music.youtube.com")) showCover(url)   // VidaraMusic: nincs fekete takaró
        navigate(url)
        if (!embedded) act.startService(Intent(act, KeepAliveService::class.java))
        onState?.invoke()
    }

    /** Shorts-fül: a lejátszó az alsó menü fölött, külön sáv és kis ablak nélkül. */
    fun openEmbedded(url: String) {
        music = false
        mini = false
        pip = false
        val loaded = loadedHost != null && web.url?.startsWith("https://m.youtube.com/shorts") == true
        visibility = View.VISIBLE
        applyLayout()
        act.startService(Intent(act, KeepAliveService::class.java))
        if (loaded) {
            // már be van töltve: nem töltjük újra (ez volt az 5-8 mp), hanem azonnal folytatjuk
            WebTube.log("S shorts: oldal már betöltve, azonnali indítás")
            pendingUrl = url
            js("window.__vdActive&&window.__vdActive()")
            postDelayed({ hideCover(true) }, 60)
            onState?.invoke()
            return
        }
        clearPending = true
        pendingUrl = url
        reloaded = false
        showCover(url)
        navigate(url)
        onState?.invoke()
    }

    fun closeEmbedded() {
        if (isOpen) close()
    }

    /** Lehúzás / ⌄: kis lebegő ablak, a videó közben megy tovább. */
    fun minimize() {
        if (!isOpen) return
        if (fs) exitFullscreen()
        mini = true
        miniPlay.setImageResource(R.drawable.ic_pause)
        applyLayout()
        onState?.invoke()
    }

    fun expand() {
        mini = false
        applyLayout()
        // nincs állókép: a videó folyamatosan megy tovább
        hideCover(true)
        js("window.__vdPlayNow&&window.__vdPlayNow();window.__vdAuto&&window.__vdAuto();" +
            "setTimeout(function(){window.__vdFixTabs&&window.__vdFixTabs();window.__vdIcons&&window.__vdIcons();},80)")
        onState?.invoke()
    }

        /** PiP-be lépés ELŐTT: a lap rögzített álló méretet kap, mielőtt az ablak összemegy. */
        fun prePip() {
            if (pip || embedded || web.url?.contains("music.youtube.com") != true) return
            // a lap PONTOSAN a mostani méretén marad (így a VidaraMusic nem rendezi át magát)
            if (web.width <= 0 || web.height <= 0 || web.width > web.height) return
            pipWebW = web.width; pipWebH = web.height
            val wl = web.layoutParams as? LayoutParams ?: return
            wl.width = pipWebW; wl.height = pipWebH; wl.gravity = Gravity.TOP or Gravity.START; wl.bottomMargin = 0
            web.layoutParams = wl
        }

        fun setPip(on: Boolean) {
        WebTube.log("DIAG setPip $on me=${width}x${height} web=${web.width}x${web.height} norm=${normW}x$normH")
        if (on && !pip) js("window.__vdDiag&&window.__vdDiag('PRE')")
        if (on) listOf(800L, 2500L).forEach { d -> postDelayed({ WebTube.log("DIAG pip+$d web=${web.width}x${web.height} sc=${web.scaleX} t=${web.translationX},${web.translationY} me=${width}x${height}"); js("window.__vdDiag&&window.__vdDiag('PIP+$d')") }, d) }
        else listOf(700L, 2500L, 5000L).forEach { d -> postDelayed({ diagViews("EXIT+$d"); WebTube.log("DIAG exit+$d web=${web.width}x${web.height} sc=${web.scaleX} t=${web.translationX},${web.translationY}"); js("window.__vdDiag&&window.__vdDiag('EXIT+$d')") }, d) }
        pip = on
        if (!on) {
            // Visszatérés PiP-ből: a kicsinyítés/nagyítás visszaállítása (ne maradjon sarokban)
            pipExitAt = android.os.SystemClock.elapsedRealtime()
            scaleX = 1f
            scaleY = 1f
            pivotX = 0f
            pivotY = 0f
            translationX = 0f
            translationY = 0f
            mRect = floatArrayOf(0f, 0f, 0f, 0f)
            pipWebW = 0; pipWebH = 0
            web.scaleX = 1f; web.scaleY = 1f; web.translationX = 0f; web.translationY = 0f
        }
        applyLayout()
        if (!on) {
            // Visszatérés a rendes ablakra: a videó képe takarja a lejátszót, amíg a YouTube újra
            // nem rajzol (különben a szürke váz villan fel), és induljon is el a videó.
            hideCover(true)
            resume()
            postDelayed({
                applyLayout()   // a végleges (nagy) méretre állítjuk, ha közben megjött
                resume()
                hideCover(true)
                js("window.__vdForce=true;try{window.dispatchEvent(new Event('resize'));}catch(e){}")
            }, 500)
            postDelayed({ if (!pip) { applyLayout(); sizeMusicWeb() } }, 1700)
        }
    }

    fun close() {
        WebTube.log("close() url=" + web.url)
        if (fs) exitFullscreen()
        if (embedded) {
            // A Shorts-oldal betöltve MARAD (a következő belépés azonnal indul), csak a hangot állítjuk le.
            js("window.__vdIdle&&window.__vdIdle()")
            removeCallbacks(hideRunnable)
            cover.visibility = View.GONE
            web.alpha = 1f
            mini = false
            pip = false
            applyLayout()
            visibility = View.GONE
            pendingUrl = ""
            act.stopService(Intent(act, KeepAliveService::class.java))
            onState?.invoke()
            return
        }
        if (loadedHost != null && !embedded) {
            // leállítjuk a lejátszást, és a kezdőlapra lépünk (az oldal betöltve marad: a következő videó gyorsabb)
            js("window.__vdBg=false;document.querySelectorAll('video').forEach(function(v){try{v.pause();}catch(e){}});window.__vdGo&&window.__vdGo('https://'+location.host+'/')")
        } else {
            web.loadUrl("about:blank")
            web.clearHistory()
            loadedHost = null
        }
        hideCover()
        mini = false
        pip = false
        applyLayout()
        visibility = View.GONE
        if (!embedded) act.stopService(Intent(act, KeepAliveService::class.java))
        onState?.invoke()
    }

    /** A lap VidaraMusic-e (a „music” jelző akkor is lehet hamis, ha mentett URL-lel töltődött be). */
    private val inMusic: Boolean
        get() = music || web.url?.contains("music.youtube.com") == true

    /** A „← Vidara” gomb csak a VidaraMusic lapon látszik. */
    private fun syncMusicExit() {
        if (::brand.isInitialized) brand.text = if (inMusic) "VidaraMusic" else "Vidara"
        if (!::musicExit.isInitialized) return
        musicExit.visibility = if (inMusic && bar.visibility == View.VISIBLE) View.VISIBLE else View.GONE
    }

    /** VidaraMusic: kilépés a YouTube Music lapról, vissza a Vidara főoldalra (ugyanabban a webes nézetben). */
    private fun exitToVidara() {
        if (!inMusic) return
        WebTube.log("VidaraMusic -> Vidara (saját gomb)")
        // a zene azonnal álljon le
        js("window.__vdBg=false;document.querySelectorAll('video,audio').forEach(function(v){try{v.pause();}catch(e){}})")
        hideCover(true)
        music = false
        clearPending = true
        pendingUrl = WebTube.YT
        // NEM zárjuk be a réteget (alatta a régi, füles natív nézet jönne elő):
        // ugyanebben a nézetben lépünk vissza a YouTube-főoldalra, ami a Vidara kezdőképernyője.
        navigate(WebTube.YT)
        syncMusicExit()
        onState?.invoke()
    }

    /** Vissza gomb: teljes képernyőből ki, egyébként oldalon belül vissza; a YouTube-felület MARAD. */
    fun back() {
        when {
            webChooser -> closeChooser()
            isSearching -> closeSearch()
            fs -> exitFullscreen()
            // VidaraMusic: az előzmény végén a vissza gesztus is a Vidara nézetbe visz
            inMusic && !web.canGoBack() -> exitToVidara()
            web.canGoBack() -> web.goBack()
            else -> act.moveTaskToBack(true)   // nincs előzmény: háttérbe, nem váltunk a régi nézetre
        }
    }

    private fun raiseBar() {
        bar.bringToFront()
        if (::searchRow.isInitialized && searchRow.visibility == View.VISIBLE) searchRow.bringToFront()
    }

    val isSearching get() = ::searchRow.isInitialized && searchRow.visibility == View.VISIBLE

    /** Kereső (nagyító): azonnal előjön a saját keresősor, a billentyűzettel együtt – egy koppintásra. */
    fun search() {
        if (!isOpen) {
            visibility = View.VISIBLE
            applyLayout()
            if (web.url == null || web.url == "about:blank") navigate(if (music) WebTube.MUSIC else WebTube.YT)
            onState?.invoke()
        }
        hideCover(true)
        searchRow.visibility = View.VISIBLE
        searchRow.bringToFront()
        searchField.setText("")
        showHistory("")
        searchField.requestFocus()
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT)
        // ha az első kérést a rendszer elnyelné (még nem volt fókusz), rövid késéssel újra
        searchField.postDelayed({ if (isSearching) { searchField.requestFocus(); imm.showSoftInput(searchField, InputMethodManager.SHOW_IMPLICIT) } }, 120)
    }

    fun closeSearch() {
        if (!::searchRow.isInitialized) return
        val imm = act.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        imm.hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
        searchRow.visibility = View.GONE
        if (::historyPanel.isInitialized) historyPanel.visibility = View.GONE
    }

    // ---------------------------------------------------- keresési előzmények (eszközönként)
    private fun prefs() = act.getSharedPreferences("vidara_glass", Context.MODE_PRIVATE)

    /** Külön előzmény-lista: a Vidara (m.youtube.com) és a VidaraMusic (music.youtube.com) sajátot kap. */
    private fun historyKey(music: Boolean) = if (music) "search_history_music" else "search_history"

    private fun historyList(music: Boolean = inMusic): MutableList<String> {
        val raw = runCatching { prefs().getString(historyKey(music), null) }.getOrNull() ?: return mutableListOf()
        return runCatching {
            val arr = org.json.JSONArray(raw)
            (0 until arr.length()).map { arr.optString(it) }.filter { it.isNotBlank() }.toMutableList()
        }.getOrDefault(mutableListOf())
    }

    /** Elmenti a keresést az ESZKÖZRE (helyi SharedPreferences) – más telefonon nem látszik. */
    private fun addHistory(q: String, music: Boolean = inMusic) {
        val t = q.trim().replace('\n', ' ')
        if (t.isEmpty()) return
        val list = historyList(music)
        list.removeAll { it.equals(t, ignoreCase = true) }
        list.add(0, t)
        while (list.size > 30) list.removeAt(list.size - 1)
        runCatching { prefs().edit().putString(historyKey(music), org.json.JSONArray(list).toString()).apply() }
    }

    private fun clearHistory(music: Boolean = inMusic) {
        runCatching { prefs().edit().remove(historyKey(music)).apply() }
    }

    /** A keresősáv alatt a korábbi keresések (gépelés közben szűrve). */
    private fun showHistory(filter: String) {
        if (!::historyPanel.isInitialized || !isSearching) return
        val f = filter.trim()
        val items = historyList(inMusic).filter { f.isEmpty() || it.contains(f, ignoreCase = true) }.take(8)
        if (items.size == 1 && items[0].equals(f, ignoreCase = true)) {
            historyPanel.visibility = View.GONE   // pontosan ez a szó: nem kell alá a lista
            return
        }
        historyPanel.removeAllViews()
        if (items.isEmpty()) {
            historyPanel.visibility = View.GONE
            return
        }
        items.forEach { q ->
            historyPanel.addView(TextView(act).apply {
                text = q
                setTextColor(Color.WHITE)
                textSize = 15f
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                setPadding(dp(18), dp(13), dp(18), dp(13))
                isClickable = true
                setOnClickListener {
                    searchField.setText(q)
                    searchField.setSelection(q.length)
                    runSearch()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        historyPanel.addView(TextView(act).apply {
            text = act.getString(R.string.search_history_clear)
            setTextColor(0xFFC4A5FF.toInt())
            textSize = 14f
            setPadding(dp(18), dp(12), dp(18), dp(16))
            isClickable = true
            setOnClickListener {
                clearHistory(inMusic)
                historyPanel.removeAllViews()
                historyPanel.visibility = View.GONE
            }
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        historyPanel.visibility = View.VISIBLE
        historyPanel.bringToFront()
    }

    private fun runSearch() {
        val q = searchField.text.toString().trim()
        if (q.isEmpty()) return
        addHistory(q)
        closeSearch()
        // a lap URL-je dönt (a zenei jelző elavulhat: pl. VidaraMusicból visszalépve)
        val url = if (inMusic) WebTube.MUSIC + "search?q=" + Uri.encode(q)
                  else WebTube.YT + "results?search_query=" + Uri.encode(q)
        // nincs fekete takaró (az nem videóoldalon akár 7 mp-ig is fent maradt, és elnyelte a koppintásokat)
        hideCover(true)
        pendingUrl = url
        setShift(0f)
        navigate(url)
        web.requestFocus()
    }

    /** Ha egy videó állva marad, elindítjuk (nincs rejtett "lejátszás" gomb, ami miatt fagyni látszana). */
    fun autoPlay() {
        js("window.__vdAuto&&window.__vdAuto()")
    }

    /** Az app indulásakor előmelegítjük a YouTube-ot (DNS, TLS, szkriptek) – az első videó gyorsabban indul. */
    fun warmUp(url: String = WebTube.YT) {
        if (!isOpen && web.url == null) web.loadUrl(url)
    }

    fun destroyWeb() {
        if (!embedded) act.stopService(Intent(act, KeepAliveService::class.java))
        removeAllViews()
        web.destroy()
    }

    // ------------------------------------------------------------------ érintés

    private var downX = 0f
    private var downY = 0f
    private var dragging = false

    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        // Ha bármi miatt fent maradt a betöltő takaró, az első koppintás levegye (ne nyelje el az érintést)
        // csak ha már szokatlanul régóta fent van – betöltés közben a koppintás ne hozza elő a szürke YouTube-vázat
        if (e.actionMasked == MotionEvent.ACTION_DOWN && cover.visibility == View.VISIBLE &&
            android.os.SystemClock.elapsedRealtime() - t0 > 2500) hideCover(true)
        return super.dispatchTouchEvent(e)
    }

    /** Shorts-váltás: a KÖVETKEZŐ videó saját poszterképét tesszük fel, amíg a kép el nem indul (nincs fekete). */
    private fun reelPoster(url: String) {
        if (mini || pip || fs) return
        val id = runCatching {
            val seg = Uri.parse(url).pathSegments
            if (seg.size >= 2) seg[1] else ""
        }.getOrNull() ?: return
        if (id.isEmpty() || id.length < 6) return
        if (reelPosterId == id) return
        reelPosterId = id
        runCatching {
            spinner.visibility = View.GONE
            val cl = cover.layoutParams as LayoutParams
            cl.topMargin = 0
            cover.layoutParams = cl
            val tl = thumb.layoutParams as LayoutParams
            tl.height = ViewGroup.LayoutParams.MATCH_PARENT
            thumb.layoutParams = tl
            val u1 = "https://i.ytimg.com/vi/" + id + "/oar2.jpg"
            val u2 = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg"
            val use = when {
                Images.cached(u1) -> u1
                Images.cached(u2) -> u2
                else -> { Images.preload(u1); Images.preload(u2); return }   // még nincs kész: ne takarjunk feketével
            }
            thumb.visibility = View.VISIBLE
            Images.load(thumb, use)
            cover.visibility = View.VISIBLE
            cover.alpha = 1f
            markCoverClickable(false)
            web.alpha = 1f
            removeCallbacks(hideRunnable)
            postDelayed({ hideCover(true) }, 900)
        }
    }

    /** Ha a rendszer elforgatta a képet (pl. megszűnt a teljes képernyő), ne maradjon fent a fullscreen réteg. */
    fun syncOrientation(portrait: Boolean) {
        // az elforgatás lezajlott: a váltás alatti takaró lekerülhet
        postDelayed({ hideCover(true) }, 150)
        if (!fs || !portrait) return
        // a teljes képernyőre váltás első pillanataiban a rendszer még a régi (álló) tájolást jelenti
        if (android.os.SystemClock.elapsedRealtime() - fsSince < 1500) return
        js("window.__vdExitFs&&window.__vdExitFs()")
        setFs(false)
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        if (pip || embedded) return false
        if (mini) {
            val r = Rect()
            miniControls.getHitRect(r)
            return !r.contains(e.x.toInt(), e.y.toInt())
        }
        // Teljes méretű YouTube-felület: MINDEN érintés a lapé (görgetés, lehúzásos frissítés).
        // Csak figyelünk: a videó lehúzása kép-a-képben módot kér.
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { swipeY = e.y; swipeX = e.x; swipeArmed = canPip() && web.url?.contains("/shorts") != true && web.url?.contains("music.youtube.com") != true && e.y < height * 0.45f }
            MotionEvent.ACTION_MOVE -> {
                if (swipeArmed && e.y - swipeY > dp(110) && kotlin.math.abs(e.x - swipeX) < dp(90)) {
                    swipeArmed = false
                    miniTap = false   // ennek a mozdulatnak a vége nem koppintás
                    minimize()   // appon belüli kis ablak, a videó megy tovább
                }
            }
            MotionEvent.ACTION_CANCEL, MotionEvent.ACTION_UP -> swipeArmed = false
        }
        return false
    }

    /** Lehúzás a videón: kép a képben. */
    var onPipRequest: (() -> Unit)? = null
    private var swipeY = 0f
    private var swipeX = 0f
    private var swipeArmed = false
    // Shorts-váltás: a pillanatnyi képkocka befagyasztása (ne legyen fekete a váltás)
    private var reelShot: Bitmap? = null
    private var reelPosterId = ""
    private var navShorts = false
    private var reelSnapY = 0f
    private var reelSnapArmed = false
    private var reelShotAt = 0L

    // Kis ablak: CSAK egy valódi koppintás nagyítja vissza. A lehúzó mozdulat végén az ujj felengedése
    // nem koppintás (eddig ettől ugrott vissza azonnal a rendes képre).
    private var miniTap = false
    private var miniTapX = 0f
    private var miniTapY = 0f

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (mini) {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { miniTap = true; miniTapX = e.x; miniTapY = e.y }
                MotionEvent.ACTION_MOVE -> if (kotlin.math.abs(e.x - miniTapX) > dp(12) || kotlin.math.abs(e.y - miniTapY) > dp(12)) miniTap = false
                MotionEvent.ACTION_UP -> { if (miniTap) expand(); miniTap = false }
                MotionEvent.ACTION_CANCEL -> miniTap = false
            }
            return true
        }
        return super.onTouchEvent(e)
    }
}

/** Életben tartja a folyamatot, amíg a webes lejátszó fut (hang a zárolt képernyőn is). */
class KeepAliveService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // a lezárt képernyő / értesítés gombjai
        when (intent?.action) {
            "vd.prev" -> { MediaCtl.cmd("prev"); return START_NOT_STICKY }
            "vd.next" -> { MediaCtl.cmd("next"); return START_NOT_STICKY }
            "vd.play" -> { MediaCtl.cmd("play"); return START_NOT_STICKY }
            "vd.pause" -> { MediaCtl.cmd("pause"); return START_NOT_STICKY }
        }
        // médiavezérlős értesítés (lezárt képernyőn is: borító, cím, előző / lejátszás-szünet / következő, tekerés)
        val n = MediaCtl.buildNotification(this)
        if (Build.VERSION.SDK_INT >= 29) startForeground(MediaCtl.NID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        else startForeground(MediaCtl.NID, n)
        MediaCtl.serviceOn = true
        // zárolt képernyőn se aludjon el a processzor és a wifi (különben megakad / pufferel a videó)
        if (wake == null) {
            wake = (getSystemService(Context.POWER_SERVICE) as android.os.PowerManager)
                .newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "vidara:web").apply { setReferenceCounted(false); acquire() }
        }
        if (wifi == null) {
            wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as android.net.wifi.WifiManager)
                .createWifiLock(android.net.wifi.WifiManager.WIFI_MODE_FULL_HIGH_PERF, "vidara:web").apply { setReferenceCounted(false); acquire() }
        }
        return START_NOT_STICKY
    }

    private var wake: android.os.PowerManager.WakeLock? = null
    private var wifi: android.net.wifi.WifiManager.WifiLock? = null

    override fun onDestroy() {
        MediaCtl.stop()
        runCatching { wake?.release() }
        runCatching { wifi?.release() }
        super.onDestroy()
    }
}
