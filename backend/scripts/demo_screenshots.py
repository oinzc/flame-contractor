"""为 README 截取新版单账号续火工作台的演示界面图（方案 A：5 浅 1 深）。

先构建前端并启动只读演示实例（scripts/demo_server.py --reseed），再运行本脚本：
  .venv/Scripts/python.exe scripts/demo_screenshots.py
输出到 docs/assets/screenshots/。截图固定使用 Asia/Shanghai 时区与 1440×900（2x）视口，
以保证重复生成时画面一致且单屏自适应分页容量稳定。
"""

from __future__ import annotations

import argparse
import asyncio
import sys
from pathlib import Path

from playwright.async_api import Page, async_playwright

REPO_ROOT = Path(__file__).resolve().parents[2]
OUT_DIR = REPO_ROOT / "docs" / "assets" / "screenshots"
if hasattr(sys.stdout, "reconfigure"):
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

SHOT_PLAN: tuple[dict[str, str], ...] = (
    {"name": "dashboard", "theme": "light"},
    {"name": "send-confirm", "theme": "light"},
    {"name": "targets", "theme": "light"},
    {"name": "scan-reconciliation", "theme": "light"},
    {"name": "jobs", "theme": "light"},
    {"name": "accounts", "theme": "dark"},
)


async def set_theme(page: Page, theme: str) -> None:
    label = "浅色" if theme == "light" else "深色"
    await page.locator(f'aside.fc-sidebar [title="切换主题：{label}"]').click()
    await page.wait_for_function(
        "(expected) => document.documentElement.dataset.theme === expected",
        arg=theme,
    )
    await page.wait_for_timeout(200)


async def open_drawer_and_shot(page: Page, trigger_name: str, shot_name: str) -> None:
    await page.locator(".fc-page-actions").get_by_role("button", name=trigger_name).click()
    await page.locator("aside.fc-drawer").wait_for(state="visible")
    await page.wait_for_load_state("networkidle")
    await page.wait_for_timeout(450)
    await shot(page, shot_name)
    await close_drawer(page)


async def close_drawer(page: Page) -> None:
    await page.get_by_role("button", name="关闭面板").click()
    await page.locator("aside.fc-drawer").wait_for(state="hidden")
    await page.wait_for_timeout(200)


async def shot(page: Page, name: str, **kwargs) -> None:
    path = OUT_DIR / f"{name}.png"
    await page.screenshot(path=str(path), **kwargs)
    print(f"已保存 {path.relative_to(REPO_ROOT)}")


async def capture_all(base_url: str) -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)

    async with async_playwright() as pw:
        browser = await pw.chromium.launch()
        context = await browser.new_context(
            viewport={"width": 1440, "height": 900},
            device_scale_factor=2,
            timezone_id="Asia/Shanghai",
            locale="zh-CN",
            color_scheme="light",
            reduced_motion="reduce",
        )
        page = await context.new_page()
        await page.goto(base_url)

        await page.locator("#auth-username").wait_for(state="visible")
        await page.locator("#auth-username").fill("demo")
        await page.locator("#auth-password").fill("demo-password")
        await page.locator('button[type="submit"]').click()

        await page.locator('table[aria-label="当前账号续火好友列表"]').wait_for(state="visible")
        await set_theme(page, "light")
        await page.wait_for_load_state("networkidle")
        await page.wait_for_timeout(600)
        await shot(page, "dashboard")

        await open_drawer_and_shot(page, "今日群发预览", "send-confirm")

        await page.locator("tr.fc-row-clickable").first.click()
        await page.locator("aside.fc-drawer").wait_for(state="visible")
        await page.wait_for_timeout(350)
        await shot(page, "targets")
        await close_drawer(page)

        await open_drawer_and_shot(page, "扫描对账", "scan-reconciliation")

        await page.locator("aside.fc-sidebar").get_by_role("button", name="记录与统计").click()
        await page.wait_for_load_state("networkidle")
        await page.wait_for_timeout(500)
        await shot(page, "jobs")

        await page.locator("aside.fc-sidebar").get_by_role("button", name="系统与调度").click()
        await page.wait_for_load_state("networkidle")
        await set_theme(page, "dark")
        await page.locator("button.fc-acct-trigger").click()
        await page.locator('[aria-label="切换抖音账号"]').wait_for(state="visible")
        await page.wait_for_timeout(350)
        await shot(page, "accounts")

        await browser.close()


async def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://127.0.0.1:8010")
    args = parser.parse_args()
    await capture_all(args.base_url)


if __name__ == "__main__":
    asyncio.run(main())
