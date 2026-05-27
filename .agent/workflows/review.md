# Workflow: Review (/review)

This workflow defines the procedure when a user requests a code review via the `/review` command.

- **Objective:**
  Perform a rigorous, detailed review of modified or targeted files in the codebase, identifying bugs, style issues, and architectural alignment.

- **Review Dimensions:**
  1. **Correctness & Platform Safety:** Check for memory leaks, thread-safety issues, JNI exceptions, and Wasm-specific pitfalls (e.g., safe casts, memory OOM, etc.). Refer to `.agent/rules/kioarch-pitfalls.md` for specifics.
  2. **Code Style & Documentation:** Verify that code comments (KDocs) are written in standard English, and the code follows standard conventions.
  3. **Architectural Alignment:** Ensure streaming behaviors use `kotlinx.io.Sink` properly and match the KioArch dynamic execution model.

- **Instructions:**
  1. **Identify Changes:** Inspect changed files using git commands or file-reading tools.
  2. **Categorize Issues:** Classify findings by severity: Critical (must fix), Major (should fix), or Minor/Suggestion (opinion).
  3. **Provide Proposals:** Show clear before/after diffs or code examples for your suggestions.
  4. **Output:** Write the review results in **Japanese**.
