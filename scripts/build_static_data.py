#!/usr/bin/env python3
"""
Build compact static artifacts for the web version of Market Scout.
"""

from __future__ import annotations

import argparse
import sys
from pathlib import Path


ROOT_DIR = Path(__file__).resolve().parents[1]
if str(ROOT_DIR) not in sys.path:
    sys.path.insert(0, str(ROOT_DIR))

from market_data import (  # noqa: E402
    DATASET_ORDER,
    DEFAULT_BUILD_DIR,
    DEFAULT_RAW_DIR,
    ensure_dataset_file,
    get_dataset_local_path,
    parse_dataset_file,
    write_static_artifacts,
)


def parse_args():
    parser = argparse.ArgumentParser(
        description="Build compact static Redfin data artifacts for web hosting."
    )
    parser.add_argument(
        "--raw-dir",
        default=str(DEFAULT_RAW_DIR),
        help="Directory for cached raw Redfin gzip files.",
    )
    parser.add_argument(
        "--output-dir",
        default=str(DEFAULT_BUILD_DIR),
        help="Directory for generated static artifacts.",
    )
    parser.add_argument(
        "--max-age-days",
        type=int,
        default=30,
        help="Refresh cached raw files after this many days.",
    )
    parser.add_argument(
        "--force-refresh",
        action="store_true",
        help="Re-download source datasets even if a cache file already exists.",
    )
    parser.add_argument(
        "--skip-download",
        action="store_true",
        help="Require raw files to already exist locally and never download them.",
    )
    return parser.parse_args()


def main():
    args = parse_args()
    raw_dir = Path(args.raw_dir)
    output_dir = Path(args.output_dir)

    caches = {}
    for dataset_key in DATASET_ORDER:
        if args.skip_download:
            local_path = get_dataset_local_path(dataset_key, raw_dir=raw_dir)
            if not local_path.exists():
                raise FileNotFoundError(
                    f"Missing raw dataset for --skip-download: {local_path}"
                )
            print(f"Using existing {dataset_key} dataset -> {local_path}")
        else:
            local_path = ensure_dataset_file(
                dataset_key,
                raw_dir=raw_dir,
                max_age_days=args.max_age_days,
                force_refresh=args.force_refresh,
                logger=print,
            )

        print(f"Parsing {dataset_key} dataset -> {local_path}")
        caches[dataset_key] = parse_dataset_file(local_path, dataset_key)
        print(f"Loaded {len(caches[dataset_key]):,} {dataset_key} records")

    summary = write_static_artifacts(caches, output_dir=output_dir)
    print(f"Wrote static artifacts -> {output_dir}")
    print(
        "Counts:",
        ", ".join(
            f"{dataset}={count:,}" for dataset, count in summary["manifest"]["datasets"].items()
        ),
    )
    print(f"Search index entries: {summary['search_index_count']:,}")


if __name__ == "__main__":
    main()
