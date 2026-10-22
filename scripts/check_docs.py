"""校验本仓库文档的本地链接。"""

from pathlib import Path
import re
import sys


def main() -> int:
    # 只扫描文档编辑源和入口，避免把构建目录或相邻仓库当作发布文档。
    root = Path(__file__).resolve().parents[1]
    pages = [root / "README.md", *sorted((root / "docs/wiki").glob("*.md"))]
    failures = []
    local_links = 0
    for page in pages:
        for target in re.findall(r"\[[^\]]+\]\(([^)]+)\)", page.read_text(encoding="utf-8")):
            if target.startswith(("https://", "http://", "#")):
                continue
            local_links += 1
            relative_path = target.split("#", 1)[0]
            if not (page.parent / relative_path).is_file():
                failures.append(f"{page.relative_to(root)}: {target}")
    if failures:
        print("文档链接不存在：\n" + "\n".join(failures), file=sys.stderr)
        return 1
    print(f"检查通过：{len(pages)} 个页面，{local_links} 个本地链接。")
    return 0


if __name__ == "__main__":
    sys.exit(main())
