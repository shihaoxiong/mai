"""Terminal Tool Implementation

Execute shell commands safely.
"""

import asyncio
import json
import shutil
from pathlib import Path
import sys
from pathlib import Path
from typing import Any, Dict, List, Optional

from ....logger import get_logger
from ..schema import ToolSchema, ToolParameter
from ..client import MCPClient


logger = get_logger("terminal_tool")


class TerminalTool:
    """Terminal command execution tool"""

    def __init__(self):
        self.allowed_commands: List[str] = []
        self._setup_allowed_commands()

    def _setup_allowed_commands(self) -> None:
        """Setup list of allowed commands"""
        self.allowed_commands = [
            "ls", "cat", "echo", "grep", "find", "head", "tail",
            "wc", "mkdir", "touch", "python", "python3",
            "pwd", "cd", "rm", "cp", "mv", "chmod", "chown",
            "date", "whoami", "hostname", "uname", "which",
            "sort", "uniq", "tr", "cut", "awk", "sed",
            "diff", "patch", "tar", "gzip", "gunzip",
            "zip", "unzip", "rsync", "scp", "ssh"
        ]

    def execute_command(self, command: str) -> str:
        """Execute a shell command and return output"""
        cmd = command.strip()

        if not cmd:
            raise ValueError("Empty command")

        base_cmd = cmd.split()[0] if cmd.split() else ""

        if base_cmd not in self.allowed_commands:
            raise PermissionError(
                f"Command '{base_cmd}' is not allowed. "
                f"Allowed commands: {self.allowed_commands}"
            )

        try:
            import subprocess
            result = subprocess.run(
                cmd,
                shell=True,
                capture_output=True,
                text=True,
                timeout=60,
                cwd=Path.cwd()
            )

            output = result.stdout
            if result.stderr and not output:
                output = result.stderr

            return output if output else "(command executed successfully)"

        except subprocess.TimeoutExpired:
            raise TimeoutError(f"Command timed out: {cmd}")
        except Exception as e:
            raise RuntimeError(f"Command execution failed: {str(e)}")

    def execute_with_args(
        self,
        command: str,
        args: str = "",
        timeout: int = 60
    ) -> str:
        """Execute a command with arguments"""
        full_cmd = f"{command} {args}" if args else command
        return self.execute_command(full_cmd)

    def list_directory(self, path: str = ".") -> str:
        """List directory contents"""
        p = Path(path)
        if not p.exists():
            raise FileNotFoundError(f"Directory not found: {path}")
        if not p.is_dir():
            raise NotADirectoryError(f"Not a directory: {path}")

        items = []
        for item in p.iterdir():
            item_type = "DIR" if item.is_dir() else "FILE"
            items.append(f"{item_type:4} {item.name}")

        return "\n".join(sorted(items))

    def read_file(self, path: str, lines: Optional[int] = None) -> str:
        """Read file contents"""
        p = Path(path)
        if not p.exists():
            raise FileNotFoundError(f"File not found: {path}")
        if p.is_dir():
            raise IsADirectoryError(f"Is a directory: {path}")

        try:
            if lines:
                with open(p, "r") as f:
                    content = "".join(f.readlines()[:lines])
            else:
                content = p.read_text()

            return content

        except UnicodeDecodeError:
            with open(p, "rb") as f:
                binary_content = f.read(1024)
                return f"[Binary file - first 1024 bytes]\n{binary_content}"

    def write_file(self, path: str, content: str, mode: str = "w") -> str:
        """Write content to file"""
        p = Path(path)

        if p.exists() and p.is_dir():
            raise IsADirectoryError(f"Is a directory: {path}")

        if mode == "w":
            p.write_text(content)
        elif mode == "a":
            p.write_text(p.read_text() + content)

        return f"File written: {path}"

    def search_files(
        self,
        pattern: str,
        path: str = ".",
        recursive: bool = True
    ) -> str:
        """Search for pattern in files"""
        p = Path(path)
        if not p.exists():
            raise FileNotFoundError(f"Path not found: {path}")

        matches = []
        max_matches = 100

        if recursive:
            for match in p.rglob("*"):
                if match.is_file() and len(matches) < max_matches:
                    try:
                        content = match.read_text() if match.stat().st_size < 1024 * 1024 else ""
                        if pattern in content or pattern in match.name:
                            matches.append(str(match))
                    except Exception:
                        pass
        else:
            for match in p.glob("*"):
                if match.is_file() and len(matches) < max_matches:
                    matches.append(str(match))

        if not matches:
            return f"No matches found for pattern: {pattern}"

        return "\n".join(matches[:max_matches])

    def count_lines(self, path: str) -> str:
        """Count lines in file"""
        p = Path(path)
        if not p.exists():
            raise FileNotFoundError(f"File not found: {path}")

        try:
            with open(p, "r") as f:
                line_count = sum(1 for _ in f)
            return f"Lines: {line_count}"
        except Exception as e:
            raise RuntimeError(f"Failed to count lines: {str(e)}")

    def get_file_info(self, path: str) -> str:
        """Get file information"""
        p = Path(path)
        if not p.exists():
            raise FileNotFoundError(f"File not found: {path}")

        info = []
        info.append(f"Path: {p.absolute()}")
        info.append(f"Name: {p.name}")
        info.append(f"Type: {'Directory' if p.is_dir() else 'File'}")
        info.append(f"Size: {p.stat().st_size} bytes")
        info.append(f"Created: {p.stat().st_ctime}")
        info.append(f"Modified: {p.stat().st_mtime}")

        return "\n".join(info)


def register_terminal_tools(mcp_client: MCPClient) -> None:
    """Register terminal tools with MCP client"""

    execute_schema = ToolSchema(
        name="terminal.execute",
        description="Execute a shell command safely",
        category="system",
        parameters=[
            ToolParameter(
                name="command",
                type="string",
                description="The command to execute",
                required=True
            )
        ],
        side_effect=True,
        permission="write"
    )
    mcp_client.register_tool(execute_schema, TerminalTool().execute_command)

    ls_schema = ToolSchema(
        name="terminal.ls",
        description="List directory contents",
        category="file",
        parameters=[
            ToolParameter(
                name="path",
                type="string",
                description="Directory path to list",
                required=False,
                default="."
            )
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(ls_schema, TerminalTool().list_directory)

    cat_schema = ToolSchema(
        name="terminal.cat",
        description="Read file contents",
        category="file",
        parameters=[
            ToolParameter(
                name="path",
                type="string",
                description="File path to read",
                required=True
            ),
            ToolParameter(
                name="lines",
                type="integer",
                description="Number of lines to read",
                required=False
            )
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(cat_schema, TerminalTool().read_file)

    write_schema = ToolSchema(
        name="terminal.write",
        description="Write content to a file",
        category="file",
        parameters=[
            ToolParameter(
                name="path",
                type="string",
                description="File path to write",
                required=True
            ),
            ToolParameter(
                name="content",
                type="string",
                description="Content to write",
                required=True
            )
        ],
        side_effect=True,
        permission="write"
    )
    mcp_client.register_tool(write_schema, TerminalTool().write_file)

    grep_schema = ToolSchema(
        name="terminal.grep",
        description="Search for pattern in files",
        category="search",
        parameters=[
            ToolParameter(
                name="pattern",
                type="string",
                description="Pattern to search for",
                required=True
            ),
            ToolParameter(
                name="path",
                type="string",
                description="Directory to search in",
                required=False,
                default="."
            )
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(grep_schema, TerminalTool().search_files)

    wc_schema = ToolSchema(
        name="terminal.wc",
        description="Count lines in a file",
        category="file",
        parameters=[
            ToolParameter(
                name="path",
                type="string",
                description="File path",
                required=True
            )
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(wc_schema, TerminalTool().count_lines)

    file_info_schema = ToolSchema(
        name="terminal.file_info",
        description="Get file information",
        category="file",
        parameters=[
            ToolParameter(
                name="path",
                type="string",
                description="File or directory path",
                required=True
            )
        ],
        side_effect=False,
        permission="read"
    )
    mcp_client.register_tool(file_info_schema, TerminalTool().get_file_info)
