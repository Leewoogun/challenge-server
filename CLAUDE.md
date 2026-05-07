# 협업 규칙

## Git

- **사용자가 명시적으로 요청하기 전에는 절대 커밋하지 않는다.** "커밋해줘", "commit", "push" 같은 직접 지시가 있을 때만 `git commit` 실행. 스킬 가이드(예: brainstorming의 "commit the design doc")가 자동 커밋을 권하더라도 이 규칙이 우선한다.
- 작업 결과는 staging 까지도 자동으로 하지 않는다. 변경 파일을 보여주고 사용자가 직접 또는 지시 후에 커밋한다.
- `git push`, `--force`, `reset --hard`, 브랜치 삭제 등 파괴적/공유 영향 명령은 항상 사전 확인 필요.
