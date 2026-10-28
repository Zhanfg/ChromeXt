package org.matrix.chromext.extension

import java.io.File
import org.json.JSONObject

/** A subset of chrome.runtime and chrome.storage.local, installed only in a CDP isolated world. */
object ExtensionApiBootstrap {
  const val BINDING = "__chromeXtExtensionRpc"

  fun source(id: String, directory: File): String {
    val file = File(directory, "manifest.json")
    require(file.isFile && file.length() <= 512L * 1024)
    val parsedManifest = JSONObject(file.readText(Charsets.UTF_8))
    val manifest = parsedManifest.toString()
    val permissions = parsedManifest.optJSONArray("permissions")
    val hasStorage = permissions != null &&
        (0 until permissions.length()).any { permissions.getString(it) == "storage" }
    return """(()=>{
      if (globalThis.__chromeXtApiReady) return;
      Object.defineProperty(globalThis,'__chromeXtApiReady',{value:true});
      const pending=new Map();
      let next=1;
      const rpc=(method,args)=>new Promise((resolve,reject)=>{
        const id=next++;pending.set(id,{resolve,reject});
        try{globalThis.$BINDING(JSON.stringify({id,method,args}));}
        catch(e){pending.delete(id);reject(e);}
      });
      Object.defineProperty(globalThis,'__chromeXtReply',{value:(reply)=>{
        const call=pending.get(reply.id);
        if(!call)return;pending.delete(reply.id);
        if(reply.ok)call.resolve(reply.value);
        else call.reject(new Error(reply.error||'Unsupported API'));
      }});
      const wrap=(method,args,callback)=>{
        const promise=rpc(method,args);
        if(typeof callback==='function'){
          promise.then(value=>callback(value),err=>{
            console.error('ChromeXt extension API error',err);
            callback(undefined);
          });
          return undefined;
        }
        return promise;
      };
      const local={
        get:(keys,cb)=>wrap('storage.local.get',[keys===undefined?null:keys],cb),
        set:(values,cb)=>wrap('storage.local.set',[values],cb),
        remove:(keys,cb)=>wrap('storage.local.remove',[keys],cb),
        clear:(cb)=>wrap('storage.local.clear',[],cb)
      };
      const namespace={
        runtime:Object.freeze({
          id:${JSONObject.quote(id)},
          getManifest:()=>JSON.parse(${JSONObject.quote(manifest)})
        })
      };
      if($hasStorage)namespace.storage=Object.freeze({local:Object.freeze(local)});
      Object.defineProperty(globalThis,'chrome',{value:Object.freeze(namespace),configurable:false});
    })();"""
  }
}
