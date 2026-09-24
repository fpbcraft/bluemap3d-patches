from pathlib import Path
import hashlib, json, zipfile

dist = Path("dist")
manifest = {}
for p in sorted(dist.iterdir()):
    if p.is_file():
        manifest[p.name] = {
            "size": p.stat().st_size,
            "sha256": hashlib.sha256(p.read_bytes()).hexdigest(),
        }
(dist / "MANIFEST.json").write_text(json.dumps(manifest, indent=2) + "\n")

with zipfile.ZipFile(dist / "bluemap3d-patches-1.1.0.zip", "w", zipfile.ZIP_DEFLATED) as z:
    for p in sorted(dist.rglob("*")):
        if p.is_file() and p.name != "bluemap3d-patches-1.1.0.zip":
            z.write(p, p.relative_to(dist))