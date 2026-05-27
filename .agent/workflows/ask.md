# Workflow: Ask (/ask)

This workflow enforces a strict **read-only, consultative** mode when a user issues the `/ask` command or asks questions about architecture, algorithms, or code behavior.

- **Objective:**
  Respond to technical questions, discuss design proposals, and provide deep architectural guidance without modifying the project repository.

- **Constraints & Instructions:**
  1. **Strictly Consultative:** You must not create, modify, or delete any source files. You may search and read files to provide precise answers.
  2. **Code Examples:** When providing code snippets, explain the design rationale, potential trade-offs, and compliance with the codebase conventions. Ensure comments in snippets follow the language rules (English for code/KDoc).
  3. **Respond in Japanese:** Answer the user's questions clearly in **Japanese**.
  4. **Reference Code:** Use links to files and line ranges (e.g. `[main.kt](file:///path/to/main.kt#L10-L20)`) to ground your explanations in the actual code.
