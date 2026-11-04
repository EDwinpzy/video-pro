"""视频刮削：按片名刮取完整影片元数据。

数据链路（本机网络实测可用的真实源）：
1. hhkan0.com 搜索 → 候选列表（标题/封面/影片id）
2. hhkan0.com 详情页 → 完整元数据（标题/年份/类型/简介/导演/演员/评分/多线路+选集）
3. 合并 16:9 横版海报（TMDB backdrop 配置 key 后命中，否则封面兜底）

用法:
    from scraper import scrape
    result = scrape("痴迷")
"""
import json
import os
import re
import threading
import urllib.parse
import urllib.request

import hhkan

_cache = {}
_lock = threading.Lock()


def _search(title, page=1):
    """hhkan 搜索 → [{id, cover, title}]"""
    token = hhkan.get_search_token()
    path = "/search?t=%s&k=%s&p=%d" % (
        urllib.parse.quote(token), urllib.parse.quote(title), page)
    html = hhkan.get_page(path)
    items = []
    for m in re.finditer(
        r'<a href="/detail/(\d+)\.html" class="search-result-item">.*?'
        r'data-original="([^"]+)".*?<div class="title">([^<]*)</div>',
        html, re.S):
        items.append({
            "id": int(m.group(1)),
            "cover": hhkan.img_url(m.group(2)),
            "title": m.group(3).strip(),
        })
    return items


def scrape(title, page=1):
    """按片名刮削：{matched, data:{完整元数据+backdrop+线路}, candidates}"""
    title = (title or "").strip()
    if not title:
        return {"matched": False, "error": "缺少片名"}
    cache_key = "sc:%s:%d" % (title, page)
    with _lock:
        if cache_key in _cache:
            return _cache[cache_key]
    try:
        items = _search(title, page)
    except Exception as e:
        return {"matched": False, "error": "搜索失败: %s" % str(e)[:120]}
    if not items:
        result = {"matched": False, "items": []}
        with _lock:
            _cache[cache_key] = result
        return result
    # 详情（取最佳匹配 = 第一条；可换 candidates 中的 id 精确刮削）
    best = items[0]
    try:
        d = hhkan.parse_detail(hhkan.get_page("/detail/%d.html" % best["id"]), best["id"])
    except Exception as e:
        return {"matched": False, "error": "详情刮取失败: %s" % str(e)[:120], "items": items}
    if not d["title"]:
        result = {"matched": False, "items": items}
        with _lock:
            _cache[cache_key] = result
        return result
    d["cover"] = d.get("cover") or best.get("cover")
    d["score"] = d.get("score") or best.get("score", 0)
    d["candidates"] = items[:5]
    result = {"matched": True, "data": d}
    with _lock:
        _cache[cache_key] = result
    return result
