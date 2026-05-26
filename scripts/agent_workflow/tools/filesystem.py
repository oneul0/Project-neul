"""
파일시스템 도구 — Researcher / Reviewer 에이전트에서 사용하는 읽기 전용 툴셋.

각 툴은 두 부분으로 구성된다:
  - SCHEMA  : Claude API에 전달하는 JSON Schema 정의
  - execute : 실제 Python 함수 (tool_use 블록 수신 시 호출)
"""

from __future__ import annotations

import fnmatch
import json
import os
import re
import subprocess
from pathlib import Path
from typing import Any


# ─────────────────────────────────────────────
#  Helper
# ─────────────────────────────────────────────

def _rel(path: str, base: str) -> str:
    """절대 경로를 base 기준 상대 경로로 변환한다."""
    try:
        return str(Path(path).relative_to(base))
    except ValueError:
        return path


# ─────────────────────────────────────────────
#  1. read_file
# ─────────────────────────────────────────────

READ_FILE_SCHEMA: dict[str, Any] = {
    "name": "read_file",
    "description": (
        "파일 내용을 읽어 반환한다. "
        "line_start / line_end 로 범위를 제한할 수 있다. "
        "이진 파일은 읽지 못한다."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "path": {
                "type": "string",
                "description": "읽을 파일의 절대 또는 상대 경로",
            },
            "line_start": {
                "type": "integer",
                "description": "시작 줄 번호 (1-indexed, 생략 시 처음부터)",
            },
            "line_end": {
                "type": "integer",
                "description": "끝 줄 번호 (포함, 생략 시 끝까지)",
            },
        },
        "required": ["path"],
    },
}


def read_file(path: str, line_start: int | None = None, line_end: int | None = None, base_dir: str = ".") -> str:
    """파일을 읽어 텍스트로 반환한다."""
    abs_path = Path(base_dir) / path if not Path(path).is_absolute() else Path(path)

    if not abs_path.exists():
        return f"[ERROR] 파일이 없습니다: {path}"
    if not abs_path.is_file():
        return f"[ERROR] 디렉터리입니다: {path}"

    try:
        lines = abs_path.read_text(encoding="utf-8", errors="replace").splitlines(keepends=True)
    except Exception as e:
        return f"[ERROR] 읽기 실패: {e}"

    if line_start is not None or line_end is not None:
        s = (line_start or 1) - 1
        e_ = line_end or len(lines)
        lines = lines[s:e_]
        header = f"# {_rel(str(abs_path), base_dir)}  (줄 {s+1}–{e_})\n"
    else:
        header = f"# {_rel(str(abs_path), base_dir)}\n"

    content = "".join(lines)
    # 너무 크면 앞 200줄만
    if len(lines) > 200 and line_start is None and line_end is None:
        content = "".join(lines[:200])
        content += f"\n… (총 {len(lines)}줄, 처음 200줄만 표시. line_start/line_end로 범위 지정 가능)"

    return header + content


# ─────────────────────────────────────────────
#  2. list_directory
# ─────────────────────────────────────────────

LIST_DIR_SCHEMA: dict[str, Any] = {
    "name": "list_directory",
    "description": (
        "디렉터리 내용을 나열한다. "
        "recursive=true 이면 하위 디렉터리까지 탐색하되 "
        "node_modules / .git / target / build 등은 자동 제외된다."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "path": {
                "type": "string",
                "description": "탐색할 디렉터리 경로",
            },
            "recursive": {
                "type": "boolean",
                "description": "하위 디렉터리 포함 여부 (기본 false)",
            },
            "pattern": {
                "type": "string",
                "description": "fnmatch 글로브 패턴 (예: *.java)",
            },
        },
        "required": ["path"],
    },
}

_SKIP_DIRS = {
    "node_modules", ".git", "target", "build", ".gradle",
    ".idea", "__pycache__", ".venv", "venv", ".mypy_cache",
    "dist", "out", ".next", ".nuxt",
}


def list_directory(path: str, recursive: bool = False, pattern: str | None = None, base_dir: str = ".") -> str:
    """디렉터리 목록을 텍스트로 반환한다."""
    abs_path = Path(base_dir) / path if not Path(path).is_absolute() else Path(path)

    if not abs_path.exists():
        return f"[ERROR] 경로가 없습니다: {path}"
    if not abs_path.is_dir():
        return f"[ERROR] 파일입니다: {path}"

    entries: list[str] = []

    if recursive:
        for root, dirs, files in os.walk(abs_path):
            dirs[:] = [d for d in dirs if d not in _SKIP_DIRS and not d.startswith(".")]
            rel_root = _rel(root, str(abs_path))
            for f in sorted(files):
                rel = os.path.join(rel_root, f) if rel_root != "." else f
                if pattern is None or fnmatch.fnmatch(f, pattern):
                    entries.append(rel)
            if len(entries) > 500:
                entries.append("… (500개 초과, 패턴이나 특정 경로로 좁혀주세요)")
                break
    else:
        for item in sorted(abs_path.iterdir()):
            name = item.name + ("/" if item.is_dir() else "")
            if pattern is None or fnmatch.fnmatch(item.name, pattern):
                entries.append(name)

    if not entries:
        return f"(비어 있음: {path})"

    return f"# {_rel(str(abs_path), base_dir)}/\n" + "\n".join(entries)


# ─────────────────────────────────────────────
#  3. grep_code
# ─────────────────────────────────────────────

GREP_CODE_SCHEMA: dict[str, Any] = {
    "name": "grep_code",
    "description": (
        "코드베이스에서 정규표현식 또는 키워드로 검색한다. "
        "매칭된 줄을 파일명 및 줄 번호와 함께 반환한다. "
        "최대 50개 결과로 제한된다."
    ),
    "input_schema": {
        "type": "object",
        "properties": {
            "pattern": {
                "type": "string",
                "description": "검색할 정규표현식 또는 문자열",
            },
            "path": {
                "type": "string",
                "description": "검색 범위 디렉터리 또는 파일 (기본: 프로젝트 루트)",
            },
            "file_pattern": {
                "type": "string",
                "description": "대상 파일 글로브 패턴 (예: *.java, *.py)",
            },
            "case_sensitive": {
                "type": "boolean",
                "description": "대소문자 구분 여부 (기본 true)",
            },
        },
        "required": ["pattern"],
    },
}


def grep_code(
    pattern: str,
    path: str | None = None,
    file_pattern: str | None = None,
    case_sensitive: bool = True,
    base_dir: str = ".",
) -> str:
    """코드에서 패턴을 검색하고 결과를 반환한다."""
    search_path = str(Path(base_dir) / path) if path else base_dir

    try:
        flags = re.MULTILINE if case_sensitive else re.MULTILINE | re.IGNORECASE
        regex = re.compile(pattern, flags)
    except re.error as e:
        return f"[ERROR] 잘못된 정규표현식: {e}"

    results: list[str] = []

    def _search_file(filepath: Path) -> None:
        if len(results) >= 50:
            return
        try:
            text = filepath.read_text(encoding="utf-8", errors="replace")
        except Exception:
            return
        for i, line in enumerate(text.splitlines(), 1):
            if regex.search(line):
                rel = _rel(str(filepath), base_dir)
                results.append(f"{rel}:{i}: {line.rstrip()}")
                if len(results) >= 50:
                    break

    search_root = Path(search_path)

    if search_root.is_file():
        _search_file(search_root)
    else:
        for root, dirs, files in os.walk(search_root):
            dirs[:] = [d for d in dirs if d not in _SKIP_DIRS and not d.startswith(".")]
            for fname in sorted(files):
                if len(results) >= 50:
                    break
                if file_pattern and not fnmatch.fnmatch(fname, file_pattern):
                    continue
                _search_file(Path(root) / fname)

    if not results:
        return f"(검색 결과 없음: '{pattern}')"

    header = f"# grep: '{pattern}' in {_rel(search_path, base_dir)}\n"
    suffix = "\n… (50개 한도 도달, 패턴을 좁혀주세요)" if len(results) == 50 else ""
    return header + "\n".join(results) + suffix


# ─────────────────────────────────────────────
#  Tool registry
# ─────────────────────────────────────────────

#: 읽기 전용 툴 스키마 목록 (Claude API tools 파라미터에 전달)
READONLY_TOOL_SCHEMAS: list[dict[str, Any]] = [
    READ_FILE_SCHEMA,
    LIST_DIR_SCHEMA,
    GREP_CODE_SCHEMA,
]

#: 툴 이름 → 실행 함수 매핑
TOOL_EXECUTORS: dict[str, Any] = {
    "read_file": read_file,
    "list_directory": list_directory,
    "grep_code": grep_code,
}


def execute_tool(tool_name: str, tool_input: dict[str, Any], base_dir: str = ".") -> str:
    """tool_use 블록을 받아 해당 툴을 실행하고 결과 문자열을 반환한다."""
    executor = TOOL_EXECUTORS.get(tool_name)
    if executor is None:
        return f"[ERROR] 알 수 없는 툴: {tool_name}"
    try:
        return executor(**tool_input, base_dir=base_dir)
    except TypeError as e:
        return f"[ERROR] 툴 호출 파라미터 오류: {e}"
    except Exception as e:
        return f"[ERROR] 툴 실행 중 예외 발생: {e}"
