#!/usr/bin/env python3
"""zanao offline CLI.

Signs requests in pure Python with the recovered makeSign algorithm, then
talks to api.app.zanao.com directly. No Zanao app, Frida, Java or unidbg is
required at runtime.

Config lookup order:
  1. $ZANAO_CONFIG
  2. ~/.zanao.json
  3. /sdcard/Download/Zanao/zanao-token.json
  4. /sdcard/Download/zanao-token.json

The token catcher writes the Downloads copy automatically. `zanao import-token`
copies it into ~/.zanao.json.
"""
from __future__ import annotations

import argparse
import base64
import concurrent.futures
import hashlib
import json
import os
import random
import re
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

BASE = "http://api.app.zanao.com"
VERSION = "0.6.0"
CODEBOOK = "HySuiITldDszAQ9ZCx2pkePtE0qgUr_JYn1KNjO-afc4b7M658BFGLRVWXhmovw3"
SCAN_CACHE_DIR = Path(os.environ.get("ZANAO_SCAN_CACHE", str(Path.home() / ".cache" / "zanao" / "scan")))
FAST_GENERIC = 12
FAST_PAGES = 30
THOROUGH_PAGES = 25
MAX_RESULTS = 300
SCAN_CATES = ("101", "105", "302", "108", "303", "102")
TOKEN_CANDIDATES = [
    Path.home() / ".zanao.json",
    Path("/sdcard/Download/Zanao/zanao-token.json"),
    Path("/sdcard/Download/zanao-token.json"),
]


class ZanaoError(RuntimeError):
    pass


def _config_path() -> Path:
    explicit = os.environ.get("ZANAO_CONFIG")
    if explicit:
        return Path(explicit)
    existing = [candidate for candidate in TOKEN_CANDIDATES if candidate.is_file()]
    if existing:
        return max(existing, key=lambda candidate: candidate.stat().st_mtime)
    return TOKEN_CANDIDATES[0]


def _read_config(path: Path) -> dict[str, str]:
    if not path.is_file():
        raise ZanaoError(
            f"缺少配置: {path}\n"
            "先运行 token 捕捉器，或在 Downloads/Zanao/zanao-token.json 放一份 token JSON。"
        )
    try:
        cfg = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ZanaoError(f"配置无法解析: {path}: {exc}") from exc
    if not isinstance(cfg, dict):
        raise ZanaoError(f"配置不是 JSON 对象: {path}")
    token = os.environ.get("ZANAO_TOKEN", cfg.get("token", ""))
    device = os.environ.get("ZANAO_DEVICE", cfg.get("device", ""))
    alias = os.environ.get("ZANAO_ALIAS", cfg.get("alias", "sysu"))
    if not token:
        raise ZanaoError("配置缺少 token")
    if not device:
        raise ZanaoError("配置缺少 device")
    return {"token": str(token), "device": str(device), "alias": str(alias or "sysu")}


def load_config() -> dict[str, str]:
    return _read_config(_config_path())


def _codebook_base64(data: bytes) -> str:
    out: list[str] = []
    for i in range(0, len(data), 3):
        chunk = data[i:i + 3]
        n = len(chunk)
        value = int.from_bytes(chunk + b"\0" * (3 - n), "big")
        indexes = ((value >> 18) & 63, (value >> 12) & 63, (value >> 6) & 63, value & 63)
        out.extend(CODEBOOK[index] for index in indexes[: n + 1])
        out.extend("=" * (3 - n))
    return "".join(out)


def _secret_key(randnum: str, timestamp: str) -> str:
    encoded = _codebook_base64((randnum + timestamp).encode("utf-8"))
    digest = hashlib.md5(encoded.encode("utf-8")).hexdigest()
    mixed = bytes(
        (ord(char) + ord(digest[index % len(digest)])) & 0xFF
        for index, char in enumerate(encoded)
    )
    return base64.b64encode(mixed).decode("ascii")


def sign(params: dict[str, Any]) -> str:
    items = [
        (str(key), str(value))
        for key, value in params.items()
        if value is not None and str(key) != "key"
    ]
    items.sort(key=lambda item: item[0])
    query = "&".join(f"{key}={value}" for key, value in items)
    secret = _secret_key(
        str(params.get("randnum__", "")),
        str(params.get("timestamp__", "")),
    )
    return hashlib.md5((query + "&secretkey=" + secret).encode("utf-8")).hexdigest().upper()


def unhash(value: str) -> str:
    """Decrypt an app-result payload with the app's fixed AES-CBC key."""
    compact = value.strip().replace("-", "+").replace("_", "/")
    compact += "=" * ((4 - len(compact) % 4) % 4)
    try:
        payload = base64.b64decode(compact, validate=True)
    except Exception as exc:
        raise ZanaoError("响应不是合法 base64") from exc
    try:
        from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

        key = b"6E7D41B229B5E8H4"
        iv = b"A8Y9L2Z8N6A1H3Q2"
        decryptor = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
        plain = decryptor.update(payload) + decryptor.finalize()
        pad = plain[-1]
        if 1 <= pad <= 16 and plain.endswith(bytes([pad]) * pad):
            plain = plain[:-pad]
        return plain.decode("utf-8")
    except Exception as exc:
        raise ZanaoError(f"响应解密失败: {exc}") from exc


def _split_query(path: str, data: dict[str, Any]) -> tuple[str, dict[str, Any]]:
    """Merge a query string into data so it is included in the signature."""
    if "?" not in path:
        return path, data
    base, query = path.split("?", 1)
    merged = dict(data)
    for key, values in urllib.parse.parse_qs(query, keep_blank_values=True).items():
        if key not in merged:
            merged[key] = values[-1] if values else ""
    return base, merged


def request(method: str, path: str, data: dict[str, Any] | None = None) -> tuple[int, str]:
    cfg = load_config()
    path, data = _split_query(path, data or {})
    data = {str(k): str(v) for k, v in data.items()}
    timestamp = str(int(time.time()))
    randnum = "".join(str(random.randrange(10)) for _ in range(20))
    sign_params = dict(data)
    sign_params["timestamp__"] = timestamp
    sign_params["randnum__"] = randnum
    signature = sign(sign_params)

    body = urllib.parse.urlencode(data)
    url = BASE + path
    if method.upper() == "GET":
        if body:
            url += ("&" if "?" in url else "?") + body
        req = urllib.request.Request(url)
    else:
        req = urllib.request.Request(url, data=body.encode("utf-8"))

    headers = {
        "X-Requested-With": "XMLHttpRequest",
        "X-Sc-Alias": cfg["alias"],
        "X-Sc-Token": cfg["token"],
        "X-Sc-Device": cfg["device"],
        "X-Sc-Client": "app",
        "X-Sc-Platform": "Android",
        "X-Sc-Hb-V": "210",
        "X-Sc-Nt-V": "310",
        "X-Sc-Version": "3.1.0",
        "X-Sc-Nd": randnum,
        "X-Sc-Td": timestamp,
        "X-Sc-Ah": signature,
        "Content-Type": "application/x-www-form-urlencoded",
    }
    for key, value in headers.items():
        req.add_header(key, value)

    try:
        with urllib.request.urlopen(req, timeout=20) as response:
            code = response.status
            text = response.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as exc:
        code = exc.code
        text = exc.read().decode("utf-8", "replace")
    except OSError as exc:
        raise ZanaoError(f"请求失败: {exc}") from exc

    if not text.lstrip().startswith("{"):
        text = unhash(text)
    return code, text


def api(method: str, path: str, data: dict[str, Any] | None = None) -> Any:
    code, text = request(method, path, data)
    try:
        envelope = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ZanaoError(f"接口返回非 JSON (HTTP {code}): {text[:160]}") from exc
    if envelope.get("errno") != 0:
        raise ZanaoError(
            f"errno={envelope.get('errno')} {envelope.get('errmsg', '')}".strip()
        )
    return envelope.get("data")


def print_json(value: Any) -> None:
    print(json.dumps(value, ensure_ascii=False, indent=2))


def _list_of(data: Any, key: str = "list") -> list[dict[str, Any]]:
    if isinstance(data, dict):
        value = data.get(key)
        if isinstance(value, list):
            return [item for item in value if isinstance(item, dict)]
    if isinstance(data, list):
        return [item for item in data if isinstance(item, dict)]
    return []


def _thread_row(t: dict[str, Any]) -> dict[str, Any]:
    return {
        "thread_id": t.get("thread_id"),
        "title": t.get("title"),
        "content": t.get("content"),
        "cate_id": t.get("cate_id"),
        "cate_name": t.get("cate_name"),
        "post_time": t.get("post_time"),
        "l_count": t.get("l_count"),
        "c_count": t.get("c_count"),
        "view_count": t.get("view_count"),
        "nickname": t.get("nickname"),
        "is_anon": t.get("is_anon"),
        "img_paths": t.get("img_paths") or [],
    }


def _print_threads(rows: list[dict[str, Any]], title: str = "") -> None:
    if title:
        print(title)
    print(f"{'ID':<12}{'分区':<10}{'时间':<10}{'赞':<5}{'评':<5}标题")
    for t in rows:
        print(f"{str(t.get('thread_id', '')):<12}{str(t.get('cate_name') or ''):<10}"
              f"{str(t.get('post_time') or ''):<10}{str(t.get('l_count', '')):<5}"
              f"{str(t.get('c_count', '')):<5}{t.get('title', '')}")
    print(f"\n共 {len(rows)} 条")


def _print_comments(rows: list[dict[str, Any]]) -> None:
    for c in rows:
        who = c.get("nickname") or ("未署名" if c.get("is_anon") else "?")
        print(f"[{c.get('comment_id')}] {who} · {c.get('post_time', '')}")
        print(f"  {c.get('content', '')}")
        print(f"  赞 {c.get('like_num', 0)}  回复 {c.get('reply_num', 0)}")
    print(f"\n共 {len(rows)} 条评论")


# ───────────────────────────── 作者资料 / 历史检索 ─────────────────────────────
# 移植自 LSPosed 模块 com.whywood.zsign：
#   IdentityClient.whoInfo  -> /user/usershow 查询帖子/评论的发布者资料
#   HistoryScanner          -> 关键词检索 + 作者比对 + 板块回溯

STOPWORDS = set(
    "的 了 在 是 我 你 他 她 它 这 那 有 和 就 不 都 一 也 吗 吧 呢 啊 呀 嘛 "
    "一个 什么 怎么 自己 感觉 现在 但是 因为 所以 就是 还是 可以 没有 我们 你们 "
    "他们 这个 那个 知道 觉得 真的 有点 一点 时候 什么的 之类 而且 然后 如果 已经 "
    "一直 好像 可能 应该 不要 不是 不能 这样 那样 一些 多少".split()
)

# 模块 assets/zsign-keywords.txt：语料覆盖率高、搜索端可用的候选词
GENERIC_KEYWORDS = [
    "有", "一", "校", "不", "中", "是", "可", "出", "人", "个", "在", "没", "以",
    "想", "大", "好", "学", "到", "上", "要", "下", "不好", "有人", "有无", "怎么",
    "蹲蹲", "本人", "现在", "最好", "喜欢", "如果", "觉得", "可以", "因为", "一下",
    "知道", "好像", "可能", "到了", "有没", "平时", "求", "问", "谢谢", "请问",
    "大家", "同学", "求助",
]


def _parse_ts(value: Any) -> int:
    """接口时间字段是秒级字符串；返回毫秒，解析失败返回 0。"""
    text = str(value or "").strip()
    if not text.isdigit():
        return 0
    return int(text) * 1000


def avatar_key(url: str) -> str:
    """从头像 URL 抽稳定片段：.../NAME.jpg@xxx -> NAME。"""
    if not url:
        return ""
    tail = url.rsplit("/", 1)[-1]
    tail = tail.split("@", 1)[0]
    tail = tail.split("?", 1)[0]
    if "." in tail:
        tail = tail.rsplit(".", 1)[0]
    return tail


def usershow(thread_id: Any = None, comment_id: Any = None) -> dict[str, Any]:
    """查询帖子/评论的发布者资料。"""
    payload: dict[str, str] = {}
    if str(comment_id or "") not in ("", "0", "None"):
        payload["comment_id"] = str(comment_id)
    elif str(thread_id or "") not in ("", "0", "None"):
        payload["thread_id"] = str(thread_id)
    else:
        raise ZanaoError("需要 thread_id 或 comment_id")
    try:
        data = api("POST", "/user/usershow", payload)
    except ZanaoError:
        data = api("GET", "/user/usershow", payload)
    info = data.get("user_info") if isinstance(data, dict) else None
    if not isinstance(info, dict):
        if isinstance(data, dict) and data.get("nickname"):
            return data
        raise ZanaoError("接口未返回 user_info（帖子可能已删除或不可见）")
    return info


class ScanState:
    """一次历史检索的运行状态（线程安全）。"""

    def __init__(self, target: str, avatar: str, days: int, full: bool, limit: int) -> None:
        self.target = target
        self.avatar = avatar
        self.full = full
        self.limit = limit
        self.until = (int(time.time()) - days * 86400) * 1000 if days > 0 else 0
        self.seed_cate = ""
        self.keywords: list[str] = []
        self.seen: set[str] = set()
        self.results: list[dict[str, Any]] = []
        self.scanned = 0
        self.oldest = 0
        self.state = "running"
        self.lock = threading.Lock()
        self.who_memo: dict[str, dict[str, Any] | None] = {}

    def add(self, row: dict[str, Any]) -> None:
        tid = str(row.get("thread_id") or "")
        if not tid:
            return
        with self.lock:
            if len(self.results) >= self.limit:
                return
            if any(item["tid"] == tid for item in self.results):
                return
            self.results.append({
                "tid": tid,
                "title": row.get("title", ""),
                "time": row.get("p_time", ""),
                "cate": row.get("cate_name", ""),
                "anon": bool(row.get("is_anon")),
            })
        print(f"\r\n[hit] {tid} {row.get('title', '')}", file=sys.stderr, flush=True)

    def hit(self, info: dict[str, Any] | None) -> bool:
        if not isinstance(info, dict):
            return False
        nick = str(info.get("nickname") or "")
        head = str(info.get("headimgurl") or "")
        if self.target and nick == self.target:
            return True
        return bool(self.avatar) and self.avatar in head

    def who(self, tid: str) -> dict[str, Any] | None:
        with self.lock:
            if tid in self.who_memo:
                return self.who_memo[tid]
        try:
            info = usershow(thread_id=tid)
        except ZanaoError:
            info = None
        with self.lock:
            self.who_memo[tid] = info
        return info


def extract_keywords(title: str | None, content: str | None) -> list[str]:
    """种子帖标题/正文提词：标题权重 3、正文权重 1，按分数排序后去包含项。"""
    scores: dict[str, int] = {}

    def add(word: str, weight: int) -> None:
        word = word.strip()
        if len(word) < 2 or word in STOPWORDS or word.isdigit():
            return
        scores[word] = scores.get(word, 0) + weight

    def collect(text: str | None, weight: int) -> None:
        if not text:
            return
        for part in re.split(r"[^\u4e00-\u9fa5A-Za-z0-9]+", str(text)):
            part = part.strip()
            if len(part) < 2:
                continue
            if len(part) <= 8:
                add(part, weight)
            else:
                for i in range(0, len(part) - 3, 2):
                    add(part[i:i + 4], weight)

    collect(title, 3)
    collect(content, 1)
    ordered = sorted(scores, key=lambda w: (-scores[w], -len(w)))
    chosen: list[str] = []
    for word in ordered:
        if len(chosen) >= 6:
            break
        if any(word in item or item in word for item in chosen):
            continue
        chosen.append(word)
    return chosen


def merge_keywords(state: ScanState, full: bool) -> None:
    want = len(GENERIC_KEYWORDS) if full else FAST_GENERIC
    for word in GENERIC_KEYWORDS:
        if len(state.keywords) >= want:
            break
        if any(word in item or item in word for item in state.keywords):
            continue
        state.keywords.append(word)


def _scan_rows(state: ScanState, rows: list[dict[str, Any]], pool: concurrent.futures.ThreadPoolExecutor) -> None:
    for row in rows:
        tid = str(row.get("thread_id") or "")
        if not tid:
            continue
        with state.lock:
            if tid in state.seen:
                continue
            state.seen.add(tid)
            state.scanned += 1
        stamp = _parse_ts(row.get("p_time"))
        if state.until and stamp and stamp < state.until:
            continue
        if stamp and (state.oldest == 0 or stamp < state.oldest):
            state.oldest = stamp
        if not row.get("is_anon"):
            if state.target and row.get("nickname") == state.target:
                state.add(row)
        else:
            pool.submit(_scan_who, state, row)


def _scan_who(state: ScanState, row: dict[str, Any]) -> None:
    if state.state != "running":
        return
    if state.hit(state.who(str(row.get("thread_id")))):
        state.add(row)


def _scan_search(state: ScanState, keyword: str, pages: int,
                 search_pool: concurrent.futures.ThreadPoolExecutor,
                 who_pool: concurrent.futures.ThreadPoolExecutor) -> None:
    for page in range(1, pages + 1):
        if state.state != "running":
            return
        payload: dict[str, str] = {
            "wd": keyword,
            "cur_page": str(page),
            "cate_id": "20",
            "range": "2y",
        }
        if state.seed_cate:
            payload["thread_cate_id"] = state.seed_cate
        data = None
        for _ in range(2):
            try:
                data = api("POST", "/thread/v2/search", payload)
                break
            except ZanaoError:
                time.sleep(0.6)
        if data is None:
            return
        rows = _list_of(data)
        if not rows:
            return
        before = len(state.results)
        _scan_rows(state, rows, who_pool)
        if len(state.results) == before and page > 3 and len(rows) < 3:
            return


def _scan_walk(state: ScanState, who_pool: concurrent.futures.ThreadPoolExecutor) -> None:
    order: list[str] = [state.seed_cate] if state.seed_cate else []
    order += [cate for cate in SCAN_CATES if cate not in order]
    for cate in order:
        from_time = str(int(time.time()))
        for _ in range(4000):
            if state.state != "running":
                return
            try:
                rows = _list_of(api("GET", "/thread/v2/list",
                                    {"from_time": from_time, "cate_id": cate}))
            except ZanaoError:
                break
            if not rows:
                break
            first = _parse_ts(rows[0].get("p_time"))
            if state.until and first and first < state.until:
                return
            last = str(rows[-1].get("p_time") or "")
            if not last or last == from_time:
                break
            _scan_rows(state, rows, who_pool)
            from_time = last


def _scan_cache_path(target: str, avatar: str) -> Path:
    key = hashlib.sha1((target + "|" + avatar).encode("utf-8")).hexdigest()[:16]
    return SCAN_CACHE_DIR / f"{key}.json"


def _load_scan_cache(path: Path) -> list[dict[str, Any]] | None:
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return None
    if time.time() * 1000 - int(data.get("ts", 0)) > 86400000:
        return None
    results = data.get("results")
    return [item for item in results if isinstance(item, dict)] if isinstance(results, list) else None


def cmd_scan(args: argparse.Namespace) -> int:
    target = (args.nickname or "").strip()
    avatar = avatar_key(args.avatar or "")
    if not target and not avatar:
        raise ZanaoError("至少需要 --nickname 或 --avatar 其中之一")

    state = ScanState(target, avatar, args.days, args.full, max(1, args.limit))
    cache_path = _scan_cache_path(target, avatar)
    cached = None if args.no_cache else _load_scan_cache(cache_path)
    if cached is not None:
        state.results = cached
        if not args.json:
            print(f"缓存命中 {len(cached)} 条（24h 内，--no-cache 强制重扫）", file=sys.stderr)
        if args.cache_only:
            if args.json:
                print_json({"target": target, "avatar": avatar, "state": "cached",
                            "count": len(cached), "results": cached})
            else:
                for item in cached:
                    print(f"{item.get('tid', ''):<12}{str(item.get('time', '')):<12}"
                          f"{str(item.get('cate') or ''):<12}{item.get('title', '')}")
                print(f"\n缓存 {len(cached)} 条")
            return 0

    if args.seed:
        detail: dict[str, Any] = {}
        try:
            raw = api("GET", "/thread/info", {"id": str(args.seed), "from": "post"})
            if isinstance(raw, dict):
                detail = raw.get("detail") or {}
            if isinstance(detail, list):
                detail = next((item for item in detail if isinstance(item, dict)), {})
            if not isinstance(detail, dict):
                detail = {}
        except ZanaoError:
            detail = {}
        state.seed_cate = str(detail.get("cate_id") or "")
        state.keywords = extract_keywords(detail.get("title"), detail.get("content"))
        if not state.keywords and not args.json:
            print("种子帖不可用，仅使用通用词", file=sys.stderr)
    merge_keywords(state, args.full or bool(args.walk_only))

    if not args.json:
        mode = "walk" if args.walk_only else ("full" if args.full else "fast")
        print(f"目标 {target or '-'} 头像片段 {avatar or '-'} 关键词 {len(state.keywords)} 模式 {mode}",
              file=sys.stderr)

    stop_progress = threading.Event()

    def progress() -> None:
        while not stop_progress.wait(2.0):
            print(f"\r扫描 {state.scanned} 条 / 命中 {len(state.results)}", end="", file=sys.stderr, flush=True)

    reporter = threading.Thread(target=progress, daemon=True)
    if not args.json:
        reporter.start()

    try:
        with concurrent.futures.ThreadPoolExecutor(max_workers=max(1, args.workers)) as search_pool, \
             concurrent.futures.ThreadPoolExecutor(max_workers=8) as who_pool:
            if not args.walk_only:
                futures = [
                    search_pool.submit(_scan_search, state, keyword,
                                       THOROUGH_PAGES if args.full else FAST_PAGES,
                                       search_pool, who_pool)
                    for keyword in state.keywords
                ]
                for future in futures:
                    future.result()
            if args.full or args.walk_only:
                _scan_walk(state, who_pool)
    except KeyboardInterrupt:
        state.state = "stopped"
        raise
    finally:
        stop_progress.set()
        if not args.json:
            print(file=sys.stderr)
            reporter.join(timeout=1)

    if state.state == "running":
        state.state = "done"
    try:
        SCAN_CACHE_DIR.mkdir(parents=True, exist_ok=True)
        cache_path.write_text(json.dumps({
            "ts": int(time.time() * 1000),
            "scanned": state.scanned,
            "depth": state.oldest,
            "results": state.results,
        }, ensure_ascii=False), encoding="utf-8")
    except OSError:
        pass

    if args.json:
        print_json({
            "target": target, "avatar": avatar, "state": state.state,
            "scanned": state.scanned, "count": len(state.results),
            "keywords": state.keywords, "results": state.results,
        })
    else:
        print(f"{'ID':<12}{'时间':<12}{'分区':<12}标题")
        for item in state.results:
            print(f"{item['tid']:<12}{str(item['time']):<12}{str(item['cate'] or ''):<12}{item['title']}")
        print(f"\n扫描 {state.scanned} 条，命中 {len(state.results)} 条")
    return 0


def cmd_toplist(args: argparse.Namespace) -> int:
    data = api("GET", "/thread/toplist", {})
    rows = [_thread_row(t) for t in _list_of(data)]
    if args.json:
        print_json({"count": len(rows), "threads": rows})
    else:
        _print_threads(rows, "排行榜")
    return 0


def cmd_likelist(args: argparse.Namespace) -> int:
    data = api("POST", "/thread/likelist", {"id": str(args.thread_id)})
    rows = _list_of(data)
    if args.json:
        print_json({"thread_id": args.thread_id, "count": len(rows), "users": rows})
        return 0
    for user in rows:
        print(f"{user.get('nickname') or user.get('user_name') or '?'}  {user.get('user_level_title', '')}")
    print(f"\n共 {len(rows)} 人点赞")
    return 0


def cmd_tags(args: argparse.Namespace) -> int:
    data = api("POST", "/mx/tag/v1/hot", {})
    rows = _list_of(data)
    if args.json:
        print_json(data)
    else:
        for tag in rows:
            print(f"[{tag.get('tag_id')}] {tag.get('tag_name') or tag.get('name')}  {tag.get('thread_count', '')}")
        if not rows:
            print_json(data)
    return 0


def cmd_ping(args: argparse.Namespace) -> int:
    data = api("GET", "/thread/v2/list", {
        "cate_id": "latest", "from_time": "0",
        "with_comment": "false", "with_reply": "false",
    })
    threads = _list_of(data)
    result = {"ok": True, "threads": len(threads), "version": VERSION}
    if args.json:
        print_json(result)
    else:
        print(f"OK threads={len(threads)} version={VERSION}")
    return 0


def cmd_list(args: argparse.Namespace) -> int:
    data = api("GET", "/thread/v2/list", {
        "cate_id": args.cate,
        "from_time": str(args.from_time),
        "with_comment": "true",
        "with_reply": "true",
    })
    rows = [_thread_row(t) for t in _list_of(data)[: max(0, args.n)]]
    if args.json:
        print_json({"cate": args.cate, "count": len(rows), "threads": rows})
    else:
        _print_threads(rows, f"分类：{args.cate}")
    return 0


def cmd_hot(args: argparse.Namespace) -> int:
    data = api("POST", "/thread/hot", {"count": str(args.count), "type": str(args.type)})
    rows = [_thread_row(t) for t in _list_of(data)]
    if args.json:
        print_json({"hot_type": args.type, "count": len(rows), "threads": rows})
    else:
        _print_threads(rows, "热门帖子")
    return 0


def cmd_search(args: argparse.Namespace) -> int:
    payload: dict[str, Any] = {
        "wd": args.keyword,
        "cur_page": str(args.page),
        "cate_id": "10",
    }
    if args.range:
        payload["cate_id"] = "20"
        payload["range"] = args.range
    data = api("POST", "/thread/v2/search", payload)
    rows = [_thread_row(t) for t in _list_of(data)[: max(0, args.n)]]
    if args.json:
        print_json({"keyword": args.keyword, "count": len(rows), "threads": rows})
    else:
        _print_threads(rows, f"搜索：{args.keyword}")
    return 0


def cmd_info(args: argparse.Namespace) -> int:
    data = api("GET", "/thread/info", {"id": str(args.thread_id), "from": "post"})
    if args.json:
        print_json(data)
        return 0
    detail = data.get("detail", data) if isinstance(data, dict) else data
    if not isinstance(detail, dict):
        print_json(data)
        return 0
    fields = ("thread_id", "title", "content", "is_anon", "nickname",
              "cate_name", "post_time", "pt_time", "l_count", "c_count",
              "view_count", "check_status", "status", "img_paths")
    for key in fields:
        if key in detail:
            print(f"{key:<14} {detail[key]}")
    return 0


def _resolve_cate(raw: str) -> tuple[str, str]:
    cate = str(raw).strip()
    if cate.isdigit():
        return cate, ""
    data = api("GET", "/catelist", {"from": "post", "is_cross": "0", "cross_all": "1"})
    for item in _list_of(data, "cate_list"):
        if str(item.get("name", "")).strip() == cate:
            return str(item.get("cate_id")), cate
    raise ZanaoError(f"找不到分类: {cate}；先运行 categories 查看分类")


def cmd_post(args: argparse.Namespace) -> int:
    cate, cate_name = _resolve_cate(args.cate)
    payload = {
        "cate_id": cate,
        "contact_person": args.contact_person,
        "contact_phone": args.contact_phone,
        "contact_qq": args.contact_qq,
        "contact_wx": args.contact_wx,
        "title": args.title,
        "content": args.content,
        "img_paths": args.images,
        "cert_show": "0",
        "is_comment_close": "1" if args.no_comments else "0",
        "use_anon": "0" if args.no_anon else "1",
        "reward_price": "0",
        "top_price": "0",
        "post_price": "0",
        "color_price": "0",
    }
    if not args.yes:
        if args.json:
            print_json({"dry_run": True, "cate_id": cate, "cate_name": cate_name,
                        "would_post": payload})
        else:
            print(f"分类：{cate_name or cate} ({cate})")
            print("dry-run：未发布。加 --yes 才会真正发帖。")
        return 0
    data = api("POST", "/thread/post", payload)
    if args.json:
        print_json({"ok": True, "response": data})
    else:
        print("已发布 thread_id =", data.get("thread_id") if isinstance(data, dict) else None)
    return 0


def cmd_comments(args: argparse.Namespace) -> int:
    data = api("POST", "/comment/list", {"id": str(args.thread_id)})
    rows = _list_of(data)
    if args.json:
        print_json({"thread_id": args.thread_id, "count": len(rows), "comments": rows})
    else:
        _print_comments(rows)
    return 0


def cmd_comment(args: argparse.Namespace) -> int:
    payload = {
        "id": str(args.thread_id),
        "content": args.content,
        "reply_comment_id": str(args.reply),
        "root_comment_id": str(args.root),
        "use_anon": "1" if args.anon else "0",
        "from": "detail",
    }
    if not args.yes:
        if args.json:
            print_json({"dry_run": True, "would_comment": payload})
        else:
            print("dry-run：未评论。加 --yes 才会真正发表评论。")
        return 0
    data = api("POST", "/comment/post", payload)
    if args.json:
        print_json({"ok": True, "response": data})
    else:
        print("评论已发布", data if data else "")
    return 0


def _like(args: argparse.Namespace, action: int) -> int:
    payload = {"id": str(args.thread_id), "comment_id": "0", "action": str(action)}
    if not args.yes:
        if args.json:
            print_json({"dry_run": True, "would_like": payload})
        else:
            print("dry-run：加 --yes 才会提交点赞/取消点赞。")
        return 0
    api("POST", "/thread/like", payload)
    if args.json:
        print_json({"ok": True, "thread_id": args.thread_id, "action": action})
    else:
        print("已点赞" if action else "已取消点赞")
    return 0


def cmd_like(args: argparse.Namespace) -> int:
    return _like(args, 1)


def cmd_unlike(args: argparse.Namespace) -> int:
    return _like(args, 0)


def _like_comment(args: argparse.Namespace, action: int) -> int:
    payload = {
        "id": str(args.thread_id),
        "comment_id": str(args.comment_id),
        "action": str(action),
    }
    if not args.yes:
        if args.json:
            print_json({"dry_run": True, "would_like_comment": payload})
        else:
            print("dry-run：加 --yes 才会提交评论点赞/取消。")
        return 0
    api("POST", "/comment/like", payload)
    if args.json:
        print_json({"ok": True, "thread_id": args.thread_id,
                    "comment_id": args.comment_id, "action": action})
    else:
        print("已点赞评论" if action else "已取消评论点赞")
    return 0


def cmd_like_comment(args: argparse.Namespace) -> int:
    return _like_comment(args, 1)


def cmd_unlike_comment(args: argparse.Namespace) -> int:
    return _like_comment(args, 0)


def cmd_categories(args: argparse.Namespace) -> int:
    data = api("POST", "/catelist", {"from": "post", "is_cross": "0", "cross_all": "1"})
    rows = _list_of(data, "cate_list")
    if args.json:
        print_json({"count": len(rows), "categories": rows})
    else:
        for c in rows:
            print(f"[{c.get('cate_id')}] {c.get('name')}  {c.get('summary', '')}")
        print(f"\n共 {len(rows)} 个分类")
    return 0


def cmd_messages(args: argparse.Namespace) -> int:
    data = api("POST", "/msg/list", {"from_time": "0"})
    rows = _list_of(data)[: max(0, args.n)]
    if args.json:
        print_json({"count": len(rows), "messages": rows})
        return 0
    for m in rows:
        print(f"[{m.get('msg_id')}] {m.get('msg_title', '')}  {m.get('create_time_text', '')}")
        comment = (m.get("from_comment_info") or {}).get("content") if isinstance(m.get("from_comment_info"), dict) else ""
        thread = (m.get("thread_info") or {}).get("title") if isinstance(m.get("thread_info"), dict) else ""
        text = comment or thread or m.get("msg_content") or ""
        if text:
            print(f"  {text}")
        if m.get("thread_id"):
            print(f"  thread_id={m.get('thread_id')} comment_id={m.get('comment_id')}")
    print(f"\n共 {len(rows)} 条消息")
    return 0


def cmd_me(args: argparse.Namespace) -> int:
    data = api("POST", "/user/info", {"from": "mine"})
    if args.json:
        print_json(data)
        return 0
    if not isinstance(data, dict):
        print_json(data)
        return 0
    info = data.get("user_info") if isinstance(data.get("user_info"), dict) else data
    school = data.get("school_name")
    if school:
        print(f"{'school_name':<16} {school}")
    for key in ("nickname", "user_id", "user_level", "user_level_title",
                "user_exp", "user_today_exp", "reg_days", "cert_status",
                "thread_count", "comment_count", "follow_count", "fans_count"):
        if key in info:
            print(f"{key:<16} {info[key]}")
    return 0


def cmd_profile(args: argparse.Namespace) -> int:
    items: list[tuple[str, str]] = []
    if args.thread:
        items.append(("tid", str(args.thread)))
    if args.tid:
        items.append(("tid", str(args.tid)))
    if args.comment:
        items.append(("cid", str(args.comment)))
    if args.cid:
        items.append(("cid", str(args.cid)))
    if args.file:
        for line in Path(args.file).read_text(encoding="utf-8").splitlines():
            line = line.strip()
            if not line or line.startswith("#"):
                continue
            items.append(("cid", line[4:]) if line.startswith("cid:") else ("tid", line))
    if not items:
        raise ZanaoError("需要 --thread/--tid 或 --comment/--cid")

    rows: list[dict[str, Any]] = []
    for kind, value in items:
        try:
            info = usershow(thread_id=value if kind == "tid" else None,
                            comment_id=value if kind == "cid" else None)
            rows.append({
                "id": value, "kind": kind, "ok": True,
                "nickname": info.get("nickname", ""),
                "level": info.get("user_level", ""),
                "level_title": info.get("user_level_title", ""),
                "reg_days": info.get("reg_days", ""),
                "school": info.get("school_name", ""),
                "headimgurl": info.get("headimgurl", ""),
            })
        except ZanaoError as exc:
            rows.append({"id": value, "kind": kind, "ok": False, "error": str(exc)})

    if args.raw:
        for row in rows:
            if row["ok"]:
                print_json(usershow(thread_id=row["id"] if row["kind"] == "tid" else None,
                                    comment_id=row["id"] if row["kind"] == "cid" else None))
            else:
                print_json({"ok": False, "id": row["id"], "error": row["error"]})
        return 0 if all(row["ok"] for row in rows) else 1
    if args.json:
        print_json({"count": len(rows), "users": rows})
        return 0 if all(row["ok"] for row in rows) else 1

    for row in rows:
        if not row["ok"]:
            print(f"[{row['kind']}:{row['id']}] 查询失败：{row['error']}", file=sys.stderr)
            continue
        if len(rows) == 1:
            print(f"{'nickname':<12} {row['nickname']}")
            print(f"{'level':<12} {row['level']} {row['level_title']}")
            print(f"{'reg_days':<12} {row['reg_days']}")
            print(f"{'school':<12} {row['school']}")
            print(f"{'headimgurl':<12} {row['headimgurl']}")
        else:
            print(f"{row['kind']}:{row['id']}\t{row['nickname']}\t{row['level']}\t{row['reg_days']}\t{row['school']}")
    return 0 if all(row["ok"] for row in rows) else 1


def cmd_change(args: argparse.Namespace) -> int:
    payload = {"id": str(args.thread_id), "act": args.act}
    if not args.yes:
        if args.json:
            print_json({"dry_run": True, "would_change": payload})
        else:
            print("dry-run：加 --yes 才会修改帖子状态。")
        return 0
    api("POST", "/thread/change", payload)
    if args.json:
        print_json({"ok": True, "thread_id": args.thread_id, "act": args.act})
    else:
        print("帖子状态已修改")
    return 0


def cmd_delete_comment(args: argparse.Namespace) -> int:
    payload = {"id": str(args.thread_id), "comment_id": str(args.comment_id)}
    if not args.yes:
        if args.json:
            print_json({"dry_run": True, "would_delete": payload})
        else:
            print("dry-run：加 --yes 才会删除评论。")
        return 0
    api("POST", "/comment/delete", payload)
    if args.json:
        print_json({"ok": True, **payload})
    else:
        print("评论已删除")
    return 0


def cmd_import_token(args: argparse.Namespace) -> int:
    source = Path(args.file) if args.file else None
    if source is None:
        for candidate in TOKEN_CANDIDATES[1:]:
            if candidate.is_file():
                source = candidate
                break
    if source is None:
        raise ZanaoError("找不到 token JSON；请先在捕捉器里捕捉")
    try:
        cfg = json.loads(source.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ZanaoError(f"读取 token JSON 失败: {source}: {exc}") from exc
    if not isinstance(cfg, dict) or not cfg.get("token") or not cfg.get("device"):
        raise ZanaoError("token JSON 缺少 token 或 device")
    target = Path.home() / ".zanao.json"
    try:
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(json.dumps(cfg, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
        target.chmod(0o600)
    except OSError as exc:
        raise ZanaoError(f"写入配置失败: {target}: {exc}") from exc
    if args.json:
        print_json({"ok": True, "source": str(source), "target": str(target)})
    else:
        print(f"已导入 {source} -> {target}")
    return 0


def cmd_raw(args: argparse.Namespace) -> int:
    try:
        data = json.loads(args.data) if args.data else {}
    except json.JSONDecodeError as exc:
        raise ZanaoError(f"--data 不是合法 JSON: {exc}") from exc
    code, text = request(args.method, args.path, data)
    if args.json:
        try:
            parsed = json.loads(text)
        except json.JSONDecodeError:
            parsed = text
        print_json({"code": code, "body": parsed})
    else:
        print(code)
        print(text)
    return 0


def _add_write(parser: argparse.ArgumentParser) -> None:
    parser.add_argument("--yes", action="store_true", help="确认提交写操作")


def build_parser() -> argparse.ArgumentParser:
    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--json", action="store_true", help="输出机器可读 JSON")
    parser = argparse.ArgumentParser(prog="zanao", description="赞噢校园集市离线 CLI")
    parser.add_argument("--version", action="version", version=VERSION)
    sub = parser.add_subparsers(dest="command", required=True)

    p = sub.add_parser("scan", parents=[common], help="批量历史检索：多关键词翻页 + 作者比对 + 板块回溯")
    p.add_argument("--nickname", "-n", default="", help="目标昵称（精确匹配）")
    p.add_argument("--avatar", default="", help="头像 URL 或片段，用于改名/换头像场景的二次匹配")
    p.add_argument("--seed", default="", help="种子帖子 ID：取标题/正文提词并锁定分区")
    p.add_argument("--days", type=int, default=0, help="只扫最近 N 天，0=不限")
    p.add_argument("--full", action="store_true", help="full 模式：全部关键词 + 板块回溯")
    p.add_argument("--walk-only", action="store_true", help="跳过关键词，直接板块回溯")
    p.add_argument("--workers", type=int, default=4, help="关键词并发数")
    p.add_argument("--limit", type=int, default=MAX_RESULTS, help="最多返回条数")
    p.add_argument("--no-cache", action="store_true", help="忽略 24h 缓存")
    p.add_argument("--cache-only", action="store_true", help="只读 24h 缓存，不发起扫描")
    p.set_defaults(func=cmd_scan)

    p = sub.add_parser("ping", parents=[common], help="检查离线签名和接口")
    p.set_defaults(func=cmd_ping)

    p = sub.add_parser("list", parents=[common], help="帖子列表")
    p.add_argument("--cate", default="latest")
    p.add_argument("--n", type=int, default=10)
    p.add_argument("--from-time", default="0", help="分页起点时间戳，默认 0")
    p.set_defaults(func=cmd_list)

    p = sub.add_parser("hot", parents=[common], help="热门帖子")
    p.add_argument("--count", type=int, default=10)
    p.add_argument("--type", type=int, default=3)
    p.set_defaults(func=cmd_hot)

    p = sub.add_parser("search", parents=[common], help="关键词搜索（单次查询，取前 N 条）")
    p.add_argument("keyword")
    p.add_argument("--page", type=int, default=1)
    p.add_argument("--range", help="历史范围：1d/3d/7d/1m/6m/1y")
    p.add_argument("--n", type=int, default=20)
    p.set_defaults(func=cmd_search)

    p = sub.add_parser("toplist", parents=[common], help="排行榜")
    p.set_defaults(func=cmd_toplist)

    p = sub.add_parser("likelist", parents=[common], help="帖子点赞用户")
    p.add_argument("thread_id")
    p.set_defaults(func=cmd_likelist)

    p = sub.add_parser("tags", parents=[common], help="热门话题标签")
    p.set_defaults(func=cmd_tags)

    p = sub.add_parser("post", parents=[common], help="发帖（默认 dry-run）")
    p.add_argument("--title", required=True)
    p.add_argument("--content", required=True)
    p.add_argument("--cate", default="108", help="分类 ID 或分类名，如 108 / 校园趣事")
    p.add_argument("--images", default="", help="逗号分隔的 img_paths")
    p.add_argument("--contact-person", default="")
    p.add_argument("--contact-phone", default="")
    p.add_argument("--contact-qq", default="")
    p.add_argument("--contact-wx", default="")
    p.add_argument("--no-comments", action="store_true")
    p.add_argument("--no-anon", action="store_true")
    p.add_argument("--yes", action="store_true")
    p.set_defaults(func=cmd_post)

    p = sub.add_parser("info", parents=[common], help="帖子详情")
    p.add_argument("thread_id")
    p.set_defaults(func=cmd_info)

    p = sub.add_parser("comments", parents=[common], help="评论列表")
    p.add_argument("thread_id")
    p.set_defaults(func=cmd_comments)

    p = sub.add_parser("comment", parents=[common], help="发表评论（默认 dry-run）")
    p.add_argument("thread_id")
    p.add_argument("--content", required=True)
    p.add_argument("--reply", default="0", help="回复的 comment_id")
    p.add_argument("--root", default="0", help="根评论 comment_id")
    p.add_argument("--anon", action="store_true")
    _add_write(p)
    p.set_defaults(func=cmd_comment)

    for name, func, help_text in (
        ("like", cmd_like, "点赞帖子"),
        ("unlike", cmd_unlike, "取消点赞帖子"),
    ):
        p = sub.add_parser(name, parents=[common], help=help_text)
        p.add_argument("thread_id")
        _add_write(p)
        p.set_defaults(func=func)

    for name, func, help_text in (
        ("like-comment", cmd_like_comment, "点赞评论"),
        ("unlike-comment", cmd_unlike_comment, "取消点赞评论"),
    ):
        p = sub.add_parser(name, parents=[common], help=help_text)
        p.add_argument("thread_id")
        p.add_argument("comment_id")
        _add_write(p)
        p.set_defaults(func=func)

    p = sub.add_parser("categories", parents=[common], help="分区列表")
    p.set_defaults(func=cmd_categories)

    p = sub.add_parser("messages", parents=[common], help="消息列表")
    p.add_argument("--n", type=int, default=20)
    p.set_defaults(func=cmd_messages)

    p = sub.add_parser("me", parents=[common], help="当前账号")
    p.set_defaults(func=cmd_me)

    p = sub.add_parser("profile", parents=[common], help="查看帖子/评论的发布者资料（--tid/--cid 或批量）")
    p.add_argument("--thread", help="帖子 ID（同 --tid）")
    p.add_argument("--tid", help="帖子 ID")
    p.add_argument("--comment", help="评论 ID（同 --cid）")
    p.add_argument("--cid", help="评论 ID")
    p.add_argument("--file", help="批量文件，每行 tid 或 cid:值")
    p.add_argument("--raw", action="store_true", help="输出接口原始 JSON")
    p.set_defaults(func=cmd_profile)

    p = sub.add_parser("change", parents=[common], help="修改帖子状态（默认 dry-run）")
    p.add_argument("thread_id")
    p.add_argument("--act", default="finish")
    _add_write(p)
    p.set_defaults(func=cmd_change)

    p = sub.add_parser("delete-comment", parents=[common], help="删除评论（默认 dry-run）")
    p.add_argument("thread_id")
    p.add_argument("comment_id")
    _add_write(p)
    p.set_defaults(func=cmd_delete_comment)

    p = sub.add_parser("import-token", parents=[common], help="导入捕捉器 JSON 到 ~/.zanao.json")
    p.add_argument("--file")
    p.set_defaults(func=cmd_import_token)

    p = sub.add_parser("raw", parents=[common], help="直接调接口")
    p.add_argument("--method", default="GET")
    p.add_argument("--path", required=True)
    p.add_argument("--data", default="{}")
    p.set_defaults(func=cmd_raw)
    return parser


def main(argv: list[str] | None = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        return int(args.func(args))
    except ZanaoError as exc:
        print(f"[zanao] {exc}", file=sys.stderr)
        return 1
    except KeyboardInterrupt:
        return 130


if __name__ == "__main__":
    raise SystemExit(main())
