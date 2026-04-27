#!/usr/bin/env bash
set -euo pipefail

python3 scripts/build_static_data.py
python3 scripts/build_static_site.py
