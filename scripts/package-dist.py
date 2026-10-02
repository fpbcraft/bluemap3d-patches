from pathlib import Path
import hashlib
import json
import subprocess
import zipfile

from release_metadata import build_release

root = Path(__file__).resolve().parents[1]
dist = root / "dist"
release = build_release()

try:
    patch_commit = subprocess.check_output(
        ["git", "rev-parse", "HEAD"], cwd=root, text=True
    ).strip()
except (OSError, subprocess.CalledProcessError):
    patch_commit = "unknown"

build_info = {
    **release,
    "patchCommit": patch_commit,
}
(dist / "BUILD_INFO.json").write_text(json.dumps(build_info, indent=2) + "\n")

manifest = {}
for path in sorted(dist.iterdir()):
    if path.is_file():
        manifest[path.name] = {
            "size": path.stat().st_size,
            "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
        }
(dist / "MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n")

archive_name = f"bluemap3d-patches-{release['version']}.zip"
with zipfile.ZipFile(dist / archive_name, "w", zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(dist.rglob("*")):
        if path.is_file() and path.name != archive_name:
            archive.write(path, path.relative_to(dist))
