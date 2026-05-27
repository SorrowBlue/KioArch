---
trigger: always_on
---

# Git Commit Rules

- **Format:**
  Follow the Conventional Commits specification:
  `<type>(<scope>): <description>`

  Types:
  - `feat`: A new feature
  - `fix`: A bug fix
  - `docs`: Documentation changes only
  - `style`: Changes that do not affect the meaning of the code (white-space, formatting, missing semi-colons, etc.)
  - `refactor`: A code change that neither fixes a bug nor adds a feature
  - `perf`: A code change that improves performance
  - `test`: Adding missing tests or correcting existing tests
  - `chore`: Changes to the build process or auxiliary tools and libraries such as documentation generation

- **Subject Line:**
  - Limit the subject line (first line) to 50 characters.
  - Capitalize the first letter (optional, but keep it consistent).
  - Do not end the subject line with a period.
  - Use the imperative mood (e.g., "add feature" instead of "added feature" or "adds feature" - for English).

- **Body (Optional):**
  - Use a blank line between the subject and the body.
  - Wrap lines at 72 characters.
  - Focus on *why* the change was made, rather than *how* (which is visible in the diff).

- **Breaking Changes:**
  - Must be indicated by a `!` after the type/scope (e.g. `feat(core)!: change API`) or as a `BREAKING CHANGE:` footer at the bottom.

- **Language Policies:**
  - **Default:** English is the preferred standard language for git commits in this codebase.
  - **Command `/commit`:** You must generate the commit message in **English**.
  - **Command `/commit-ja`:** You must generate the commit message in **Japanese** (matching standard Japanese developer commits).
