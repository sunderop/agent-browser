package com.agentbrowser

import org.json.JSONObject

// JavaScript snippets the server injects into the page.
object Scripts {

    // Words that suggest a CAPTCHA / verification / block page
    private const val KW = "['captcha','verify you are human','verify you are a human','are you a robot','unusual activity','suspicious activity','security check','confirm your identity',\"confirm it's you\",'checkpoint','temporarily blocked','automated queries','too many requests']"

    // Dense, region-grouped list of visible interactive elements. Also tags them in the page.
    private val ELEMENTS = """
(function(){
 document.querySelectorAll('[data-ab-id]').forEach(function(e){e.removeAttribute('data-ab-id');e.removeAttribute('data-ab-label');e.removeAttribute('data-ab-type')});
 var W=window.__abRefs=window.__abRefs||{m:new WeakMap(),r:{}};
 var vh=window.innerHeight,SC=__SCOPE__,ALL=__ALL__,REFS=__REFS__,modal=null;
 if(!SC){
  var ms=document.querySelectorAll('dialog[open],[role=dialog],[aria-modal=true]');
  for(var i=0;i<ms.length;i++){
   var mr=ms[i].getBoundingClientRect(),mc=getComputedStyle(ms[i]);
   if(mr.width>40&&mr.height>40&&mc.visibility!=='hidden'&&mc.display!=='none'){modal=ms[i];break}
  }
 }
 var root=SC?document.querySelector(SC):(modal||document);
 if(!root)return 'ERR NOT_FOUND scope selector matches nothing';
 var M=__MATCH__.toLowerCase();
 var RN={navigation:'nav',banner:'header',contentinfo:'footer',main:'main',dialog:'dialog'};
 var TY={password:'pw',checkbox:'chk',radio:'rad',submit:'btn',button:'btn',file:'file',search:'search'};
 var sel='a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=tab],[role=checkbox],[role=menuitem],[role=switch],[contenteditable=true],[onclick]';
 var els=root.querySelectorAll(sel),out=[],n=0,bc=0,lastReg=null,capped=false;
 for(var j=0;j<els.length;j++){
  var e=els[j];
  var r=e.getBoundingClientRect();
  if(r.width<2||r.height<2)continue;
  var cs=getComputedStyle(e);
  if(cs.visibility==='hidden'||cs.display==='none'||e.type==='hidden')continue;
  if(!ALL&&r.top>vh*2){bc++;continue}
  if(!ALL&&r.bottom<-vh*0.5)continue;
  if(n>=150){capped=true;break}
  var lab=(e.getAttribute('aria-label')||e.innerText||(e.labels&&e.labels[0]?e.labels[0].innerText:'')||e.placeholder||((e.type==='submit'||e.type==='button')?e.value:'')||e.title||e.alt||e.name||'').replace(/\s+/g,' ').trim().slice(0,50);
  var k=e.tagName.toLowerCase(),role=e.getAttribute('role'),t;
  if(k==='a')t='a';
  else if(k==='textarea')t='area';
  else if(k==='select')t='sel';
  else if(k==='input')t=TY[e.type]||'in';
  else if(k==='button'||role==='button')t='btn';
  else if(e.isContentEditable)t='area';
  else if(role==='tab')t='tab';
  else if(role==='checkbox'||role==='switch')t='chk';
  else t='btn';
  var f='';
  if((t==='in'||t==='search'||t==='area')&&e.value)f+=' ='+JSON.stringify(String(e.value).slice(0,30));
  if(t==='pw'&&e.value)f+=' (filled)';
  if(e.checked||e.getAttribute('aria-checked')==='true')f+=' ✓';
  if(e.disabled||e.getAttribute('aria-disabled')==='true')f+=' off';
  if(e.required)f+=' *';
  if(t==='sel'&&e.selectedIndex>=0)f+=' ='+JSON.stringify(e.options[e.selectedIndex].text.slice(0,25));
  var href='';
  if(t==='a'){
   href=e.getAttribute('href')||'';
   try{var u=new URL(e.href);href=(u.host===location.host?'':u.host)+u.pathname}catch(x){}
   href=href.slice(0,45);
  }
  if(r.top>=vh)f+=' ↓';else if(r.bottom<=0)f+=' ↑';
  if(M&&(lab+' '+t+f).toLowerCase().indexOf(M)<0)continue;
  var lm=e.closest('dialog,[role=dialog],nav,[role=navigation],header,[role=banner],footer,[role=contentinfo],aside,form,main,[role=main]');
  var reg='';
  if(lm){var lr=lm.getAttribute('role'),lt=lm.tagName.toLowerCase();reg=RN[lr]||lr||lt}
  if(reg!==lastReg){out.push('['+(reg||'page')+']');lastReg=reg}
  n++;
  var ref=W.m.get(e);
  if(!ref){ref='e'+Math.random().toString(36).slice(2,7);W.m.set(e,ref);W.r[ref]=new WeakRef(e)}
  e.setAttribute('data-ab-id',n);
  e.setAttribute('data-ab-label',lab.toLowerCase());
  e.setAttribute('data-ab-type',t);
  out.push(n+' '+t+' "'+(lab||'?')+'"'+f+(href?' \u2192'+href:'')+(REFS?' ref='+ref:''));
 }
 var sh=Math.max(1,document.documentElement.scrollHeight-vh);
 var head='# '+document.title.slice(0,60)+' | '+location.host+location.pathname.slice(0,40)+' | y'+Math.round(window.scrollY/sh*100)+'%'+((modal&&!SC)?' | MODAL open (listing only it; pass scope=body for the page)':'');
 if(!n)return head+'\nno interactive elements found';
 if(bc)out.push('+'+bc+' more below (scroll, or use --all)');
 if(capped)out.push('capped at 150');
 var fo=document.activeElement;
 if(fo&&fo.getAttribute&&fo.getAttribute('data-ab-id'))out.push('focus: '+fo.getAttribute('data-ab-id'));
 return head+'\n'+out.join('\n');
})()
""".trimIndent()

    // Finds a tagged element by its label (exact first, then contains).
    private val MATCH = """
(function(){
 var L=__LABEL__.toLowerCase(),IN=__INPUTS__;
 var els=document.querySelectorAll('[data-ab-id]'),ex=[],pa=[];
 for(var i=0;i<els.length;i++){
  var e=els[i],lab=e.getAttribute('data-ab-label')||'',ty=e.getAttribute('data-ab-type');
  if(IN&&['in','search','pw','area','sel'].indexOf(ty)<0)continue;
  if(lab===L)ex.push(e);else if(lab.indexOf(L)>=0)pa.push(e);
 }
 var c=ex.length?ex:pa;
 if(c.length===0)return 'none';
 if(c.length===1)return c[0].getAttribute('data-ab-id');
 return 'many:\n'+c.slice(0,8).map(function(e){return e.getAttribute('data-ab-id')+'. '+e.getAttribute('data-ab-label')+' - '+e.getAttribute('data-ab-type')}).join('\n');
})()
""".trimIndent()

    private val INSPECT = """
(function(){
 var e=__FIND__;
 if(!e)return 'ERR ELEMENT_STALE element not found - the page changed. Run browse elements again.';
 var r=e.getBoundingClientRect();
 var o=[];
 o.push('tag: '+e.tagName.toLowerCase()+(e.type?' type='+e.type:''));
 ['id','name','role','aria-label','placeholder','title','href','alt','aria-expanded','aria-checked','accept'].forEach(function(a){var v=e.getAttribute(a);if(v)o.push(a+': '+v.slice(0,150))});
 if(e.className&&typeof e.className==='string')o.push('class: '+e.className.slice(0,120));
 var t=(e.innerText||'').replace(/\s+/g,' ').trim();
 if(t)o.push('text: '+t.slice(0,300));
 if(e.labels&&e.labels[0])o.push('label: '+e.labels[0].innerText.replace(/\s+/g,' ').trim().slice(0,100));
 if(e.value!==undefined&&e.type!=='password')o.push('value: '+String(e.value).slice(0,200));
 o.push('state: '+[e.disabled?'disabled':'enabled',e.checked?'checked':'',e.required?'required':'',e.readOnly?'readonly':''].filter(Boolean).join(' '));
 o.push('box: x='+Math.round(r.left)+' y='+Math.round(r.top)+' w='+Math.round(r.width)+' h='+Math.round(r.height)+((r.bottom<0||r.top>window.innerHeight)?' (off-screen)':''));
 var f=e.form;
 if(f)o.push('form: '+(f.id||f.getAttribute('name')||'(unnamed)')+' action='+(f.getAttribute('action')||'').slice(0,80));
 var h=null,p=e;
 while(p&&!h){
  var s=p.previousElementSibling;
  while(s&&!h){
   if(/^H[1-6]$/.test(s.tagName))h=s;
   else{var q=s.querySelector&&s.querySelector('h1,h2,h3,h4,h5,h6');if(q)h=q}
   s=s.previousElementSibling;
  }
  p=p.parentElement;
 }
 if(h)o.push('under heading: '+h.innerText.replace(/\s+/g,' ').trim().slice(0,80));
 o.push('html: '+e.outerHTML.slice(0,400));
 return o.join('\n');
})()
""".trimIndent()

    // Compact page map: headings, forms with their fields, and counts
    val OUTLINE = """
(function(){
 var o=['TITLE: '+document.title,'URL: '+location.href];
 var hs=document.querySelectorAll('h1,h2,h3,h4');
 if(hs.length)o.push('','HEADINGS');
 for(var i=0;i<hs.length&&i<40;i++){var t=hs[i].innerText.replace(/\s+/g,' ').trim();if(t)o.push(hs[i].tagName+' '+t.slice(0,80))}
 var fs=document.forms;
 if(fs.length)o.push('','FORMS');
 for(var j=0;j<fs.length&&j<10;j++){
  var f=fs[j],names=[];
  for(var k=0;k<f.elements.length&&k<15;k++){var el=f.elements[k];if(el.type==='hidden')continue;var l=(el.labels&&el.labels[0]?el.labels[0].innerText:'')||el.placeholder||el.name||el.id||el.type;names.push(String(l).replace(/\s+/g,' ').trim().slice(0,30)+':'+el.type)}
  o.push((j+1)+'. '+(f.getAttribute('action')||'(no action)').slice(0,50)+' -> '+names.join(', '));
 }
 var bs=document.querySelectorAll('button,[role=button],input[type=submit]'),bl=[];
 for(var m=0;m<bs.length&&bl.length<20;m++){var bt=(bs[m].innerText||bs[m].value||bs[m].getAttribute('aria-label')||'').replace(/\s+/g,' ').trim();if(bt)bl.push(bt.slice(0,25))}
 if(bl.length)o.push('','BUTTONS',bl.join(' | '));
 o.push('','COUNTS links='+document.links.length+' buttons='+bs.length+' inputs='+document.querySelectorAll('input,textarea,select').length+' images='+document.images.length+' iframes='+document.querySelectorAll('iframe').length);
 return o.join('\n');
})()
""".trimIndent()

    // Compact page state for the agent
    val STATE = """
(function(){
 var kw=${KW};
 var t=(document.title+' '+location.href+' '+(document.body?document.body.innerText.slice(0,800):'')).toLowerCase();
 var cap='false';
 for(var i=0;i<kw.length;i++){if(t.indexOf(kw[i])>=0){cap=kw[i];break}}
 var pw=!!document.querySelector('input[type=password]');
 var u=location.href.toLowerCase();
 var auth=pw||/(login|log-in|signin|sign-in|accounts\.google|\/auth)/.test(u);
 var modal=!!document.querySelector('dialog[open],[role=dialog],[aria-modal=true]');
 var sh=Math.max(1,document.documentElement.scrollHeight-window.innerHeight);
 return ['URL: '+location.host+location.pathname.slice(0,60),'TITLE: '+document.title.slice(0,80),'READY: '+(document.readyState==='complete'),'LOADING: '+(document.readyState!=='complete'),'MODAL: '+modal,'AUTH_REQUIRED: '+auth,'CAPTCHA: '+cap,'FORMS: '+document.forms.length,'BUTTONS: '+document.querySelectorAll('button,[role=button]').length,'INPUTS: '+document.querySelectorAll('input,textarea,select').length,'SCROLL: '+Math.round(window.scrollY/sh*100)+'%'].join('\n');
})()
""".trimIndent()

    // Returns a short keyword if the page looks like a verification / block page, else ''.
    val CHALLENGE = """
(function(){
 var kw=${KW};
 var t=(document.title+' '+location.href+' '+(document.body?document.body.innerText.slice(0,800):'')).toLowerCase();
 for(var i=0;i<kw.length;i++){if(t.indexOf(kw[i])>=0)return kw[i]}
 return '';
})()
""".trimIndent()

    val AUTH_HINT = """
(function(){var pw=!!document.querySelector('input[type=password]');var u=location.href.toLowerCase();return pw||/(login|log-in|signin|sign-in|accounts\.google|\/auth)/.test(u)})()
""".trimIndent()

    private val LABEL = """
(function(){var e=__EXPR__;if(!e)return '';return (e.getAttribute('aria-label')||e.innerText||e.value||e.title||e.alt||e.name||'').replace(/\s+/g,' ').trim().slice(0,80)})()
""".trimIndent()

    // Label of the submit button of the focused element's form (what Enter would trigger)
    val ENTER_LABEL = """
(function(){var e=document.activeElement;var f=e&&e.form;if(!f)return '';var b=f.querySelector('[type=submit],button:not([type=button])');return b?((b.innerText||b.value||'')+'').replace(/\s+/g,' ').trim().slice(0,80):''})()
""".trimIndent()

    // Scrolls to the element, hovers, presses and releases at a point inside it, then clicks.
    private val CLICK = """
(function(){
 var e=__FIND__;
 if(!e)return 'ERR ELEMENT_STALE element not found - the page changed. Run browse elements again.';
 if(e.disabled||e.getAttribute('aria-disabled')==='true')return 'ERR ELEMENT_DISABLED the element is disabled';
 e.scrollIntoView({block:'center'});
 var r=e.getBoundingClientRect();
 var x=r.left+r.width*(0.3+Math.random()*0.4),y=r.top+r.height*(0.3+Math.random()*0.4);
 var o={bubbles:true,cancelable:true,clientX:x,clientY:y,view:window};
 ['pointerover','mouseover','pointermove','mousemove','pointerdown','mousedown','pointerup','mouseup'].forEach(function(t){
  var C=t.indexOf('pointer')===0?PointerEvent:MouseEvent;
  e.dispatchEvent(new C(t,o));
 });
 e.click();
 return 'clicked';
})()
""".trimIndent()

    // Instant whole-value typing (used for long text or when pacing is off)
    private val TYPE = """
(function(){
 var e=__FIND__;
 if(!e)return 'ERR ELEMENT_STALE element not found - the page changed. Run browse elements again.';
 if(e.disabled)return 'ERR ELEMENT_DISABLED the field is disabled';
 e.scrollIntoView({block:'center'});
 e.focus();
 var v=__TEXT__;
 if(e.isContentEditable){
  document.execCommand('selectAll',false,null);
  document.execCommand('insertText',false,v);
 }else{
  var P=e instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
  var d=Object.getOwnPropertyDescriptor(P,'value');
  if(d&&d.set){d.set.call(e,v)}else{e.value=v}
  e.dispatchEvent(new Event('input',{bubbles:true}));
  e.dispatchEvent(new Event('change',{bubbles:true}));
 }
 return 'typed';
})()
""".trimIndent()

    private val FOCUS_CLEAR = """
(function(){
 var e=__FIND__;
 if(!e)return 'ERR ELEMENT_STALE element not found - the page changed. Run browse elements again.';
 if(e.disabled)return 'ERR ELEMENT_DISABLED the field is disabled';
 e.scrollIntoView({block:'center'});
 e.focus();
 if(e.isContentEditable){document.execCommand('selectAll',false,null);document.execCommand('delete',false,null)}
 else if(e.value){
  var P=e instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
  var d=Object.getOwnPropertyDescriptor(P,'value');
  if(d&&d.set){d.set.call(e,'')}else{e.value=''}
  e.dispatchEvent(new Event('input',{bubbles:true}));
 }
 return 'focused';
})()
""".trimIndent()

    private val TYPE_CHAR = """
(function(){
 var e=document.activeElement;
 if(!e)return 'no focus';
 var c=__CH__;
 ['keydown','keypress'].forEach(function(t){e.dispatchEvent(new KeyboardEvent(t,{key:c,bubbles:true,cancelable:true}))});
 if(e.isContentEditable){document.execCommand('insertText',false,c)}
 else{
  var P=e instanceof HTMLTextAreaElement?HTMLTextAreaElement.prototype:HTMLInputElement.prototype;
  var d=Object.getOwnPropertyDescriptor(P,'value');
  var v=(e.value||'')+c;
  if(d&&d.set){d.set.call(e,v)}else{e.value=v}
  e.dispatchEvent(new Event('input',{bubbles:true}));
 }
 e.dispatchEvent(new KeyboardEvent('keyup',{key:c,bubbles:true}));
 return 'ok';
})()
""".trimIndent()

    val CHANGE = """
(function(){var e=document.activeElement;if(e)e.dispatchEvent(new Event('change',{bubbles:true}));return 'ok'})()
""".trimIndent()

    private val PRESS = """
(function(){
 var K=__KEY__;
 var e=document.activeElement||document.body;
 var ok=true;
 ['keydown','keypress','keyup'].forEach(function(t){
  var r=e.dispatchEvent(new KeyboardEvent(t,{key:K,code:K,bubbles:true,cancelable:true}));
  if(t==='keydown')ok=r;
 });
 if(K==='Enter'&&ok&&e.form){try{e.form.requestSubmit()}catch(x){e.form.submit()}}
 return 'pressed '+K;
})()
""".trimIndent()

    // Expressions that locate an element
    fun findId(id: Int) = "document.querySelector('[data-ab-id=\"" + id + "\"]')"

    fun findRef(ref: String) =
        "(function(){var w=window.__abRefs&&window.__abRefs.r[" + JSONObject.quote(ref) +
        "];var e=w&&w.deref();return e&&e.isConnected?e:null})()"

    fun findSel(sel: String) = "document.querySelector(" + JSONObject.quote(sel) + ")"

    fun elements(scope: String, match: String, all: Boolean, refs: Boolean) =
        ELEMENTS.replace("__SCOPE__", JSONObject.quote(scope))
            .replace("__MATCH__", JSONObject.quote(match))
            .replace("__ALL__", if (all) "true" else "false")
            .replace("__REFS__", if (refs) "true" else "false")

    fun match(label: String, inputsOnly: Boolean) =
        MATCH.replace("__LABEL__", JSONObject.quote(label))
            .replace("__INPUTS__", if (inputsOnly) "true" else "false")

    fun inspect(find: String) = INSPECT.replace("__FIND__", find)

    fun label(find: String) = LABEL.replace("__EXPR__", find)

    fun click(find: String) = CLICK.replace("__FIND__", find)

    fun type(find: String, text: String) =
        TYPE.replace("__FIND__", find).replace("__TEXT__", JSONObject.quote(text))

    fun focusClear(find: String) = FOCUS_CLEAR.replace("__FIND__", find)

    fun typeChar(ch: String) = TYPE_CHAR.replace("__CH__", JSONObject.quote(ch))

    fun press(key: String) = PRESS.replace("__KEY__", JSONObject.quote(key))

    fun scroll(down: Boolean) = scrollStep(down, 0.8)

    fun scrollStep(down: Boolean, fraction: Double) =
        "(function(){window.scrollBy(0," + (if (down) "" else "-") +
        "Math.round(window.innerHeight*" + fraction + "));return 'scrolled'})()"
}
