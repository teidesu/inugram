#!/usr/bin/env python3
import argparse
import html
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from pathlib import Path


REPORTS = {
    "Leak_Suspects": "org.eclipse.mat.api:suspects",
    "Top_Consumers": "org.eclipse.mat.api:top_components",
}


def strip_html(path: Path) -> str:
    text = path.read_text(errors="replace")
    text = re.sub(r"(?is)<script.*?</script>|<style.*?</style>", " ", text)
    text = re.sub(r"(?s)<[^>]+>", " ", text)
    text = html.unescape(text)
    text = re.sub(r"[ \t\r\f\v]+", " ", text)
    text = re.sub(r"\n\s+", "\n", text)
    return "\n".join(line.strip() for line in text.splitlines() if line.strip())


def mat_executable(mat: str | None) -> Path | None:
    if mat:
        p = Path(mat).expanduser()
        if p.suffix == ".app":
            return p / "Contents" / "MacOS" / "MemoryAnalyzer"
        return p
    default = Path("/Applications/MemoryAnalyzer.app/Contents/MacOS/MemoryAnalyzer")
    return default if default.exists() else None


def report_zip(dump: Path, suffix: str) -> Path:
    return dump.with_name(f"{dump.stem}_{suffix}.zip")


def run_mat(dump: Path, mat: Path, xmx: str) -> None:
    cmd = [
        str(mat),
        "-consolelog",
        "-nosplash",
        "-application",
        "org.eclipse.mat.api.parse",
        str(dump),
        *REPORTS.values(),
        "-vmargs",
        f"-Xmx{xmx}",
    ]
    subprocess.run(cmd, check=True)


def extract_report(zip_path: Path, out_dir: Path) -> list[Path]:
    target = out_dir / zip_path.stem
    if target.exists():
        shutil.rmtree(target)
    target.mkdir(parents=True)
    with zipfile.ZipFile(zip_path) as zf:
        zf.extractall(target)
    return [target / "index.html", *sorted((target / "pages").glob("*.html"))]


def main() -> int:
    parser = argparse.ArgumentParser(description="Extract Eclipse MAT heap report text.")
    parser.add_argument("dump", help=".hprof or converted .ec.hprof")
    parser.add_argument("--mat", help="MemoryAnalyzer.app or MemoryAnalyzer binary")
    parser.add_argument("--xmx", default="8g", help="MAT heap, default: 8g")
    parser.add_argument("--out", help="output directory, default: temp dir")
    parser.add_argument("--no-run-mat", action="store_true", help="only use existing report zips")
    args = parser.parse_args()

    dump = Path(args.dump).expanduser().resolve()
    if not dump.exists():
        print(f"missing dump: {dump}", file=sys.stderr)
        return 2

    zips = {name: report_zip(dump, name) for name in REPORTS}
    if not all(p.exists() for p in zips.values()) and not args.no_run_mat:
        mat = mat_executable(args.mat)
        if not mat or not mat.exists():
            print("MAT reports missing and MemoryAnalyzer not found.", file=sys.stderr)
            print("Install MAT or pass --mat /path/to/MemoryAnalyzer.app.", file=sys.stderr)
            return 3
        run_mat(dump, mat, args.xmx)

    missing = [str(p) for p in zips.values() if not p.exists()]
    if missing:
        print("missing report zips:", file=sys.stderr)
        for p in missing:
            print(f"  {p}", file=sys.stderr)
        return 4

    out_dir = Path(args.out).expanduser().resolve() if args.out else Path(tempfile.mkdtemp(prefix="matreports-"))
    out_dir.mkdir(parents=True, exist_ok=True)
    print(f"output: {out_dir}")

    for name, zip_path in zips.items():
        print(f"\n===== {name}: {zip_path.name} =====")
        for html_path in extract_report(zip_path, out_dir):
            if html_path.exists():
                print(f"\n### {html_path.relative_to(out_dir)}")
                text = strip_html(html_path)
                print("\n".join(text.splitlines()[:120]))

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
