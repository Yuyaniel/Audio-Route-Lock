package dev.codex.audioroutelock;

import android.os.Handler;
import android.os.Looper;
import android.webkit.ValueCallback;
import android.webkit.WebView;

import org.json.JSONArray;

/**
 * 浏览器网页音频的静音（模块对网页音频的唯一手段：网页音频是 Chromium 的 native 输出，不经过 Java 的
 * {@code AudioTrack} / {@code MediaPlayer}）。
 *
 * <p>在页面里做三件事：
 *
 * <ul>
 *   <li>把 audio/video 元素标记并 {@code muted = true}（播放继续，只是没有声音）；</li>
 *   <li>拦截 Web Audio：包装 {@code AudioNode.prototype.connect}，把连向 {@code AudioContext.destination}
 *       的连接改道经过一个增益为 0 的 GainNode——音乐站常用的 AudioContext 播放靠这条才能静音；</li>
 *   <li>静音期间把 {@code HTMLMediaElement} 的 volume/muted 写入口钳死、并在 {@code play()} 前先置静音，
 *       页面点「取消静音」也写不进有声状态（消除非原子带来的漏音窗口）。</li>
 * </ul>
 *
 * <p>它只覆盖「脚本执行时那个 document」，跳转会换掉 document，所以 {@link ModuleMain} 在
 * 「页面加载完成」事件上补注入；跨域 iframe 与注入前已连线的 Web Audio 图覆盖不到（已知盲区）。
 * 脚本返回 {@code "元素已静音|元素数|iframe数|WebAudio目的地数"}，
 * 通过 {@link #resultListener} 回传用于诊断。
 */
final class WebViewAudioMuter {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    /** 静音脚本的执行结果回调，运行在主线程；summary 为 {@code "元素已静音|元素数|iframe数|WebAudio数"} 或错误描述。 */
    interface ResultListener {
        void onResult(boolean mute, String summary);
    }

    static volatile ResultListener resultListener;

    private WebViewAudioMuter() {
    }

    /** 在 WebView 所属的 UI 线程上静音 / 恢复网页音频。 */
    static void apply(WebView view, boolean mute) {
        if (view == null) {
            return;
        }
        try {
            MAIN.post(() -> evaluate(view, mute));
        } catch (Throwable ignored) {
        }
    }

    private static void evaluate(WebView view, boolean mute) {
        try {
            view.evaluateJavascript(script(mute), new ValueCallback<String>() {
                @Override
                public void onReceiveValue(String value) {
                    ResultListener listener = resultListener;
                    if (listener != null) {
                        String summary = decodeEvaluateJavascriptValue(value);
                        try {
                            listener.onResult(mute, summary);
                        } catch (Throwable ignored) {
                        }
                    }
                }
            });
        } catch (Throwable t) {
            ResultListener listener = resultListener;
            if (listener != null) {
                listener.onResult(mute, "执行失败: " + t.getClass().getSimpleName());
            }
        }
    }

    private static String decodeEvaluateJavascriptValue(String value) {
        if (value == null) {
            return "null";
        }
        String decoded = value;
        try {
            // evaluateJavascript returns a JSON-encoded value. A script string such as 1|1|0|0
            // therefore arrives here as "1|1|0|0"; parse it before result classification.
            if (value.length() >= 2 && value.charAt(0) == '"' && value.charAt(value.length() - 1) == '"') {
                decoded = new JSONArray("[" + value + "]").getString(0);
            }
        } catch (Throwable ignored) {
        }
        return trim(decoded);
    }

    private static String trim(String value) {
        return value.length() > 60 ? value.substring(0, 60) + "…" : value;
    }

    /**
     * 递归处理同源 iframe；只有被本模块标记过的元素才会被恢复，避免把网页自己静音的元素打开。
     * 返回 "元素已静音|元素数|iframe数|WebAudio数"。
     *
     * <p>静音侧是**粘性**的，分三层，全部事件驱动（没有定时器）：
     * ① 把当前元素压成静音；
     * ② 在 capture 阶段监听 volumechange/play/playing 等事件，页面一动就压回去；
     * ③ 静音期间把 {@code HTMLMediaElement.prototype} 的 volume/muted 写入口钳死、并在 {@code play()}
     * 之前先置静音——页面点「取消静音」也写不进有声状态，因此不会出现「页面写回 1 → native 吐出一小段
     * → 再被压回」的漏音窗口（网页播放器 UI 上直观表现为音量控件呈静音态）。
     * 解除静音时三层一起还原。
     */
    private static String script(boolean mute) {
        return "(function(){try{"
                + "var mute=" + (mute ? "true" : "false") + ";"
                + "var applied=0,found=0,frames=0,wa=0;"
                + "function patchAudio(win){try{"
                + "var AC=win.AudioContext||win.webkitAudioContext;"
                + "if(!AC||!AC.prototype)return;"
                + "if(!win.__arlAudioPatched){"
                + "win.__arlAudioPatched=true;"
                + "var proto=win.AudioNode?win.AudioNode.prototype:null;"
                + "if(proto&&proto.connect){"
                + "var oc=proto.connect;"
                + "proto.connect=function(target){"
                + "try{"
                + "if(target&&target.__arlDest){"
                + "if(!target.__arlGain){"
                + "var ctx=this.context||target.context;"
                + "if(ctx&&ctx.createGain){"
                + "var g=ctx.createGain();"
                + "oc.call(g,target);"
                + "g.gain.value=win.__arlMuted?0:1;"
                + "(win.__arlGains=win.__arlGains||[]).push(g);"
                + "target.__arlGain=g;"
                + "}"
                + "}"
                + "if(target.__arlGain){var args=[].slice.call(arguments);args[0]=target.__arlGain;return oc.apply(this,args);}"
                + "}"
                + "}catch(e){}"
                + "return oc.apply(this,arguments);"
                + "};"
                + "}"
                + "}"
                + "win.__arlMuted=mute;"
                + "var gs=win.__arlGains;"
                + "if(gs){for(var i=0;i<gs.length;i++){try{gs[i].gain.value=mute?0:1;}catch(e){}}}"
                + "if(!AC.prototype.__arlDestPatched){"
                + "var dp=AC.prototype,desc=null;"
                + "while(dp&&!desc){desc=Object.getOwnPropertyDescriptor(dp,'destination');dp=Object.getPrototypeOf(dp);}"
                + "if(desc&&desc.get){"
                + "var og=desc.get;"
                + "Object.defineProperty(AC.prototype,'destination',{get:function(){"
                + "var d=og.call(this);"
                + "try{if(d){d.__arlDest=true;wa++;}}catch(e){}"
                + "return d;"
                + "},configurable:true});"
                + "AC.prototype.__arlDestPatched=true;"
                + "}"
                + "}"
                + "}catch(e){}}"
                // 静音期间把 HTMLMediaElement 的 volume/muted 写入口钳死：页面（播放器 UI）无论怎么调
                // 都写不进"有声"状态，play() 之前也先置静音。仅靠 volumechange 事件压回会留下
                // 「页面已写回 1 → native 已吐出一小段 → 我们再压回去」的漏音窗口，就是那"一下声音"。
                + "function patchMedia(win){try{"
                + "var MP=win.HTMLMediaElement;if(!MP||!MP.prototype||win.__arlMediaPatched)return;"
                + "var proto=MP.prototype;"
                + "var vd=Object.getOwnPropertyDescriptor(proto,'volume');"
                + "var md=Object.getOwnPropertyDescriptor(proto,'muted');"
                + "if(!vd||!vd.set||!md||!md.set)return;"
                + "win.__arlVolDesc=vd;win.__arlMutedDesc=md;win.__arlOrigPlay=proto.play;"
                + "Object.defineProperty(proto,'volume',{configurable:true,"
                + "get:function(){return win.__arlVolDesc.get.call(this);},"
                + "set:function(v){try{win.__arlVolDesc.set.call(this,0);}catch(e){}}});"
                + "Object.defineProperty(proto,'muted',{configurable:true,"
                + "get:function(){return win.__arlMutedDesc.get.call(this);},"
                + "set:function(v){try{win.__arlMutedDesc.set.call(this,true);}catch(e){}}});"
                + "proto.play=function(){try{this.muted=true;this.volume=0;}catch(e){}"
                + "return win.__arlOrigPlay.apply(this,arguments);};"
                + "win.__arlMediaPatched=true;"
                + "}catch(e){}}"
                + "function unpatchMedia(win){try{"
                + "if(!win.__arlMediaPatched)return;"
                + "var proto=win.HTMLMediaElement.prototype;"
                + "Object.defineProperty(proto,'volume',win.__arlVolDesc);"
                + "Object.defineProperty(proto,'muted',win.__arlMutedDesc);"
                + "proto.play=win.__arlOrigPlay;"
                + "win.__arlMediaPatched=false;"
                + "}catch(e){}}"
                + "function hardMute(m){try{"
                + "if(m.muted!==true){m.muted=true;}m.defaultMuted=true;"
                + "if(m.volume!==0){m.volume=0;}"
                + "if(m.__arlGain&&m.__arlGain.gain.value!==0){m.__arlGain.gain.value=0;}"
                + "}catch(e){}}"
                + "var guarding=false;"
                + "function guard(win){"
                + "if(guarding)return;"
                + "guarding=true;"
                + "try{"
                + "var list=win.document.querySelectorAll('audio,video');"
                + "for(var i=0;i<list.length;i++){hardMute(list[i]);}"
                + "var gs=win.__arlGains;"
                + "if(gs){for(var j=0;j<gs.length;j++){try{gs[j].gain.value=0;}catch(e){}}}"
                + "}catch(e){}"
                + "guarding=false;}"
                + "function armEvents(win){"
                + "if(win.__arlEventsArmed===mute)return;"
                + "if(win.__arlHandlers){"
                + "for(var i=0;i<win.__arlHandlers.length;i++){"
                + "var h=win.__arlHandlers[i];"
                + "try{h.t.removeEventListener(h.e,h.f,true);}catch(e){}}"
                + "win.__arlHandlers=null;}"
                + "win.__arlEventsArmed=mute;"
                + "if(!mute)return;"
                + "var hs=[];"
                // 事件驱动：媒体一开始播、音量一变就压回去。不用 MutationObserver / 定时器，
                // 既避免属性变更回环，也避免在 DOM 频繁变动的页面上产生额外开销。
                // 注意 guard 要带上事件所在的那个 window：同源 iframe 各自注册监听，
                // 早先实现用了一个全局 window 变量，导致 iframe 里的事件会去压主文档。
                + "var evs=['volumechange','play','playing','loadedmetadata','canplay','ratechange'];"
                + "for(var k=0;k<evs.length;k++){"
                + "(function(e){var f=function(){guard(win);};"
                + "try{win.document.addEventListener(e,f,true);hs.push({t:win.document,e:e,f:f});}catch(x){}"
                + "})(evs[k]);}"
                + "win.__arlHandlers=hs;"
                + "}"
                + "function walk(win){try{"
                + "patchAudio(win);"
                + "if(mute){patchMedia(win);}else{unpatchMedia(win);}"
                + "armEvents(win);"
                + "var list=win.document.querySelectorAll('audio,video');"
                + "for(var i=0;i<list.length;i++){var m=list[i];"
                + "found++;"
                + "if(mute){"
                + "if(!m.__arlMuted){m.__arlMuted=true;m.__arlWasMuted=m.muted;m.__arlDefaultMuted=m.defaultMuted;m.__arlVolume=m.volume;}"
                + "hardMute(m);"
                + "if(m.muted||m.volume===0){applied++;}"
                + "}else if(m.__arlMuted){"
                + "try{m.muted=!!m.__arlWasMuted;m.defaultMuted=!!m.__arlDefaultMuted;"
                // 恢复侧走 40ms 斜坡：静音是瞬时归零（防泄露），恢复才需要平滑（消除台阶爆音）。
                + "var target=(typeof m.__arlVolume==='number')?m.__arlVolume:1;"
                + "if(target>0){m.volume=0;"
                + "var steps=8,si=0;"
                + "var t=win.setInterval(function(){si++;"
                + "try{m.volume=Math.min(target,target*si/steps);}catch(e){}"
                + "if(si>=steps){try{win.clearInterval(t);}catch(e){}}},5);"
                + "}else if(typeof m.__arlVolume==='number'){m.volume=m.__arlVolume;}"
                + "}catch(e){}"
                + "m.__arlMuted=false;}}"
                + "var fs=win.document.querySelectorAll('iframe');"
                + "for(var j=0;j<fs.length;j++){frames++;try{var w=fs[j].contentWindow;if(w)walk(w);}catch(e){}}"
                + "}catch(e){}}"
                + "walk(window);"
                + "return applied+'|'+found+'|'+frames+'|'+wa;"
                + "}catch(e){return '脚本错误:'+e.message}})()";
    }
}
