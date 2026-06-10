#!/usr/bin/env python3
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))

from Back.app.services.migrations import apply_migrations


if __name__ == "__main__":
    applied = apply_migrations()
    print("Applied migrations:", ", ".join(applied) if applied else "none")
