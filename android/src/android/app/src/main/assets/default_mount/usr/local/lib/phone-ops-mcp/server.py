#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""phone-ops-mcp — 把宿主侧 Shizuku/无障碍能力暴露为 stdio MCP server。

运行在 nanoMuse 的 PRoot 沙箱内（/usr/local/bin/phone-ops-mcp wrapper 拉起），
工具实现 = subprocess 调宿主 offload CLI（execve 被 proot native_offload 扩展
拦截转发到 Kotlin 宿主）：
  android-shizuku-cli   — adb shell 级（uid 2000/0）：pm/am/settings/input/dumpsys
  android-a11y-cli      — 无障碍 UI 自动化：tap/swipe/screenshot/nodes/text

零依赖（标准库 newline-delimited JSON-RPC 2.0，框架与 xinzhi-mcp.py 同款）。
日志写 stderr 之外一律不打（stdout 是 JSON-RPC 通道）。
"""
import json
import subprocess
import sys

PROTO = "2025-03-26"
SERVER_INFO = {"name": "phone-ops-mcp", "version": "0.1.0"}

SHIZUKU = "android-shizuku-cli"
A11Y = "android-a11y-cli"


def run(cmd, timeout=60):
    """跑一个 offload CLI，返回 (ok, output)。exit code 非 0 或无输出视为失败。"""
    try:
        p = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    except subprocess.TimeoutExpired:
        return False, "timeout after %ss: %s" % (timeout, " ".join(cmd))
    out = (p.stdout or "").strip()
    if p.returncode != 0:
        err = (p.stderr or "").strip() or out or "exit %d" % p.returncode
        return False, err
    return True, out or "(no output)"


def _int(args, key, default=None):
    v = args.get(key)
    if isinstance(v, bool):
        return int(v)
    if isinstance(v, (int, float)):
        return int(v)
    if isinstance(v, str) and v.isdigit():
        return int(v)
    return default


def _str(args, key, default=""):
    v = args.get(key)
    return v if isinstance(v, str) else default


TOOLS = [
    {
        "name": "phone_info",
        "description": "手机状态总览：设备信息、Shizuku 与无障碍服务是否可用、屏幕尺寸。任何操作前先调它确认能力在线。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "top_activity",
        "description": "当前前台 Activity（包名/组件）。用于确认打开 app 是否成功。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "app_list",
        "description": "列出已安装应用（第三方）。可按关键词过滤。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "filter": {"type": "string", "description": "包名子串过滤，如 douyin / weixin"},
            },
        },
    },
    {
        "name": "open_app",
        "description": "启动一个 app（默认全屏）。启动后可用 top_activity 或 wait_activity 确认。若目标 app 提供本机 MCP（如 larry-todo），优先用 MCP 工具而非 UI 操控。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "package": {"type": "string", "description": "包名，如 com.ss.android.ugc.aweme"},
            },
            "required": ["package"],
        },
    },
    {
        "name": "stop_app",
        "description": "强制停止一个 app。",
        "inputSchema": {
            "type": "object",
            "properties": {"package": {"type": "string", "description": "包名"}},
            "required": ["package"],
        },
    },
    {
        "name": "tap_xy",
        "description": "点按屏幕坐标。坐标语义与 ui_dump/screenshot 一致（像素）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "x": {"type": "integer"}, "y": {"type": "integer"},
            },
            "required": ["x", "y"],
        },
    },
    {
        "name": "tap_text",
        "description": "按可见文本点按控件（比坐标稳，优先用它）。",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string", "description": "控件上可见的文本"}},
            "required": ["text"],
        },
    },
    {
        "name": "swipe",
        "description": "从 (x1,y1) 滑到 (x2,y2)。上滑翻页：起点在下半屏、终点在上半屏。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "x1": {"type": "integer"}, "y1": {"type": "integer"},
                "x2": {"type": "integer"}, "y2": {"type": "integer"},
            },
            "required": ["x1", "y1", "x2", "y2"],
        },
    },
    {
        "name": "scroll_screen",
        "description": "整屏滚动一屏（内部在屏幕中心做方向性滑动）。direction: up/down。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "direction": {"type": "string", "description": "up=内容向上翻页（看下一条），down=反向"},
                "times": {"type": "integer", "description": "次数，默认 1"},
            },
        },
    },
    {
        "name": "key",
        "description": "按键：BACK / HOME / RECENTS / NOTIFICATIONS。",
        "inputSchema": {
            "type": "object",
            "properties": {"name": {"type": "string", "description": "BACK|HOME|RECENTS|NOTIFICATIONS"}},
            "required": ["name"],
        },
    },
    {
        "name": "type_text",
        "description": "向当前焦点输入框输入文本（中文可用；经无障碍注入）。",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "ui_dump",
        "description": "当前屏幕可交互节点树（JSON：bounds/text/id/clickable）。用于确定坐标或确认页面状态。比截图省。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "depth": {"type": "integer", "description": "最大遍历深度，默认 8"},
            },
        },
    },
    {
        "name": "ui_find",
        "description": "在当前屏幕找包含指定文本的控件（返回其 bounds/id）。",
        "inputSchema": {
            "type": "object",
            "properties": {"text": {"type": "string"}},
            "required": ["text"],
        },
    },
    {
        "name": "screenshot",
        "description": "截屏并以图片返回（默认 0.5 缩放，可调）。需要『看』页面布局/图片内容时用。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "scale": {"type": "number", "description": "缩放 0.25~1.0，默认 0.5"},
            },
        },
    },
    {
        "name": "extract_text",
        "description": "提取当前屏幕全部可见文本（纯文本，最省 token 的读屏方式）。",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "wait_activity",
        "description": "等待指定包名成为前台（默认 10s 超时）。open_app 后接它确认启动完成。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "package": {"type": "string"},
                "timeout_ms": {"type": "integer", "description": "默认 10000"},
            },
            "required": ["package"],
        },
    },
    {
        "name": "dialog_dismiss",
        "description": "检测/处理系统或 app 弹窗（确认/取消/指定按钮文本）。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "mode": {"type": "string", "description": "detect=只检测 | confirm | deny | dismiss=默认处理"},
                "button": {"type": "string", "description": "mode 需要点指定按钮时传按钮文本"},
            },
        },
    },
    {
        "name": "settings_global",
        "description": "读写系统 global 设置（Shizuku）。读传 key；写传 key+value。用于开关 freeform 支持等系统级开关。",
        "inputSchema": {
            "type": "object",
            "properties": {
                "key": {"type": "string"},
                "value": {"type": "string", "description": "不传=读，传了=写"},
            },
            "required": ["key"],
        },
    },
]


def tool_phone_info(_args):
    parts = []
    ok, out = run([SHIZUKU, "service", "status"])
    parts.append("shizuku: " + ("✓ " + out if ok else "✗ " + out))
    ok, out = run([A11Y, "service", "status"])
    parts.append("a11y: " + ("✓ " + out if ok else "✗ " + out))
    ok, out = run([SHIZUKU, "device", "info"])
    parts.append(("device: " + out) if ok else "device: ✗ " + out)
    return "\n".join(parts)


def tool_top_activity(_args):
    return run_or_fail([SHIZUKU, "activity", "top"])


def tool_app_list(args):
    flt = _str(args, "filter")
    ok, out = run([SHIZUKU, "package", "list", "--format", "json"])
    if not ok:
        return out, True
    try:
        data = json.loads(out)
        pkgs = data.get("packages") or data.get("data") or []
        if isinstance(pkgs, str):
            return out, False
        names = []
        for p in pkgs:
            name = p if isinstance(p, str) else (p.get("package") or p.get("name") or "")
            if flt and flt not in name:
                continue
            names.append(name)
        return "%d app(s)%s:\n%s" % (len(names), (" matching '%s'" % flt) if flt else "",
                                     "\n".join(sorted(names)[:200])), False
    except ValueError:
        return out, False


def tool_open_app(args):
    pkg = _str(args, "package")
    ok, out = run([SHIZUKU, "activity", "start", "-p", pkg])
    if not ok:
        return "启动失败：%s" % out, True
    return "已发出启动命令（%s）。用 top_activity / wait_activity 确认。" % pkg, False


def tool_stop_app(args):
    return run_or_fail([SHIZUKU, "activity", "force-stop", _str(args, "package")])


def tool_tap_xy(args):
    return run_or_fail([A11Y, "tap", "xy", str(_int(args, "x", 0)), str(_int(args, "y", 0))])


def tool_tap_text(args):
    return run_or_fail([A11Y, "tap", "text", _str(args, "text")])


def tool_swipe(args):
    return run_or_fail([A11Y, "gesture", "swipe",
                        str(_int(args, "x1", 0)), str(_int(args, "y1", 0)),
                        str(_int(args, "x2", 0)), str(_int(args, "y2", 0))])


def tool_scroll_screen(args):
    times = _int(args, "times", 1)
    direction = _str(args, "direction", "up")
    sub = "to-bottom" if direction == "up" else "to-top"
    return run_or_fail([A11Y, "scroll", "xy", "--direction", sub, "--times", str(times)])


def tool_key(args):
    return run_or_fail([A11Y, "input", "key", _str(args, "name", "BACK").upper()])


def tool_type_text(args):
    return run_or_fail([A11Y, "input", "text", _str(args, "text")])


def tool_ui_dump(args):
    depth = _int(args, "depth", 8)
    return run_or_fail([A11Y, "ui", "dump", "--depth", str(depth), "--visible-only"])


def tool_ui_find(args):
    return run_or_fail([A11Y, "ui", "find", "--text", _str(args, "text")])


def tool_extract_text(_args):
    return run_or_fail([A11Y, "extract", "text"])


def tool_screenshot(args):
    scale = args.get("scale", 0.5)
    try:
        scale = max(0.1, min(1.0, float(scale)))
    except (TypeError, ValueError):
        scale = 0.5
    ok, out = run([A11Y, "ui", "screenshot", "--inline", "--scale", str(scale)], timeout=90)
    if not ok:
        return out, True
    # envelope { ok, data: { encoding: "png+base64", data: ..., width, height } }
    try:
        start = out.index("{")
        env = json.loads(out[start:])
        data = env.get("data") or {}
        b64 = data.get("data") or data.get("base64")
        if b64:
            return {"images": [b64], "text": "screenshot %sx%s" % (data.get("width"), data.get("height"))}, False
        return out, False
    except ValueError:
        return out, False


def tool_wait_activity(args):
    pkg = _str(args, "package")
    timeout = _int(args, "timeout_ms", 10000)
    return run_or_fail([A11Y, "wait", "activity", "--package", pkg,
                        "--timeout", str(timeout)], timeout=timeout / 1000 + 15)


def tool_dialog(args):
    mode = _str(args, "mode", "detect")
    button = _str(args, "button")
    if mode == "detect":
        return run_or_fail([A11Y, "dialog", "detect"])
    cmd = [A11Y, "dialog", "dismiss"]
    if mode == "confirm":
        cmd.append("--confirm")
    elif mode == "deny":
        cmd.append("--deny")
    elif button:
        cmd += ["--button", button]
    return run_or_fail(cmd)


def tool_settings(args):
    key = _str(args, "key")
    value = args.get("value")
    if value is None:
        return run_or_fail([SHIZUKU, "settings", "get", "global", key])
    return run_or_fail([SHIZUKU, "settings", "set", "global", key, str(value)])


def run_or_fail(cmd, timeout=60):
    ok, out = run(cmd, timeout=timeout)
    return (out, False) if ok else (out, True)


HANDLERS = {
    "phone_info": tool_phone_info,
    "top_activity": tool_top_activity,
    "app_list": tool_app_list,
    "open_app": tool_open_app,
    "stop_app": tool_stop_app,
    "tap_xy": tool_tap_xy,
    "tap_text": tool_tap_text,
    "swipe": tool_swipe,
    "scroll_screen": tool_scroll_screen,
    "key": tool_key,
    "type_text": tool_type_text,
    "ui_dump": tool_ui_dump,
    "ui_find": tool_ui_find,
    "screenshot": tool_screenshot,
    "extract_text": tool_extract_text,
    "wait_activity": tool_wait_activity,
    "dialog_dismiss": tool_dialog,
    "settings_global": tool_settings,
}


def call_tool(name, args):
    """返回 MCP tools/call result dict。工具异常/isError 不中断会话。"""
    handler = HANDLERS.get(name)
    if not handler:
        err = {"code": -32602, "message": "unknown tool: %s" % name}
        return None, err
    try:
        ret = handler(args or {})
    except Exception as e:  # noqa: BLE001 —— 工具错误回传调用方
        return {"content": [{"type": "text", "text": "phone-ops 调用失败：%s" % e}], "isError": True}, None
    if isinstance(ret, tuple):
        payload, is_err = ret
    else:
        payload, is_err = ret, False
    if isinstance(payload, dict) and "images" in payload:
        content = [{"type": "image", "data": b64, "mimeType": "image/png"} for b64 in payload["images"]]
        content.append({"type": "text", "text": payload.get("text", "")})
        return {"content": content, "isError": is_err}, None
    return {"content": [{"type": "text", "text": str(payload)}], "isError": bool(is_err)}, None


def dispatch(method, params):
    if method == "initialize":
        # 回显客户端请求的协议版本（宽容协商）；未知/未带则回本实现基线。
        requested = (params or {}).get("protocolVersion")
        return {
            "protocolVersion": requested if isinstance(requested, str) and requested else PROTO,
            "capabilities": {"tools": {}},
            "serverInfo": SERVER_INFO,
            "instructions": "phone-ops 提供本机手机操控（经 Shizuku/无障碍）。"
                "流程建议：ui_dump/extract_text 看页面 → tap_text/swipe 操作 → wait_activity/top_activity 确认。"
                "坐标类操作优先 tap_text/ui_find；需要视觉判断才 screenshot。"
                "若目标 app 有自己的 MCP 工具（如 larry-todo），优先用那个而不是 UI 操控。",
        }
    if method == "ping":
        return {}
    if method == "tools/list":
        return {"tools": TOOLS}
    if method == "tools/call":
        result, err = call_tool((params or {}).get("name"), (params or {}).get("arguments"))
        if err:
            raise _RpcError(err["code"], err["message"])
        return result
    raise KeyError(method)


class _RpcError(Exception):
    def __init__(self, code, message):
        super().__init__(message)
        self.code = code


def main():
    for raw in sys.stdin:
        raw = raw.strip()
        if not raw:
            continue
        try:
            msg = json.loads(raw)
        except json.JSONDecodeError:
            continue
        method = msg.get("method", "")
        if method.startswith("notifications/"):
            continue
        msg_id = msg.get("id")
        try:
            result = dispatch(method, msg.get("params") or {})
            out = {"jsonrpc": "2.0", "id": msg_id, "result": result}
        except _RpcError as e:
            out = {"jsonrpc": "2.0", "id": msg_id, "error": {"code": e.code, "message": str(e)}}
        except KeyError:
            out = {"jsonrpc": "2.0", "id": msg_id,
                   "error": {"code": -32601, "message": "method not found: %s" % method}}
        except Exception as e:  # noqa: BLE001
            out = {"jsonrpc": "2.0", "id": msg_id,
                   "error": {"code": -32603, "message": str(e)}}
        sys.stdout.write(json.dumps(out, ensure_ascii=False) + "\n")
        sys.stdout.flush()


if __name__ == "__main__":
    main()
