package com.agentbrowser

import org.json.JSONObject

// JavaScript snippets the server injects into the page.
object Scripts {

    // Numbers every visible clickable/typeable element and returns the list.
    val ELEMENTS = """
(function(){
 document.querySelectorAll('[data-ab-id]').forEach(function(e){e.removeAttribute('data-ab-id')});
 var sel='a[href],button,input,textarea,select,[role=button],[role=link],[role=tab],[contenteditable=true],[onclick]';
 var out=[],n=0;
 document.querySelectorAll(sel).forEach(function(e){
  if(n>=150)return;
  var r=e.getBoundingClientRect();
  if(r.width<2||r.height<2)return;
  var cs=getComputedStyle(e);
  if(cs.visibility==='hidden'||cs.display==='none')return;
  if(e.type==='hidden')return;
  n++;
  e.setAttribute('data-ab-id',n);
  var t=(e.getAttribute('aria-label')||e.innerText||e.placeholder||e.value||e.title||e.alt||e.name||'').replace(/\s+/g,' ').trim().slice(0,60);
  var k=e.tagName.toLowerCase();
  if(k==='input')k='input:'+(e.type||'text');
  var h=k==='a'?(' -> '+(e.getAttribute('href')||'').slice(0,70)):'';
  out.push(n+'. ['+k+'] '+t+h);
 });
 return out.length?out.join('\n'):'no interactive elements found';
})()
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

    fun click(id: Int) = CLICK.replace("__ID__", id.toString())

    fun type(id: Int, text: String) =
        TYPE.replace("__ID__", id.toString()).replace("__TEXT__", JSONObject.quote(text))

    fun press(key: String) = PRESS.replace("__KEY__", JSONObject.quote(key))

    fun scroll(down: Boolean) =
        "(function(){window.scrollBy(0," + (if (down) "" else "-") +
        "Math.round(window.innerHeight*0.8));return 'scrolled'})()"
}
