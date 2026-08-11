package com.xjyzs.operator

import androidx.compose.runtime.mutableIntStateOf
import com.google.gson.JsonElement
import com.xjyzs.operator.utils.InputControlUtils
import com.xjyzs.operator.utils.screenshot
import androidx.compose.runtime.mutableStateOf
import io.ktor.http.ContentType
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import io.ktor.websocket.send
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration.Companion.milliseconds

object RemoteBridge {
    val commands = MutableSharedFlow<RemoteCommand>(extraBufferCapacity = 32)
    val runningState = MutableStateFlow(RunningState.STOP)
    val serverRunning = mutableStateOf(false)
    val serverUrl = mutableStateOf("")
    val port = mutableIntStateOf(31415)
}

sealed class RemoteCommand {
    data class Send(val text: String) : RemoteCommand()
    data object Stop : RemoteCommand()
    data object Clear : RemoteCommand()
}

object WebRemoteServer {
    private var engine: ApplicationEngine? = null
    private var scope: CoroutineScope? = null
    private val sessions = ConcurrentHashMap.newKeySet<io.ktor.websocket.WebSocketSession>()

    fun start(port: Int = 31415) {
        stop()
        RemoteBridge.port.value = port
        val s = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope = s
        val server = embeddedServer(Netty, port = port, host = "::") {
            install(WebSockets)
            routing {
                get("/") { call.respondText(HTML_PAGE, ContentType.Text.Html) }
                webSocket("/ws") { handleSession(this) }
            }
        }
        server.start(wait = false)
        engine = server.engine
        RemoteBridge.serverRunning.value = true
        RemoteBridge.serverUrl.value = "http://${getLocalIp()}:$port"
        s.launch { messageLoop() }
        s.launch { screenshotLoop() }
        s.launch { RemoteBridge.runningState.collect { broadcast(stateJson(it)) } }
    }

    fun stop() {
        engine?.stop(500, 1000)
        engine = null
        scope?.cancel()
        scope = null
        sessions.clear()
        RemoteBridge.serverRunning.value = false
        RemoteBridge.serverUrl.value = ""
    }

    private suspend fun handleSession(session: io.ktor.websocket.WebSocketSession) {
        sessions.add(session)
        try {
            session.send(stateJson(RemoteBridge.runningState.value))
            session.send(messagesJson())
            FloatingWindowService.captureScreenForWeb().takeIf { it.isNotEmpty() }
                ?.let { session.send(screenshotJson(it)) }
            for (frame in session.incoming) {
                if (frame is Frame.Text) handleCommand(frame.readText())
            }
        } catch (_: Exception) {
        } finally {
            sessions.remove(session)
        }
    }

    private fun broadcast(msg: String) {
        val s = scope ?: return
        for (session in sessions) {
            s.launch {
                try {
                    session.send(msg)
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun handleCommand(text: String) {
        try {
            when (JSONObject(text).getString("type")) {
                "send" -> RemoteBridge.commands.tryEmit(
                    RemoteCommand.Send(JSONObject(text).optString("text", ""))
                )

                "stop" -> RemoteBridge.commands.tryEmit(RemoteCommand.Stop)
                "clear" -> RemoteBridge.commands.tryEmit(RemoteCommand.Clear)
            }
        } catch (_: Exception) {
        }
    }

    private suspend fun messageLoop() {
        var lastSig = ""
        while (scope?.isActive == true) {
            val msgs = SharedState.msgs
            val sig = msgs.joinToString("\u0001") { "${it.role}\u0002${it.toWebText()}" }
            if (sig != lastSig) {
                lastSig = sig
                broadcast(messagesJson())
            }
            delay(150.milliseconds)
        }
    }

    private suspend fun screenshotLoop() {
        while (scope?.isActive == true) {
            val img = FloatingWindowService.captureScreenForWeb()
            if (img.isNotEmpty()) broadcast(screenshotJson(img))
            delay(800.milliseconds)
        }
    }

    private fun stateJson(state: RunningState) =
        JSONObject().put("type", "state").put("data", state.name).toString()

    private fun screenshotJson(data: String) =
        JSONObject().put("type", "screenshot").put("data", data).toString()

    private fun messagesJson(): String {
        val arr = JSONArray()
        for (msg in SharedState.msgs) {
            arr.put(JSONObject().put("role", msg.role).put("text", msg.toWebText()))
        }
        return JSONObject().put("type", "messages").put("data", arr).toString()
    }

    private fun getLocalIp(): String = try {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .flatMap { it.inetAddresses.asSequence() }
            .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }?.hostAddress ?: "localhost"
    } catch (_: Exception) {
        "localhost"
    }

    fun getAllUrls(): List<String> {
        val p = RemoteBridge.port.value
        if (p == 0) return emptyList()
        val urls = mutableListOf<String>()
        try {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .filter { !it.isLoopbackAddress }
                .forEach { addr ->
                    val host = when (addr) {
                        is Inet6Address -> {
                            if (addr.isLinkLocalAddress) return@forEach
                            "[${addr.hostAddress}]"
                        }
                        is Inet4Address -> addr.hostAddress
                        else -> return@forEach
                    }
                    urls.add("http://$host:$p")
                }
        } catch (_: Exception) {
        }
        return urls
    }
}

fun Msg.toWebText(): String {
    val c: JsonElement = content.value
    return when (role) {
        "user" -> if (c.isJsonArray) {
            c.asJsonArray.firstOrNull { it.isJsonObject && it.asJsonObject.has("text") }?.asJsonObject?.get(
                "text"
            )?.asString ?: ""
        } else try {
            c.asString
        } catch (_: Exception) {
            ""
        }

        else -> if (c.isJsonPrimitive) c.asJsonPrimitive.asString else ""
    }
}

private val HTML_PAGE = """<!DOCTYPE html>
<html lang="zh">
<head>
<meta charset="UTF-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=1,user-scalable=no">
<title>Operator Remote</title>
<style>
*{box-sizing:border-box;margin:0;padding:0}
body{font-family:system-ui,-apple-system,sans-serif;background:#f0f2f5;height:100vh;display:flex;flex-direction:column}
.hd{background:#1a1a2e;color:#fff;padding:12px 16px;display:flex;justify-content:space-between;align-items:center}
.hd-l{display:flex;align-items:center;gap:12px}
.hd h1{font-size:16px;font-weight:600}
.st{font-size:12px;padding:4px 10px;border-radius:12px;background:#3a3a5e;white-space:nowrap}
#clear{background:transparent;color:#8a8a9e;border:none;cursor:pointer;display:flex;padding:4px;border-radius:6px;transition:0.2s}
#clear:hover{background:rgba(255,255,255,0.1);color:#fff}
#clear svg{width:18px;height:18px}
.main{flex:1;display:flex;overflow:hidden}
.sw{width:45%;min-width:280px;background:#000;display:flex;align-items:center;justify-content:center;overflow:hidden}
#screen{max-width:100%;max-height:100%;object-fit:contain}
.chat{flex:1;display:flex;flex-direction:column;background:#fff;min-width:280px}
.msgs{flex:1;overflow-y:auto;padding:12px}
.msg{margin-bottom:10px;padding:8px 12px;border-radius:16px;max-width:85%;white-space:pre-wrap;word-break:break-word;font-size:14px;line-height:1.5}
.msg.user{background:#e3f2fd;margin-left:auto;border-bottom-right-radius:4px}
.msg.assistant{background:#f5f5f5;border-bottom-left-radius:4px}
.msg.system{background:#fff3e0;font-size:12px;color:#555;max-height:80px;overflow-y:auto;border-radius:8px}
.ib{display:flex;gap:10px;padding:12px 16px;border-top:1px solid #eee;align-items:center;background:#fff;padding-bottom:calc(12px + env(safe-area-inset-bottom))}
#input{flex:1;padding:10px 16px;border:1px solid #ddd;border-radius:20px;font-size:14px;outline:none;background:#f9f9f9}
#input:focus{border-color:#1a73e8;background:#fff}
.btn-icon{width:40px;height:40px;min-width:40px;border:none;border-radius:50%;display:flex;align-items:center;justify-content:center;cursor:pointer;color:#fff;transition:0.2s}
.btn-icon svg{width:20px;height:20px}
#send.send{background:#1a73e8}
#send.stop{background:#d93025}
#send.loading{background:#888;cursor:wait}
#send.takeover{background:#f9ab00}
@keyframes spin{to{transform:rotate(360deg)}}
#send.loading svg{animation:spin 1s linear infinite}
@media(max-width:700px){
  .main{flex-direction:column}
  .sw{width:100%;height:35vh;min-height:200px}
  .chat{height:65vh}
}
</style></head><body>
<div class="hd">
  <div class="hd-l">
    <h1>Operator Remote</h1>
    <button id="clear" onclick="doClear()">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><polyline points="3 6 5 6 21 6"></polyline><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"></path></svg>
    </button>
  </div>
  <span class="st" id="status">连接中</span>
</div>
<div class="main">
  <div class="sw"><img id="screen"/></div>
  <div class="chat">
    <div class="msgs" id="msgs"></div>
    <div class="ib">
      <input id="input" placeholder="输入指令..." onkeydown="if(event.key==='Enter')doSend()"/>
      <button id="send" onclick="doSend()" class="btn-icon send"></button>
    </div>
  </div>
</div>
<script>
var ICONS = {
  send: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round" stroke-linejoin="round"><line x1="12" y1="19" x2="12" y2="5"></line><polyline points="5 12 12 5 19 12"></polyline></svg>',
  stop: '<svg viewBox="0 0 24 24" fill="currentColor"><rect x="7" y="7" width="10" height="10" rx="1"></rect></svg>',
  loading: '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.5" stroke-linecap="round"><path d="M21 12a9 9 0 1 1-6.219-8.56"></path></svg>',
  takeover: '<svg viewBox="0 0 24 24" fill="currentColor"><polygon points="7 5 19 12 7 19 7 5"></polygon></svg>'
};

var ws, state = 'STOP';

function connect(){
  ws = new WebSocket('ws://'+location.host+'/ws');
  ws.onopen = function(){ document.getElementById('status').textContent='已连接'; };
  ws.onclose = function(){ document.getElementById('status').textContent='断开，重连中'; setTimeout(connect,1000); };
  ws.onmessage = function(e){
    var m = JSON.parse(e.data);
    if(m.type === 'state'){ state = m.data; updateBtn(); }
    else if(m.type === 'screenshot'){ document.getElementById('screen').src = m.data; }
    else if(m.type === 'messages'){ renderMsgs(m.data); }
  };
}

function updateBtn(){
  var b = document.getElementById('send');
  b.className = 'btn-icon';
  if(state === 'RUNNING'){ b.innerHTML = ICONS.stop; b.classList.add('stop'); }
  else if(state === 'CONNECTING'){ b.innerHTML = ICONS.loading; b.classList.add('loading'); }
  else if(state === 'TAKE_OVER'){ b.innerHTML = ICONS.takeover; b.classList.add('takeover'); }
  else { b.innerHTML = ICONS.send; b.classList.add('send'); }
}

function renderMsgs(d){
  var c = document.getElementById('msgs');
  c.innerHTML = '';
  for(var i=0; i<d.length; i++){
    var m = d[i];
    var el = document.createElement('div');
    el.className = 'msg ' + m.role;
    el.textContent = m.text;
    c.appendChild(el);
  }
  c.scrollTop = c.scrollHeight;
}

function doSend(){
  if(state === 'RUNNING' || state === 'CONNECTING'){
    ws.send(JSON.stringify({type:'stop'}));
    return;
  }
  var t = document.getElementById('input').value.trim();
  if(!t && state === 'STOP') return;
  ws.send(JSON.stringify({type:'send', text:t}));
  document.getElementById('input').value = '';
}

function doClear(){
  if(confirm("清空后，当前会话将不可恢复，确认要清空当前会话吗？")) {
    ws.send(JSON.stringify({type:'clear'}));
  }
}

updateBtn();
connect();
</script>
</body>
</html>"""
