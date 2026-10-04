package com.agentbrowser

import org.json.JSONObject

// JavaScript snippets the server injects into the page.
object Scripts {

    // Numbers visible clickable/typeable elements with their state. Optional scope (CSS) and match (text).
    private val ELEMENTS = """
(function(){
 document.querySelectorAll('[data-ab-id]').forEach(function(e){e.removeAttribute('data-ab-id')});
 var SC=__SCOPE__;
 var root=SC?document.querySelector(SC):document;
 if(!root)return 'scope not found';
 var M=__MATCH__.toLowerCase();
 var sel='a[href],button,input,textarea,select,summary,[role=button],[role=link],[role=tab],[role=checkbox],[role=menuitem],[contenteditable=true],[onclick]';
 var out=[],n=0,vh=window.innerHeight;
 root.querySelectorAll(sel).forEach(function(e){
  if(n>=150)return;
  var r=e.getBoundingClientRect();
  if(r.width<2||r.height<2)return;
  var cs=getComputedStyle(e);
  if(cs.visibility==='hidden'||cs.display==='none')return;
  if(e.type==='hidden')return;
  var lab=(e.getAttribute('aria-label')||e.innerText||(e.labels&&e.labels[0]?e.labels[0].innerText:'')||e.placeholder||((e.type==='submit'||e.type==='button')?e.value:'')||e.title||e.alt||e.name||'').replace(/\s+/g,' ').trim().slice(0,60);
  var k=e.tagName.toLowerCase();
  if(k==='input')k='input:'+(e.type||'text');
  var x=[];
  var isText=(k.indexOf('input:')===0&&['password','checkbox','radio','submit','button','file','image'].indexOf(e.type)<0)||k==='textarea';
  if(isText&&e.value)x.push('value="'+String(e.value).slice(0,40)+'"');
  if(e.type==='password')x.push('(password)');
  if(e.checked)x.push('checked');
  if(e.disabled)x.push('disabled');
  if(e.required)x.push('required');
  if(e.getAttribute('aria-expanded'))x.push('expanded='+e.getAttribute('aria-expanded'));
  if(k==='select'&&e.selectedIndex>=0)x.push('selected="'+e.options[e.selectedIndex].text.slice(0,30)+'"');
  if(k==='a')x.push('-> '+(e.getAttribute('href')||'').slice(0,70));
  if(r.bottom<0||r.top>vh)x.push('off-screen');
  if(M&&(lab+' '+x.join(' ')).toLowerCase().indexOf(M)<0)return;
  n++;
  e.setAttribute('data-ab-id',n);
  out.push(n+'. ['+k+'] '+lab+(x.length?'  | '+x.join(' '):''));
 });
 return out.length?out.join('\n'):'no interactive elements found';
})()
""".trimIndent()

    private val INSPECT = """
(function(){
 var e=document.querySelector('[data-ab-id="__ID__"]');
 if(!e)return 'element __ID__ not found - run elements again';
 var r=e.getBoundingClientRect();
 var o=[];
 o.push('tag: '+e.tagName.toLowerCase()+(e.type?' type='+e.type:''));
 ['id','name','role','aria-label','placeholder','title','href','alt','aria-expanded','aria-checked','accept'].forEach(function(a){var v=e.getAttribute(a);if(v)o.push(a+': '+v.slice(0,150))});
 if(e.className&&typeof e.className==='string')o.push('class: '+e.className.slice(0,120));
 var t=(e.innerText||'').replace(/\s+/g,' ').trim();
 if(t)o.push('text: '+t.slice(0,300));
 if(e.value!==undefined&&e.type!=='password')o.push('value: '+String(e.value).slice(0,200));
 o.push('state: '+[e.disabled?'disabled':'',e.checked?'checked':'',e.required?'required':'',e.readOnly?'readonly':''].filter(Boolean).join(' '));
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

    val OUTLINE = """
(function(){
 var o=['title: '+document.title,'url: '+location.href];
 var hs=document.querySelectorAll('h1,h2,h3,h4');
 for(var i=0;i<hs.length&&i<40;i++){var t=hs[i].innerText.replace(/\s+/g,' ').trim();if(t)o.push(hs[i].tagName.toLowerCase()+': '+t.slice(0,80))}
 var fs=document.forms;
 for(var j=0;j<fs.length&&j<10;j++){
  var f=fs[j],names=[];
  for(var k=0;k<f.elements.length&&k<15;k++){var el=f.elements[k];if(el.type==='hidden')continue;names.push((el.name||el.id||el.type)+':'+el.type)}
  o.push('form '+(j+1)+' ('+(f.getAttribute('action')||'').slice(0,50)+'): '+names.join(', '));
 }
 o.push('counts: links='+document.links.length+' buttons='+document.querySelectorAll('button,[role=button]').length+' inputs='+document.querySelectorAll('input,textarea,select').length+' images='+document.images.length+' iframes='+document.querySelectorAll('iframe').length);
 return o.join('\n');
})()
""".trimIndent()

    private val LABEL = """
(function(){var e=__EXPR__;if(!e)return '';return (e.getAttribute('aria-label')||e.innerText||e.value||e.title||e.alt||e.name||'').replace(/\s+/g,' ').trim().slice(0,80)})()
""".trimIndent()

    // Label of the submit button of the focused element's form (what Enter would trigger)
    val ENTER_LABEL = """
(function(){var e=document.activeElement;var f=e&&e.form;if(!f)return '';var b=f.querySelector('[type=submit],button:not([type=button])');return b?((b.innerText||b.value||'')+'').replace(/\s+/g,' ').trim().slice(0,80):''})()
""".trimIndent()

    private val CLICK = """
(function(){
 var e=document.querySelector('[data-ab-id="__ID__"]');
 if(!e)return 'element __ID__ not found - run elements again';
 e.scrollIntoView({block:'center'});
 e.click();
 return 'clicked __ID__';
})()
""".trimIndent()

    private val TYPE = """
(function(){
 var e=document.querySelector('[data-ab-id="__ID__"]');
 if(!e)return 'element __ID__ not found - run elements again';
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
 return 'typed into __ID__';
})()
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

    fun elements(scope: String, match: String) =
        ELEMENTS.replace("__SCOPE__", JSONObject.quote(scope)).replace("__MATCH__", JSONObject.quote(match))

    fun inspect(id: Int) = INSPECT.replace("__ID__", id.toString())

    fun labelById(id: Int) =
        LABEL.replace("__EXPR__", "document.querySelector('[data-ab-id=\"" + id + "\"]')")

    fun labelBySel(sel: String) =
        LABEL.replace("__EXPR__", "document.querySelector(" + JSONObject.quote(sel) + ")")

    fun click(id: Int) = CLICK.replace("__ID__", id.toString())

    fun type(id: Int, text: String) =
        TYPE.replace("__ID__", id.toString()).replace("__TEXT__", JSONObject.quote(text))

    fun press(key: String) = PRESS.replace("__KEY__", JSONObject.quote(key))

    fun scroll(down: Boolean) =
        "(function(){window.scrollBy(0," + (if (down) "" else "-") +
        "Math.round(window.innerHeight*0.8));return 'scrolled'})()"
}
