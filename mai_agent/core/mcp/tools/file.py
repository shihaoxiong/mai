"""File Tool Implementation

Read, write, and manage files safely.
"""

import json
import os
from pathlib import Path
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from ....logger import get_logger
from ..schema import ToolSchema, ToolParameter
from ..client import MCPClient


logger = get_logger("file_tool")


class FileTool:
    """File operations tool"""

    def __init__(self):
        self.allowed_paths: List[str] = ["./", "./data"]
        self.max_file_size: int = 10 * 1024 * 1024

    def _validate_path(self, path: str) -> Path:
        """Validate and resolve file path"""
        p = Path(path).resolve()

        allowed = False
        for allowed_base in self.allowed_paths:
            try:
                allowed_base = Path(allowed_base).resolve()
                if str(p).startswith(str(allowed_base)):
                    allowed = True
                    break
            except Exception:
                pass

        if not allowed:
            raise PermissionError(
                f"Path '{path}' is not in allowed directories: {self.allowed_paths}"
            )

        return p

    def read_file(self, path: str, encoding: str = "utf-8") -> str:
        """Read file contents"""
        p = self._validate_path(path)

        if not p.exists():
            raise FileNotFoundError(f"File not found: {path}")

        if p.is_dir():
            raise IsADirectoryError(f"Is a directory: {path}")

        if p.stat().st_size > self.max_file_size:
            raise ValueError(
                f"File too large: {p.stat().st_size} bytes (max: {self.max_file_size})"
            )

        try:
            return p.read_text(encoding=encoding)
        except UnicodeDecodeError:
            with open(p, "rb") as f:
                binary_content = f.read()
            return f"[Binary file - {len(binary_content)} bytes]"

    def read_lines(
        self,
        path: str,
        start: int = 0,
        end: Optional[int] = None,
        encoding: str = "utf-8"
    ) -> str:
        """Read specific lines from file"""
        p = self._validate_path(path)

        if not p.exists():
            raise FileNotFoundError(f"File not found: {path}")

        with open(p, "r", encoding=encoding) as f:
            lines = f.readlines()

        if start < 0:
            start = 0

        if end is None or end > len(lines):
            end = len(lines)

        if start >= end:
            return ""

        return "".join(lines[start:end])

    def write_file(
        self,
        path: str,
        content: str,
        mode: str = "w",
        encoding: str = "utf-8"
    ) -> str:
        """Write content to file"""
        p = self._validate_path(path)

        if p.is_dir():
            raise IsADirectoryError(f"Is a directory: {path}")

        if mode == "a":
            existing = ""
            if p.exists():
                existing = p.read_text(encoding=encoding)
            content = existing + content

        p.write_text(content, encoding=encoding)

        return f"File written: {path}"

    def create_file(
        self,
        path: str,
        content: str = "",
        encoding: str = "utf-8"
    ) -> str:
        """Create a new file"""
        return self.write_file(path, content, "w", encoding)

    def append_file(
        self,
        path: str,
        content: str,
        encoding: str = "utf-8"
    ) -> str:
        """Append content to file"""
        return self.write_file(path, content, "a", encoding)

    def list_directory(self, path: str = ".") -> str:
        """List directory contents"""
        p = self._validate_path(path)

        if not p.exists():
            raise FileNotFoundError(f"Directory not found: {path}")

        if not p.is_dir():
            raise NotADirectoryError(f"Not a directory: {path}")

        items = []
        for item in sorted(p.iterdir()):
            item_type = "DIR" if item.is_dir() else "FILE"
            size = item.stat().st_size if item.is_file() else 0
            items.append(f"{item_type:4} {size:>10} {item.name}")

        return "\n".join(items) if items else "(empty directory)"

    def list_files(
        self,
        path: str = ".",
        pattern: Optional[str] = None,
        recursive: bool = False
    ) -> str:
        """List files with optional pattern matching"""
        p = self._validate_path(path)

        if not p.exists():
            raise FileNotFoundError(f"Path not found: {path}")

        files = []

        if recursive:
            for item in p.rglob("*"):
                if item.is_file():
                    if pattern is None or pattern in item.name:
                        files.append(str(item.relative_to(p)))
        else:
            for item in p.glob("*"):
                if item.is_file():
                    if pattern is None or pattern in item.name:
                        files.append(item.name)

        return "\n".join(sorted(files)) if files else "(no files found)"

    def list_directories(self, path: str = ".") -> str:
        """List subdirectories"""
        p = self._validate_path(path)

        if not p.exists():
            raise FileNotFoundError(f"Path not found: {path}")

        dirs = [d.name for d in p.iterdir() if d.is_dir()]

        return "\n".join(sorted(dirs)) if dirs else "(no directories)"

    def file_exists(self, path: str) -> str:
        """Check if file exists"""
        p = self._validate_path(path)
        return "true" if p.exists() else "false"

    def directory_exists(self, path: str) -> str:
        """Check if directory exists"""
        p = self._validate_path(path)
        return "true" if p.exists() and p.is_dir() else "false"

    def get_file_info(self, path: str) -> str:
        """Get detailed file information"""
        p = self._validate_path(path)

        if not p.exists():
            raise FileNotFoundError(f"File not found: {path}")

        info = {
            "path": str(p.absolute()),
            "name": p.name,
            "type": "directory" if p.is_dir() else "file",
            "size_bytes": p.stat().st_size,
            "size_human": self._human_size(p.stat().st_size),
            "is_readable": os.access(p, os.R_OK),
            "is_writable": os.access(p, os.W_OK),
            "is_executable": os.access(p, os.X_OK),
            "created": self._timestamp(p.stat().st_ctime),
            "modified": self._timestamp(p.stat().st_mtime),
            "accessed": self._timestamp(p.stat().st_atime)
        }

        return json.dumps(info, indent=2, ensure_ascii=False)

    def search_content(
        self,
        path: str,
        pattern: str,
        recursive: bool = True,
        encoding: str = "utf-8"
    ) -> str:
        """Search for pattern in file contents"""
        p = self._validate_path(path)

        if not p.exists():
            raise FileNotFoundError(f"Path not found: {path}")

        matches = []
        max_matches = 100

        if recursive:
            for item in p.rglob("*"):
                if item.is_file() and len(matches) < max_matches:
                    try:
                        if item.stat().st_size < 1024 * 1024:
                            content = item.read_text(encoding=encoding)
                            if pattern in content:
                                matches.append(f"{item}: contains pattern")
                    except Exception:
                        pass
        else:
            for item in p.glob("*"):
                if item.is_file() and len(matches) < max_matches:
                    try:
                        if item.stat().st_size < 1024 * 1024:
                            content = item.read_text(encoding=encoding)
                            if pattern in content:
                                matches.append(f"{item.name}: contains pattern")
                    except Exception:
                        pass

        return "\n".join(matches[:max_matches]) if matches else f"No matches found for: {pattern}"

    def create_directory(self, path: str, parents: bool = True) -> str:
        """Create a directory"""
        p = self._validate_path(path)

        if parents:
            p.mkdir(parents=True, exist_ok=True)
        else:
            p.mkdir(exist_ok=True)

        return f"Directory created: {path}"

    def delete_file(self, path: str) -> str:
        """Delete a file"""
        p = self._validate_path(path)

        if not p.exists():
            raise FileNotFoundError(f"File not found: {path}")

        if p.is_dir():
            raise IsADirectoryError(f"Is a directory: {path}")

        p.unlink()
        return f"File deleted: {path}"

    def copy_file(self, source: str, destination: str) -> str:
        """Copy a file"""
        src = self._validate_path(source)
        dst = self._validate_path(destination)

        if not src.exists():
            raise FileNotFoundError(f"Source file not found: {source}")

        if src.is_dir():
            raise IsADirectoryError(f"Source is a directory: {source}")

        import shutil
        shutil.copy2(src, dst)

        return f"File copied: {source} -> {destination}"

    def move_file(self, source: str, destination: str) -> str:
        """Move a file"""
        src = self._validate_path(source)
        dst = self._validate_path(destination)

        if not src.exists():
            raise FileNotFoundError(f"Source file not found: {source}")

        import shutil
        shutil.move(src, dst)

        return f"File moved: {source} -> {destination}"

    def _human_size(self, size: int) -> str:
        """Convert bytes to human-readable size"""
        for unit in ["B", "KB", "MB", "GB", "TB"]:
            if size < 1024:
                return f"{size:.1f} {unit}"
            size /= 1024
        return f"{size:.1f} PB"

    def _timestamp(self, ts: float) -> str:
        """Convert timestamp to ISO string"""
        from datetime import datetime
        return datetime.fromtimestamp(ts).isoformat()


def register_file_tools(mcp_client: MCPClient) -> None:
    """Register file tools with MCP client"""

    read_schema = ToolSchema(
        name="file.read",
        description="Read file contents",
        category="file",
        parameters=[
            ToolParameter(name="path", type="string", description="File path to read", required=True),
            ToolParameter(name="encoding", type="string", description="File encoding", required=False, default="utf-8")
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(read_schema, FileTool().read_file)

    read_lines_schema = ToolSchema(
        name="file.read_lines",
        description="Read specific lines from file",
        category="file",
        parameters=[
            ToolParameter(name="path", type="string", description="File path", required=True),
            ToolParameter(name="start", type="integer", description="Start line (0-based)", required=False, default=0),
            ToolParameter(name="end", type="integer", description="End line (exclusive)", required=False)
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(read_lines_schema, FileTool().read_lines)

    write_schema = ToolSchema(
        name="file.write",
        description="Write content to file",
        category="file",
        parameters=[
            ToolParameter(name="path", type="string", description="File path to write", required=True),
            ToolParameter(name="content", type="string", description="Content to write", required=True),
            ToolParameter(name="mode", type="string", description="Write mode (w/a)", required=False, default="w")
        ],
        side_effect=True,
        permission="write"
    )
    mcp_client.register_tool(write_schema, FileTool().write_file)

    list_schema = ToolSchema(
        name="file.ls",
        description="List directory contents",
        category="file",
        parameters=[
            ToolParameter(name="path", type="string", description="Directory path", required=False, default=".")
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(list_schema, FileTool().list_directory)

    exists_schema = ToolSchema(
        name="file.exists",
        description="Check if file exists",
        category="file",
        parameters=[
            ToolParameter(name="path", type="string", description="File path to check", required=True)
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(exists_schema, FileTool().file_exists)

    info_schema = ToolSchema(
        name="file.info",
        description="Get detailed file information",
        category="file",
        parameters=[
            ToolParameter(name="path", type="string", description="File path", required=True)
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(info_schema, FileTool().get_file_info)

    search_schema = ToolSchema(
        name="file.search",
        description="Search for pattern in files",
        category="search",
        parameters=[
            ToolParameter(name="path", type="string", description="Directory to search", required=True),
            ToolParameter(name="pattern", type="string", description="Pattern to search for", required=True),
            ToolParameter(name="recursive", type="boolean", description="Search recursively", required=False, default=True)
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(search_schema, FileTool().search_content)
