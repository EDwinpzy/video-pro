from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path


PROJECT_ROOT = Path(__file__).resolve().parents[2]
RUNNER_PATH = PROJECT_ROOT / "app" / "src" / "main" / "python" / "proxy_runner.py"


def load_proxy_runner():
    spec = importlib.util.spec_from_file_location("video_pro_proxy_runner", RUNNER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("unable to load proxy_runner.py")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


class FakeHhkan:
    IMG_DOMAINS = [
        "https://vres.cyscyy.com",
        "https://vres.zyxpedu.com",
        "https://vres.enbymae.com",
    ]


class FakeProxy:
    hhkan = FakeHhkan()

    def __init__(self):
        self.calls = []
        self._img_fetch_with_mirrors = self.fetch

    def fetch(self, url):
        self.calls.append(url)
        return b"image", "image/jpeg"


class ImageTransportTest(unittest.TestCase):
    def setUp(self):
        self.runner = load_proxy_runner()

    def test_configures_all_known_vres_mirrors_for_http(self):
        proxy = FakeProxy()

        self.runner.configure_image_transport(proxy)

        self.assertEqual(
            [
                "http://vres.cyscyy.com",
                "http://vres.zyxpedu.com",
                "http://vres.enbymae.com",
            ],
            proxy.hhkan.IMG_DOMAINS,
        )

    def test_rewrites_known_vres_url_before_entering_proxy_fetch(self):
        proxy = FakeProxy()
        self.runner.configure_image_transport(proxy)

        result = proxy._img_fetch_with_mirrors(
            "https://vres.enbymae.com/vod1/poster.jpg?token=abc#ignored",
        )

        self.assertEqual((b"image", "image/jpeg"), result)
        self.assertEqual(
            ["http://vres.enbymae.com/vod1/poster.jpg?token=abc#ignored"],
            proxy.calls,
        )

    def test_non_vres_https_and_existing_http_are_unchanged(self):
        proxy = FakeProxy()
        self.runner.configure_image_transport(proxy)

        proxy._img_fetch_with_mirrors("https://img3.doubanio.com/poster.jpg")
        proxy._img_fetch_with_mirrors("http://vres.zyxpedu.com/poster.jpg")

        self.assertEqual(
            [
                "https://img3.doubanio.com/poster.jpg",
                "http://vres.zyxpedu.com/poster.jpg",
            ],
            proxy.calls,
        )

    def test_configuration_is_idempotent(self):
        proxy = FakeProxy()
        self.runner.configure_image_transport(proxy)
        configured = proxy._img_fetch_with_mirrors

        self.runner.configure_image_transport(proxy)

        self.assertIs(configured, proxy._img_fetch_with_mirrors)


if __name__ == "__main__":
    unittest.main()
