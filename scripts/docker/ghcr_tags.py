"""计算 GHCR 预构建镜像的规范化小写仓库名与发布标签集合（纯标准库，供 CI 与单测复用）。"""

from __future__ import annotations

import argparse
from pathlib import Path
import re

_STABLE_SEMVER_TAG_RE = re.compile(r"^v(\d+\.\d+\.\d+)$")
_PRERELEASE_SEMVER_TAG_RE = re.compile(r"^v(\d+\.\d+\.\d+-[0-9A-Za-z.-]+)$")
_SAFE_DOCKER_TAG_RE = re.compile(r"^v[0-9A-Za-z._-]{1,120}$")


def compute_ghcr_metadata(repository: str, ref: str, sha: str) -> dict[str, object]:
    repo_clean = repository.strip().lower()
    if "/" not in repo_clean or repo_clean.startswith("/") or repo_clean.endswith("/"):
        raise ValueError(f"非法 GitHub repository 标识：{repository!r}")

    sha_clean = sha.strip().lower()
    if len(sha_clean) < 7 or not re.fullmatch(r"[0-9a-f]+", sha_clean):
        raise ValueError(f"非法 Git commit SHA：{sha!r}")

    image = f"ghcr.io/{repo_clean}"
    short_sha = sha_clean[:7]
    tags: list[str] = [f"{image}:sha-{short_sha}"]

    ref_clean = ref.strip()
    if ref_clean == "refs/heads/main":
        tags.append(f"{image}:latest")
    elif ref_clean.startswith("refs/tags/"):
        tag_name = ref_clean.removeprefix("refs/tags/")
        stable_match = _STABLE_SEMVER_TAG_RE.fullmatch(tag_name)
        prerelease_match = _PRERELEASE_SEMVER_TAG_RE.fullmatch(tag_name)
        if stable_match:
            version_no_v = stable_match.group(1)
            tags.extend(
                [
                    f"{image}:{tag_name}",
                    f"{image}:{version_no_v}",
                    f"{image}:latest",
                ]
            )
        elif prerelease_match:
            version_no_v = prerelease_match.group(1)
            tags.extend(
                [
                    f"{image}:{tag_name}",
                    f"{image}:{version_no_v}",
                ]
            )
        elif _SAFE_DOCKER_TAG_RE.fullmatch(tag_name):
            tags.append(f"{image}:{tag_name}")

    return {
        "image": image,
        "short_sha": short_sha,
        "tags": tags,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Compute GHCR image name and tags.")
    parser.add_argument("--repository", required=True, help="owner/repo")
    parser.add_argument("--ref", required=True, help="Git ref, e.g. refs/heads/main")
    parser.add_argument("--sha", required=True, help="Full or >=7-char commit SHA")
    parser.add_argument(
        "--github-output",
        default="",
        help="Optional path to $GITHUB_OUTPUT file",
    )
    args = parser.parse_args()

    meta = compute_ghcr_metadata(args.repository, args.ref, args.sha)
    tags_joined = " ".join(meta["tags"])
    if args.github_output:
        out_path = Path(args.github_output)
        with out_path.open("a", encoding="utf-8") as fh:
            fh.write(f"image={meta['image']}\n")
            fh.write(f"short_sha={meta['short_sha']}\n")
            fh.write(f"tags={tags_joined}\n")
    else:
        print(f"image={meta['image']}")
        print(f"short_sha={meta['short_sha']}")
        print(f"tags={tags_joined}")


if __name__ == "__main__":
    main()
