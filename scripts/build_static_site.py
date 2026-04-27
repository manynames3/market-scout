#!/usr/bin/env python3
"""
Assemble the deployable static site for Cloudflare Pages or any other static host.
"""

from __future__ import annotations

import argparse
import shutil
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]
DEFAULT_STATIC_DIR = ROOT_DIR / "static"
DEFAULT_DATA_DIR = ROOT_DIR / "generated" / "web-data"
DEFAULT_DIST_DIR = ROOT_DIR / "dist"


def parse_args():
    parser = argparse.ArgumentParser(
        description="Assemble static frontend assets and generated data into a deployable directory."
    )
    parser.add_argument(
        "--static-dir",
        default=str(DEFAULT_STATIC_DIR),
        help="Directory containing committed static frontend assets.",
    )
    parser.add_argument(
        "--data-dir",
        default=str(DEFAULT_DATA_DIR),
        help="Directory containing generated web data artifacts.",
    )
    parser.add_argument(
        "--dist-dir",
        default=str(DEFAULT_DIST_DIR),
        help="Output directory for the final static site.",
    )
    return parser.parse_args()


def copy_tree_contents(source_dir, dest_dir):
    for item in source_dir.iterdir():
      target = dest_dir / item.name
      if item.is_dir():
          shutil.copytree(item, target, dirs_exist_ok=True)
      else:
          shutil.copy2(item, target)


def main():
    args = parse_args()
    static_dir = Path(args.static_dir)
    data_dir = Path(args.data_dir)
    dist_dir = Path(args.dist_dir)

    if not static_dir.exists():
        raise FileNotFoundError(f"Missing static frontend directory: {static_dir}")
    if not data_dir.exists():
        raise FileNotFoundError(
            f"Missing generated data directory: {data_dir}. Run scripts/build_static_data.py first."
        )

    if dist_dir.exists():
        shutil.rmtree(dist_dir)
    dist_dir.mkdir(parents=True, exist_ok=True)

    copy_tree_contents(static_dir, dist_dir)
    shutil.copytree(data_dir, dist_dir / "data", dirs_exist_ok=True)

    print(f"Static site assembled at {dist_dir}")
    print(f"Frontend assets: {static_dir}")
    print(f"Data artifacts:  {data_dir}")
    print(f"Deploy root:     {dist_dir}")


if __name__ == "__main__":
    main()
