// 読みさし: the works being read, newest first, at most twenty (2026-11-04/05, the user). Drawn the same from the
// reader's bars (reader.html) and from the shelf's toolbar (yomisashi.html), from one list the app gives as JSON:
// {view, max, items: [{key, title, sub, text, pct, fresh, current}]}.
//   C (view "c"): a sheet of two columns; the empty places are drawn up to `max` (six), never twenty.
//   A (view "a"): large cards in a row, like the system's recent apps.
// The view last used is kept by the app. A card opens its work where it was left (the work being read only closes
// this); ✕ (C) or a sweep up (A) takes it out of 読みさし, not off the shelf. The sheet closes as Aa's does: a tap
// outside, 閉じる, Back, or pulled down from its top past a third of its height (at least 120 px).
//
// ⟳ asks the sites about the whole shelf, as the shelf's 新着を確認 does (only when pressed: opening the sheet or a
// card asks nothing), and draws the cards again with what it found.
//
// Yomisashi.init(el, host): host = {list() -> JSON string, open(key), drop(key), view(v), check(), closed()};
// the app answers check() with Yomisashi.checked(message).
var Yomisashi=(function(){
  var el=null, host=null, data=null, swiped=false, opening=false, checking=false, note='';
  var REFRESH='<svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M17.65,6.35C16.2,4.9 14.21,4 12,4c-4.42,0 -7.99,3.58 -7.99,8s3.57,8 7.99,8c3.73,0 6.84,-2.55 7.73,-6h-2.08c-0.82,2.33 -3.04,4 -5.65,4 -3.31,0 -6,-2.69 -6,-6s2.69,-6 6,-6c1.66,0 3.14,0.69 4.22,1.78L13,11h7V4l-2.35,2.35z"/></svg>';
  function check(){return '<button class="ck" data-ys="check" aria-label="新着を確認"'+(checking?' disabled':'')+'>'+REFRESH+'</button>';}
  function said(){return note?'<div class="said" role="status">'+esc(note)+'</div>':'';}
  function esc(t){return String(t).replace(/[&<>"']/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c];});}
  function isOpen(){return !!el&&el.classList.contains('on');}
  function open(){
    var d=null;
    try{d=JSON.parse(host.list());}catch(e){}
    data=d&&d.items?d:{view:'c',max:6,items:[]};
    swiped=false;opening=false;if(!checking)note='';
    el.classList.add('on');draw();
  }
  function close(){if(!isOpen())return;el.classList.remove('on','big');el.innerHTML='';if(host.closed)host.closed();}
  function drop(k){host.drop(k);data.items=data.items.filter(function(w){return w.key!==k;});draw();}
  function info(w){return (w.text?'<div class="ex">'+esc(w.text)+'</div>':'')+'<div class="su">'+esc(w.sub)+'</div>'+
    (w.fresh?'<span class="nw">新着</span>':'')+'<div class="pr"><i style="width:'+Math.round(w.pct*100)+'%"></i></div>';}
  function card(cls,w,inner){return '<div class="'+cls+(w.current?' cur':'')+'" data-key="'+esc(w.key)+'" role="button" tabindex="0" aria-label="'+esc(w.title)+'">'+inner+'</div>';}
  function draw(){
    var d=data, big=d.view==='a', it=d.items, h='<div class="dim" data-ys="close"></div>', i;
    el.classList.toggle('big',big);
    if(!big){
      h+='<div class="sh"><div class="hd"><span class="t">読みさし</span>'+check()+'<button data-ys="a">大きく</button><button data-ys="close">閉じる</button></div>'+said()+'<div class="grid">';
      var n=Math.max(d.max,it.length+it.length%2);
      for(i=0;i<n;i++){var w=it[i];
        if(!w){h+='<div class="empty">空き</div>';continue;}
        h+=card('cd',w,(w.current?'':'<button class="x" data-drop="'+esc(w.key)+'" aria-label="読みさしから外す">✕</button>')+
          '<div class="ti">'+esc(w.title)+'</div>'+info(w)+(w.current?'<div class="now">いま読んでいる</div>':''));}
      h+='</div><div class="nt">✕ で読みさしから外します（本棚には残ります）</div></div>';
    }else{
      h+='<div class="bg"><div class="hd"><span class="t">読みさし</span>'+check()+'<button data-ys="c">小さく</button><button data-ys="close">閉じる</button></div>'+
        said()+'<div class="nt">上に払うと読みさしから外します（本棚には残ります）</div><div class="row">';
      if(!it.length)h+='<div class="bc"><div class="su">まだありません</div></div>';
      it.forEach(function(w){h+=card('bc',w,'<div class="vt">'+esc(w.title)+'</div>'+info(w));});
      h+='</div></div>';
    }
    el.innerHTML=h;
    if(big){var c=el.querySelector('.bc.cur');if(c)c.scrollIntoView({inline:'center',block:'nearest'});el.querySelectorAll('.bc[data-key]').forEach(sweep);}
  }
  // A: a card follows the finger up past 10 px of give (a tap's drift still opens it); let go past 90 px it goes
  // out of 読みさし. Not the work being read.
  function sweep(c){var y0=null,x0=null,dy=0;
    function back(){c.style.transition='transform 180ms ease-out,opacity 180ms';c.style.transform='';c.style.opacity='';}
    c.addEventListener('touchstart',function(e){y0=e.touches[0].clientY;x0=e.touches[0].clientX;dy=0;c.style.transition='none';},{passive:true});
    c.addEventListener('touchmove',function(e){if(y0===null)return;var my=e.touches[0].clientY-y0,mx=e.touches[0].clientX-x0;
      if(Math.abs(mx)>Math.abs(my)&&dy===0)return;dy=Math.min(0,my+10);
      if(dy<0){swiped=true;c.style.transform='translateY('+dy+'px)';c.style.opacity=String(1+dy/400);}},{passive:true});
    // Taken by the system (a back gesture, say): the card goes back, and taps are taken again.
    c.addEventListener('touchcancel',function(){y0=null;dy=0;back();swiped=false;},{passive:true});
    c.addEventListener('touchend',function(){if(y0===null)return;y0=null;c.style.transition='transform 180ms ease-out,opacity 180ms';
      if(dy<-90&&!c.classList.contains('cur')){c.style.transform='translateY(-120%)';c.style.opacity='0';var k=c.dataset.key;setTimeout(function(){drop(k);},180);}
      else{c.style.transform='';c.style.opacity='';}
      setTimeout(function(){swiped=false;},50);});}
  // C: the sheet pulled down from its top. Not once scrolled, so a scroll never ends in a close.
  function pull(){var sh=null,y0=null,dy=0;
    function back(){if(!sh)return;sh.style.transition='transform 150ms ease-out';sh.style.transform='';}
    el.addEventListener('touchstart',function(e){
      if(e.touches.length!==1){if(y0!==null)back();y0=null;dy=0;return;}
      sh=e.target.closest&&e.target.closest('.sh');y0=sh&&sh.scrollTop<=0?e.touches[0].clientY:null;dy=0;if(sh)sh.style.transition='';},{passive:true});
    el.addEventListener('touchmove',function(e){
      if(y0===null)return;
      if(sh.scrollTop>0){y0=null;dy=0;sh.style.transform='';return;}
      dy=Math.max(0,e.touches[0].clientY-y0-10);if(dy>0)swiped=true;
      sh.style.transform=dy>0?'translateY('+dy+'px)':'';},{passive:true});
    el.addEventListener('touchend',function(){
      if(y0===null)return;y0=null;
      if(dy>Math.max(120,sh.offsetHeight/3))close();else back();
      dy=0;setTimeout(function(){swiped=false;},50);},{passive:true});
    el.addEventListener('touchcancel',function(){y0=null;dy=0;back();swiped=false;},{passive:true});
  }
  function init(e,h){
    el=e;host=h;pull();
    el.addEventListener('click',function(ev){
      if(swiped)return;
      var t=ev.target, a=t.closest&&t.closest('[data-ys]'), x=t.closest&&t.closest('[data-drop]'), k=t.closest&&t.closest('[data-key]');
      if(x){drop(x.dataset.drop);return;}
      if(a){var v=a.dataset.ys;if(v==='close'){close();return;}
        if(v==='check'){if(checking)return;checking=true;note='新着を確認しています…';draw();host.check();return;}
        data.view=v;host.view(v);draw();return;}
      // One work opens: a second tap (or another card) while it does would start a second reader.
      if(k){if(k.classList.contains('cur')){close();return;}if(opening)return;opening=true;k.style.opacity='.6';host.open(k.dataset.key);}
    });
  }
  // The check is over: the cards drawn again from the shelf as it now is, in the view open, with what was found.
  function checked(msg){checking=false;note=msg||'';if(!isOpen())return;var v=data.view,d=null;
    try{d=JSON.parse(host.list());}catch(e){}
    if(d&&d.items){d.view=v;data=d;}draw();}
  return {init:init,open:open,close:close,isOpen:isOpen,checked:checked};
})();
