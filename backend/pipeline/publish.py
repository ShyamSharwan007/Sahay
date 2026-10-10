"""Upload built packs to the GitHub Release "packs-v1" and record them in backend/app/data/packs.json.

    cd backend
    uv run --with-requirements pipeline/requirements.txt python -m pipeline.publish
    uv run --with-requirements pipeline/requirements.txt python -m pipeline.publish --region mahabalipuram --repo org/sahay

Needs the GitHub CLI (`gh`), logged in with access to the repository. Only the .sqlite files are uploaded; the
map fields (pmtiles, styles, assets zip) stay null unless an entry already has them (they are kept as they are).
"""

import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from pathlib import Path

from .pack_writer import read_meta
from .paths import PACKS_DIR, PACKS_JSON, REPO_ROOT
from .regions import load_regions

TAG = "packs-v1"
MAP_FIELDS = (
    "pmtilesUrl",
    "pmtilesBytes",
    "styleLightUrl",
    "styleDarkUrl",
    "assetsZipUrl",
    "assetsZipBytes",
)


class PublishError(RuntimeError):
    pass


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()


def release_asset_url(repo: str, tag: str, filename: str) -> str:
    return f"https://github.com/{repo}/releases/download/{tag}/{filename}"


def build_entry(pack_path: Path, repo: str, tag: str = TAG, previous: dict | None = None) -> dict:
    """packs.json entry for one built pack. Map fields default to null and keep any value already recorded."""
    meta = read_meta(pack_path)
    version = meta.get("pack_version")
    if not version:
        raise PublishError(f"{pack_path} has no meta.pack_version; is it a Sahay pack?")
    previous = previous or {}
    entry = {
        "packVersion": version,
        "sqliteUrl": release_asset_url(repo, tag, pack_path.name),
        "sqliteBytes": pack_path.stat().st_size,
        "sqliteSha256": sha256_of(pack_path),
    }
    entry.update({field: previous.get(field) for field in MAP_FIELDS})
    return entry


def read_packs_json(path: Path) -> dict:
    if not path.exists():
        return {}
    try:
        data = json.loads(path.read_text(encoding="utf-8"))
    except ValueError as exc:
        raise PublishError(f"{path} is not valid JSON, fix or delete it first: {exc}") from exc
    return data if isinstance(data, dict) else {}


def write_packs_json(path: Path, data: dict) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(json.dumps(dict(sorted(data.items())), indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, path)


def _gh(args: list[str], *, check: bool = True) -> subprocess.CompletedProcess:
    try:
        result = subprocess.run(["gh", *args], capture_output=True, text=True, cwd=REPO_ROOT)
    except FileNotFoundError as exc:
        raise PublishError(
            "GitHub CLI `gh` not found. Install it from https://cli.github.com and run `gh auth login`."
        ) from exc
    if check and result.returncode != 0:
        raise PublishError(
            f"`gh {' '.join(args)}` failed:\n{result.stderr.strip() or result.stdout.strip()}"
        )
    return result


def detect_repo() -> str:
    return _gh(["repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner"]).stdout.strip()


def ensure_release(repo: str, tag: str) -> None:
    if _gh(["release", "view", tag, "--repo", repo], check=False).returncode == 0:
        return
    print(f"creating release {tag} ...")
    _gh(
        [
            "release",
            "create",
            tag,
            "--repo",
            repo,
            "--title",
            "Sahay trip packs",
            "--notes",
            "Offline trip packs (SQLite) for Sahay. Built by backend/pipeline.",
        ]
    )


def upload(repo: str, tag: str, files: list[Path]) -> None:
    _gh(["release", "upload", tag, *map(str, files), "--repo", repo, "--clobber"])


def main(argv=None) -> int:
    parser = argparse.ArgumentParser(
        description="Publish packs to the GitHub Release and update packs.json."
    )
    parser.add_argument(
        "--region",
        action="append",
        choices=sorted(load_regions()),
        help="region to publish (repeatable; default: every region with a built pack)",
    )
    parser.add_argument("--repo", help="owner/name (default: detected from the git remote via gh)")
    parser.add_argument("--tag", default=TAG)
    parser.add_argument(
        "--dry-run", action="store_true", help="show what would be uploaded and written"
    )
    args = parser.parse_args(argv)
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")

    try:
        region_ids = args.region or [
            r for r in sorted(load_regions()) if (PACKS_DIR / r / f"{r}.sqlite").exists()
        ]
        if not region_ids:
            raise PublishError(
                f"no built packs found under {PACKS_DIR}; run pipeline.build_pack first"
            )
        files = {}
        for rid in region_ids:
            path = PACKS_DIR / rid / f"{rid}.sqlite"
            if not path.exists():
                raise PublishError(
                    f"{path} does not exist; run `python -m pipeline.build_pack --region {rid}` first"
                )
            if not read_meta(path).get("public_key_b64"):
                print(
                    f"WARNING: {rid} was built without SIGNING_PUBLIC_KEY_B64; the app cannot verify alerts with it"
                )
            files[rid] = path

        if args.dry_run:
            repo = args.repo or "<owner>/<repo>"
        else:
            if shutil.which("gh") is None:
                raise PublishError(
                    "GitHub CLI `gh` not found. Install it from https://cli.github.com and run `gh auth login`."
                )
            repo = args.repo or detect_repo()
            ensure_release(repo, args.tag)
            upload(repo, args.tag, list(files.values()))

        packs = read_packs_json(PACKS_JSON)
        for rid, path in files.items():
            packs[rid] = build_entry(path, repo, args.tag, packs.get(rid))
            print(
                f"{rid}: {packs[rid]['packVersion']}  {packs[rid]['sqliteBytes']} bytes  {packs[rid]['sqliteUrl']}"
            )
        if args.dry_run:
            print(f"dry run: nothing uploaded, {PACKS_JSON} not written")
        else:
            write_packs_json(PACKS_JSON, packs)
            print(f"wrote {PACKS_JSON}")
        return 0
    except PublishError as exc:
        print(f"ERROR: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
