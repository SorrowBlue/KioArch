# Workflow: Issue (/issue)

This workflow defines the process when a user issues the `/issue` command or requests information about a specific GitHub issue.

- **Objective:**
  Fetch the details of the specified GitHub issue using the GitHub CLI (`gh`), handle potential token/credential issues robustly, and present a clear summary of the issue to the user.

- **Constraints & Instructions:**
  1. **Fetch Issue Information:**
     - Promptly execute the GitHub CLI to view the issue.
     - Command: `gh issue view <issue_number>`
  2. **Robust Token Handling:**
     - If the command fails with `HTTP 401: Bad credentials`, it is highly likely that an invalid `GITHUB_TOKEN` is set in the environment.
     - Automatically retry by clearing the `GITHUB_TOKEN` environment variable for the command execution (e.g. `$env:GITHUB_TOKEN=$null; gh issue view <issue_number>` in PowerShell).
  3. **Analyze & Summarize in Japanese:**
     - Once the issue details are retrieved, carefully analyze the goal, proposed changes, pitfalls, and context.
     - Present a structured summary in **Japanese** to the user in the chat.
  4. **Suggest Next Steps:**
     - After summarizing the issue, politely ask the user if they would like to proceed with creating an implementation plan (via `/plan`) or discuss the design details.
