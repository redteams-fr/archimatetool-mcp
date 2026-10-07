"""Connects to the server configured as 'archi' in ~/.vibe/config.toml using Vibe's own code."""
import asyncio, os, tomllib
from pathlib import Path
from vibe.core.config.mcp_servers import _parse_raw_mcp_server
from vibe.core.tools.mcp.tools import list_tools_http

raw = next(s for s in tomllib.loads(Path(os.path.expanduser("~/.vibe/config.toml")).read_text())["mcp_servers"] if s["name"] == "archi")
server = _parse_raw_mcp_server(raw)
tools = asyncio.run(list_tools_http(server.url, headers=server.http_headers(), startup_timeout_sec=30))
print(f"{len(tools)} tools:", ", ".join(t.name for t in tools))
