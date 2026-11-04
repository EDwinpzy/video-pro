"""Chaquopy 入口：把解压后的后端目录加入 sys.path，再启动 proxy.main(port)。

app 侧 EmbeddedBackend 解压 zip 后调用 start(dir, port)：
- dir 是应用私有可写目录（含 proxy.py / hhkan.py / team_backdrop.py / scraper.py / www / 缓存目录）
- 在此把 dir 插到 sys.path[0]，保证 import proxy 用的是解压出的那份
"""
import sys
import os
import ssl
import threading
import urllib.request
from urllib.parse import urlsplit, urlunsplit


# These public poster hosts answer HTTP 200 but do not complete a TLS handshake.
# Keep the exception host-scoped: every other URL retains normal HTTPS validation.
HTTP_ONLY_VRES_HOSTS = frozenset({
    "vres.cyscyy.com",
    "vres.zyxpedu.com",
    "vres.enbymae.com",
})
_HTTP_FALLBACK_MARKER = "_otv_http_fallback"


def prefer_http_for_vres(url):
    """Use the poster hosts' working transport without weakening global TLS checks."""
    try:
        parsed = urlsplit(url)
        host = (parsed.hostname or "").rstrip(".").lower()
    except ValueError:
        return url
    if parsed.scheme.lower() != "https" or host not in HTTP_ONLY_VRES_HOSTS:
        return url
    return urlunsplit(("http", parsed.netloc, parsed.path, parsed.query, parsed.fragment))


def configure_image_transport(proxy_module):
    """Install the vres HTTP strategy before the proxy starts its warm-up threads."""
    domains = getattr(getattr(proxy_module, "hhkan", None), "IMG_DOMAINS", None)
    if not isinstance(domains, (list, tuple)):
        raise RuntimeError("proxy.hhkan.IMG_DOMAINS is unavailable")
    proxy_module.hhkan.IMG_DOMAINS = [prefer_http_for_vres(base) for base in domains]

    original = getattr(proxy_module, "_img_fetch_with_mirrors", None)
    if not callable(original):
        raise RuntimeError("proxy._img_fetch_with_mirrors is unavailable")
    if getattr(original, _HTTP_FALLBACK_MARKER, False):
        return

    def fetch_with_http_fallback(url):
        return original(prefer_http_for_vres(url))

    setattr(fetch_with_http_fallback, _HTTP_FALLBACK_MARKER, True)
    proxy_module._img_fetch_with_mirrors = fetch_with_http_fallback


def configure_api_environment(api_base_url, migration_ca_path):
    """Point embedded web fallbacks at this APK's backend and trust its private CA."""
    origin = str(api_base_url or "").strip().rstrip("/")
    try:
        parsed = urlsplit(origin)
        port = parsed.port
    except ValueError as exc:
        raise ValueError("api_base_url must be an HTTPS origin") from exc
    if (parsed.scheme != "https" or not parsed.hostname or parsed.username or parsed.password
            or parsed.path not in ("", "/") or parsed.query or parsed.fragment
            or (port is not None and not 1 <= port <= 65535)):
        raise ValueError("api_base_url must be an HTTPS origin")
    origin = f"https://{parsed.netloc}"
    ca_path = str(migration_ca_path or "").strip()
    if not ca_path or not os.path.isfile(ca_path):
        raise ValueError("migration CA file is missing")

    context = ssl.create_default_context()
    context.load_verify_locations(cafile=ca_path)
    urllib.request.install_opener(urllib.request.build_opener(
        urllib.request.HTTPSHandler(context=context)
    ))

    os.environ["OTV_CLOUD_BASE"] = origin + "/web"
    os.environ["OTV_LICENSE_ENDPOINT"] = origin + "/activate"
    os.environ["OTV_IPTV_LATEST_URL"] = origin + "/iptv/latest"


def start(backend_dir, port=8090, api_base_url=None, migration_ca_path=None):
    """启动内置后端（阻塞前先 setpath 再起线程）。"""
    if api_base_url is None or migration_ca_path is None:
        raise ValueError("api_base_url and migration_ca_path are required")
    configure_api_environment(api_base_url, migration_ca_path)
    sys.path.insert(0, backend_dir)
    # Hot-update extraction replaces backend_dir.  Store scraped metadata beside it
    # under filesDir so SQLite survives both backend and APK upgrades.
    os.environ.setdefault("OTV_DATA_DIR", os.path.dirname(backend_dir))
    # 让 proxy 的相对资源（decrypt_stream.js/team_icon_cache 等）落回解压目录
    os.chdir(backend_dir)
    import proxy
    configure_image_transport(proxy)

    def _run():
        # 端口共存自愈（TV 版/移动版同装一台设备时 8090 只有一方能绑定；
        # 先绑定的一方服务整机 loopback，两边 app 都能访问）。若本进程起晚了
        # 绑定失败（Address already in use），后台每 5s 重试——绑定方进程一旦
        # 被系统回收释放端口，本进程立即接管，避免「后端凭空消失」。
        # v1.21 投屏：bind 0.0.0.0——电视（DLNA 渲染器）需经局域网访问本机
        # 中继流（127.0.0.1 电视不可达）；出站 SSRF 防护不变，LAN 暴露面为
        # 只读媒体中继。
        import time
        last_err = None
        for _ in range(240):   # 最多重试 20 分钟
            try:
                proxy.main(int(port), bind="0.0.0.0")
                return
            except Exception as e:
                last_err = e
                time.sleep(5.0)
        import traceback
        traceback.print_exc()
        print("backend give up retrying: %r" % (last_err,))

    t = threading.Thread(target=_run, daemon=True)
    t.start()
