#!/usr/bin/env python3
"""
run_workflow.py — 3-에이전트 AI 워크플로우 CLI 진입점.

사용법:
    # 작업을 직접 전달
    python run_workflow.py "OllamaAnalyzerService에 타임아웃 로깅을 추가하세요"

    # 프로젝트 루트를 명시
    python run_workflow.py --dir /path/to/project "기능 설명"

    # 결과를 파일로 저장
    python run_workflow.py --save "기능 설명"

    # 인터랙티브 모드
    python run_workflow.py

환경 변수:
    ANTHROPIC_API_KEY  — Claude API 키 (필수)
"""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

# 스크립트 위치 기준으로 패키지 경로 설정
SCRIPTS_DIR = Path(__file__).parent
sys.path.insert(0, str(SCRIPTS_DIR))

from agent_workflow import WorkflowOrchestrator


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="3-에이전트 AI 코딩 워크플로우 (Researcher → Planner → Reviewer)",
        formatter_class=argparse.RawDescriptionHelpFormatter,
        epilog="""
예시:
  python run_workflow.py "OllamaAnalyzerService 타임아웃 로깅 추가"
  python run_workflow.py --dir /my/project --save "인증 모듈 리팩토링"
        """,
    )
    parser.add_argument(
        "task",
        nargs="?",
        default=None,
        help="수행할 작업 설명 (생략 시 인터랙티브 입력)",
    )
    parser.add_argument(
        "--dir", "-d",
        default=os.getcwd(),
        help="프로젝트 루트 디렉터리 (기본: 현재 디렉터리)",
    )
    parser.add_argument(
        "--save", "-s",
        action="store_true",
        help="결과를 ./workflow_output/ 에 저장",
    )
    parser.add_argument(
        "--output-dir", "-o",
        default="./workflow_output",
        help="결과 저장 디렉터리 (기본: ./workflow_output)",
    )
    parser.add_argument(
        "--log-dir", "-l",
        default="./workflow_logs",
        help="로그 저장 디렉터리 (기본: ./workflow_logs)",
    )
    parser.add_argument(
        "--no-log",
        action="store_true",
        help="로깅 비활성화",
    )
    return parser.parse_args()


def check_env() -> None:
    if not os.environ.get("ANTHROPIC_API_KEY"):
        print("[INFO] ANTHROPIC_API_KEY 없음 → claude CLI (OAuth) 모드로 실행합니다.")
    else:
        print("[INFO] ANTHROPIC_API_KEY 감지 → Anthropic SDK 모드로 실행합니다.")


def main() -> None:
    args = parse_args()
    check_env()

    # 작업 내용 결정
    if args.task:
        task = args.task
    else:
        print("수행할 작업을 입력하세요 (엔터 두 번으로 완료):")
        lines: list[str] = []
        try:
            while True:
                line = input()
                if line == "" and lines and lines[-1] == "":
                    break
                lines.append(line)
        except (EOFError, KeyboardInterrupt):
            pass
        task = "\n".join(lines).strip()

    if not task:
        print("[ERROR] 작업 내용이 없습니다.")
        sys.exit(1)

    # 프로젝트 디렉터리 확인
    project_dir = Path(args.dir).resolve()
    if not project_dir.exists():
        print(f"[ERROR] 디렉터리가 없습니다: {project_dir}")
        sys.exit(1)

    # 워크플로우 실행
    log_dir = None if args.no_log else args.log_dir
    orchestrator = WorkflowOrchestrator(base_dir=str(project_dir), log_dir=log_dir)

    try:
        result = orchestrator.run(task)
    except KeyboardInterrupt:
        print("\n\n[중단] 사용자가 워크플로우를 취소했습니다.")
        sys.exit(1)

    # 결과 출력
    print(result.summary())

    # 파일 저장 (옵션)
    if args.save:
        out_dir = Path(args.output_dir)
        out_dir.mkdir(parents=True, exist_ok=True)
        saved_path = result.save(str(out_dir))
        print(f"\n결과가 저장되었습니다: {saved_path}")


if __name__ == "__main__":
    main()
