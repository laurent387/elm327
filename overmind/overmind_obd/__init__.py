"""Overmind OBD : ingestion, stockage, analyse et MCP (lecture seule) des passerelles ELM327."""

from pathlib import Path

__version__ = "0.1.0"

PACKAGE_DIR = Path(__file__).resolve().parent
REPO_DIR = PACKAGE_DIR.parent.parent
SCHEMA_PATH = REPO_DIR / "schemas" / "overmind_obd.schema.json"
PID_CATALOG_PATH = REPO_DIR / "schemas" / "pid_catalog.json"
