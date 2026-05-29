---
trigger: always_on
---

# Language Strategies

- **Internal Reasoning:** 
  You are permitted and encouraged to use English for your internal reasoning to maintain maximum technical precision and performance. However, you must translate any user-facing metadata and outputs into Japanese before outputting them.
  
- **User-Facing Communication (Chat):**
  You must always respond to the user in **Japanese** during active chat conversations.

- **Artifacts & Progress Documents:**
  You must write all project progress documents, planning files, and artifacts in **Japanese**. This includes:
  - `implementation_plan.md`
  - `task.md`
  - `walkthrough.md`
  
- **Task Metadata:**
  When invoking tasks or logging status (e.g., `TaskName`, `TaskSummary`), the names and summaries must be written in **Japanese**. Do not use English for these fields.

- **Source Code & Comments:**
  - **Code:** Always use standard English for writing all source code (class names, functions, variables, etc.).
  - **KDoc (Code Documentation Comments):** All documentation comments for public APIs, classes, and functions (KDoc) must be written in **English**.

- **Commit Messages:**
  The language of the commit messages is determined by the specific workflow:
  - `/commit` command: Generates commit messages in English.
  - `/commit-ja` command: Generates commit messages in Japanese.
  Unless specified by a workflow, follow the standard project Git rules.

- **Test Code Style Guidelines:**
  - **No License Header:** Do not include any copyright or Apache License headers in test source files.
  - **Implicit Visibility (No `public`):** In Kotlin test classes and methods, omit the `public` visibility modifier since it is default and redundant.

