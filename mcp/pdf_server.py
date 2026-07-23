#!/usr/bin/env -S uv run --script
# /// script
# requires-python = ">=3.10"
# dependencies = [
#     "mcp[cli]>=1.2.0",
#     "pdfplumber>=0.11.0",
# ]
# ///
"""A tiny MCP server that gives an agent real PDF capabilities:
page count, per-page text extraction, and table extraction as Markdown.

Designed for local models in VS Code agent mode, where the built-in
read_file tool only sees PDF bytes (garbage), not page text/tables.
"""

import os
from pathlib import Path

import pdfplumber
from mcp.server.fastmcp import FastMCP

mcp = FastMCP("pdf-tools")

# --- Confined workspace -------------------------------------------------------
# All tools below are locked to this folder. Override with MCP_WORKSPACE.
# Relative paths are resolved against WORKSPACE; absolute paths must stay inside
# it (see _safe). This keeps the pdf_* tools consistent with list_dir/read_pdf,
# which emit/accept workspace-relative names — otherwise an agent that lists the
# workspace and feeds the relative name back to extract_pdf_text gets
# "no such file or directory" because pdfplumber resolved it against the
# server's CWD, not WORKSPACE.
WORKSPACE = Path(
    os.environ.get("MCP_WORKSPACE", "/home/george/Desktop/chat_ollama-master/chat_ollama-master/workspace")
).resolve()
WORKSPACE.mkdir(parents=True, exist_ok=True)


def _safe(path: str) -> Path | None:
    """Resolve `path` inside WORKSPACE and reject anything that escapes it.

    Returns the resolved Path, or None if `path` is outside the workspace.
    Paths are relative to the workspace root. Absolute paths, '..', and symlinks
    that resolve outside the workspace are all rejected (an absolute right-hand
    path makes pathlib discard the workspace prefix, so resolve() lands outside
    and the membership check below fails). Callers must turn None into a friendly
    error string — never treat it as a valid path."""
    p = (WORKSPACE / str(path)).resolve()
    if p != WORKSPACE and WORKSPACE not in p.parents:
        return None
    return p


def _merge_complementary_columns(grid: list[list]) -> list[list]:
    """Merge adjacent columns that are never both filled in the same row.

    pdfplumber often splits a whitespace-laid-out table so the header and body
    land in alternating columns (header in cols 1,3,5; body in 0,2,4). Those
    column pairs are 'complementary' — no row fills both — so merging them
    reconstructs the real columns. A genuine multi-column table has at least one
    row with two adjacent cells filled, so it is never collapsed by this."""
    while grid and len(grid[0]) > 1:
        width = len(grid[0])
        for i in range(width - 1):
            if all(not (r[i] and r[i + 1]) for r in grid):
                grid = [r[:i] + [(r[i] or r[i + 1])] + r[i + 2:] for r in grid]
                break
        else:
            break
    return grid


def _clean_grid(rows: list[list]) -> list[list]:
    """Normalize cells, drop empty rows, drop all-empty columns, then merge the
    complementary columns pdfplumber emits for whitespace-laid-out tables."""
    grid = [[(c if c is not None else "").replace("\n", " ").strip() for c in row]
            for row in rows if row and any((c or "").strip() for c in row)]
    if not grid:
        return []
    width = max(len(r) for r in grid)
    grid = [r + [""] * (width - len(r)) for r in grid]
    keep = [i for i in range(width) if any(r[i] for r in grid)]
    grid = [[r[i] for i in keep] for r in grid]
    return _merge_complementary_columns(grid)


def _is_real_table(grid: list[list]) -> bool:
    """A genuine table has >=2 columns and >=2 rows after cleaning. Prose blocks
    collapse to a single column once complementary columns are merged, so they are
    rejected here."""
    return len(grid) >= 2 and len(grid[0]) >= 2


def _grid_to_markdown(grid: list[list]) -> str:
    """Render a cleaned grid as a GitHub Markdown table."""
    width = len(grid[0])
    header, *body = grid
    out = ["| " + " | ".join(header) + " |",
           "| " + " | ".join(["---"] * width) + " |"]
    out += ["| " + " | ".join(r) + " |" for r in body]
    return "\n".join(out)


@mcp.tool()
def pdf_info(path: str) -> str:
    """Return the number of pages in a PDF. `path` is relative to the confined
    workspace root (absolute paths inside it are also accepted)."""
    p = _safe(path)
    if p is None:
        return f"Error: '{path}' is outside the confined workspace"
    if not p.is_file():
        return f"Error: '{path}' is not a file in the workspace"
    with pdfplumber.open(str(p)) as pdf:
        return f"{p.relative_to(WORKSPACE)}: {len(pdf.pages)} pages"


@mcp.tool()
def extract_pdf_text(path: str, page: int) -> str:
    """Extract plain text from a single 1-indexed page of a PDF. `path` is
    relative to the confined workspace root (absolute paths inside it are also
    accepted)."""
    p = _safe(path)
    if p is None:
        return f"Error: '{path}' is outside the confined workspace"
    if not p.is_file():
        return f"Error: '{path}' is not a file in the workspace"
    with pdfplumber.open(str(p)) as pdf:
        if page < 1 or page > len(pdf.pages):
            return f"Error: page {page} out of range (1..{len(pdf.pages)})"
        return pdf.pages[page - 1].extract_text() or "(no extractable text on this page)"


@mcp.tool()
def extract_pdf_tables(path: str, page: int) -> str:
    """Extract genuine tables on a single 1-indexed page and return them as Markdown.
    Prose that pdfplumber mis-detects as a table is filtered out, and empty filler
    columns are removed. If the page has no real table, says so. `path` is
    relative to the confined workspace root (absolute paths inside it are also
    accepted)."""
    p = _safe(path)
    if p is None:
        return f"Error: '{path}' is outside the confined workspace"
    if not p.is_file():
        return f"Error: '{path}' is not a file in the workspace"
    with pdfplumber.open(str(p)) as pdf:
        if page < 1 or page > len(pdf.pages):
            return f"Error: page {page} out of range (1..{len(pdf.pages)})"
        raw = pdf.pages[page - 1].extract_tables()
    grids = [g for g in (_clean_grid(t) for t in raw) if _is_real_table(g)]
    if not grids:
        return "(no real table on this page — it may be prose; try extract_pdf_text)"
    blocks = [f"### Table {i}\n\n{_grid_to_markdown(g)}" for i, g in enumerate(grids, 1)]
    return "\n\n".join(blocks)


# --- Confined file tools (workspace-only) ------------------------------------
@mcp.tool()
def list_dir(subpath: str = ".") -> str:
    """List files and folders inside the confined workspace. `subpath` is relative
    to the workspace root (default: the root). Paths outside it are rejected."""
    d = _safe(subpath)
    if d is None:
        return f"Error: '{subpath}' is outside the confined workspace"
    if not d.exists():
        return f"Error: '{subpath}' does not exist in the workspace"
    if not d.is_dir():
        return f"Error: '{subpath}' is not a directory"
    rows = []
    for e in sorted(d.iterdir()):
        kind = "dir " if e.is_dir() else "file"
        size = e.stat().st_size if e.is_file() else ""
        rows.append(f"{kind}  {e.relative_to(WORKSPACE)}  {size}")
    return "\n".join(rows) or "(empty)"


@mcp.tool()
def read_file(path: str) -> str:
    """Read a UTF-8 text file from the confined workspace. `path` is relative to
    the workspace root. For PDFs use read_pdf instead."""
    p = _safe(path)
    if p is None:
        return f"Error: '{path}' is outside the confined workspace"
    if not p.is_file():
        return f"Error: '{path}' is not a file in the workspace"
    try:
        return p.read_text(encoding="utf-8")
    except UnicodeDecodeError:
        return f"Error: '{path}' is not UTF-8 text (binary file?)"


@mcp.tool()
def write_file(path: str, content: str) -> str:
    """Create or overwrite a UTF-8 text file in the confined workspace (this also
    serves as 'save'). `path` is relative to the workspace root; parent folders
    are created as needed. Paths outside the workspace are rejected."""
    p = _safe(path)
    if p is None:
        return f"Error: '{path}' is outside the confined workspace"
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(content, encoding="utf-8")
    return f"Wrote {len(content)} chars to {p.relative_to(WORKSPACE)}"


@mcp.tool()
def read_pdf(path: str) -> str:
    """Extract the full text of a PDF located in the confined workspace (all pages
    concatenated). `path` is relative to the workspace root. For page-level text or
    tables, use extract_pdf_text / extract_pdf_tables."""
    p = _safe(path)
    if p is None:
        return f"Error: '{path}' is outside the confined workspace"
    if not p.is_file():
        return f"Error: '{path}' is not a file in the workspace"
    with pdfplumber.open(str(p)) as pdf:
        parts = [
            f"--- page {i} ---\n{(page.extract_text() or '').strip()}"
            for i, page in enumerate(pdf.pages, 1)
        ]
    return "\n\n".join(parts) or "(no extractable text)"


if __name__ == "__main__":
    import uvicorn
    from starlette.middleware.cors import CORSMiddleware

    # Serve Streamable-HTTP directly (no supergateway bridge). CORS is enabled so
    # browser-based MCP clients (e.g. the llama.cpp web UI) can reach this endpoint
    # cross-origin; Mcp-Session-Id is exposed so clients can read the session id.
    # Server-side clients (Spring AI, VS Code) ignore CORS.
    app = mcp.streamable_http_app()
    app.add_middleware(
        CORSMiddleware,
        allow_origins=["*"],
        allow_methods=["*"],
        allow_headers=["*"],
        expose_headers=["Mcp-Session-Id"],
    )
    uvicorn.run(app, host="127.0.0.1", port=8765)
