"""Copy the platform's protocol artifact in and regenerate the gRPC stubs.

`protocol/` at the repository root is the artifact: the `.proto` files, `ENCODING.md` and the
fixtures. This copies it verbatim into `sdks/python/proto/` (committed, so CI can diff the two)
and generates `src/ankka/_proto/` (ignored).
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from pathlib import Path

SDK = Path(__file__).resolve().parent.parent
REPO = SDK.parent.parent
SOURCE = REPO / "protocol"
COPY = SDK / "proto"
OUT = SDK / "src" / "ankka" / "_proto"


def main() -> int:
    if not (SOURCE / "src" / "main" / "protobuf").is_dir():
        print(f"no protocol artifact at {SOURCE}", file=sys.stderr)
        return 1
    if COPY.exists():
        shutil.rmtree(COPY)
    for sub in ("src/main/protobuf", "fixtures"):
        if (SOURCE / sub).is_dir():
            shutil.copytree(SOURCE / sub, COPY / sub)
    for name in ("ENCODING.md", "README.md"):
        if (SOURCE / name).is_file():
            shutil.copy2(SOURCE / name, COPY / name)
    if OUT.exists():
        shutil.rmtree(OUT)
    OUT.mkdir(parents=True)
    (OUT / "__init__.py").write_text("")
    protos = sorted((COPY / "src" / "main" / "protobuf").rglob("*.proto"))
    if not protos:
        print("no .proto files to generate from", file=sys.stderr)
        return 1
    # Generated imports are `from ankka.protocol.v1 import ...`; rooting the include path at the
    # proto directory and the output inside `_proto` keeps them resolvable as `ankka._proto.ankka...`.
    cmd = [
        sys.executable,
        "-m",
        "grpc_tools.protoc",
        f"-I{COPY / 'src' / 'main' / 'protobuf'}",
        f"--python_out={OUT}",
        f"--pyi_out={OUT}",
        f"--grpc_python_out={OUT}",
        *map(str, protos),
    ]
    result = subprocess.run(cmd, check=False)
    if result.returncode != 0:
        return result.returncode
    for d in OUT.rglob("*"):
        if d.is_dir() and not (d / "__init__.py").exists():
            (d / "__init__.py").write_text("")
    # protoc writes imports by the proto package path (`from ankka.protocol.v1 import ...`), which
    # would only resolve if the generated code were the top-level `ankka` package. It lives under
    # `ankka._proto`, so the imports are rewritten to say so.
    for f in list(OUT.rglob("*.py")) + list(OUT.rglob("*.pyi")):
        text = f.read_text()
        rewritten = text.replace("from ankka.protocol.v1 import", "from ankka._proto.ankka.protocol.v1 import")
        if rewritten != text:
            f.write_text(rewritten)
    print(f"generated {len(protos)} proto files into {OUT.relative_to(SDK)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
