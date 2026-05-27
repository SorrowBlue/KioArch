# Workflow: Plan (/plan)

This workflow enforces a strict **planning-only** mode when a user issues the `/plan` command or requests a new task design.

- **Objective:**
  Investigate the codebase, analyze the requirements, and draft a high-quality implementation plan without making any modifications to the actual project source code.

- **Constraints & Instructions:**
  1. **Strictly Read-Only:** Do not modify, create, or delete any project files or directories. Running tests or build diagnostic commands is allowed, but modifying the source code is strictly prohibited.
  2. **Codebase Exploration:** Explore the code, find references, and understand dependencies using search tools.
  3. **Draft Implementation Plan:** Create or update `implementation_plan.md` in the artifacts directory. Write the plan in **Japanese**.
  4. **Structure of `implementation_plan.md`:**
     - **Goal:** Clear summary of the request.
     - **Proposed Changes:** Logical grouping of files to change, add, or delete (using absolute links).
     - **Verification Plan:** Outline how you will verify changes (automated tests, manual validation, etc.).
     - **Open Questions / Considerations:** Highlight any design trade-offs, breaking changes, or uncertainties using warning alerts.
  5. **Ask for Approval:** Once the plan is saved, set `request_feedback = true` in the metadata, summarize the key design decisions, and stop your turn to wait for user approval.
