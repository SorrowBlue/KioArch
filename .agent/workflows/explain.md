# Workflow: Explain (/explain)

This workflow defines the procedure when a user requests an explanation of specific classes, functions, or workflows using the `/explain` command.

- **Objective:**
  Deconstruct the code logic, control flow, and data flow of targeted components to provide a clear, educational overview of how the code operates.

- **Explanation Structure:**
  1. **High-Level Purpose:** What is the primary role of this class/function in the KioArch system?
  2. **Data Flow & Memory Management:** How does data flow in and out of the component? How is memory allocated and freed (especially across JNI or Wasm boundaries)?
  3. **Key Logical Sections:** Break down critical parts of the code step-by-step.
  4. **Corner Cases & Error Handling:** How does this component behave under exceptional or edge-case conditions?

- **Instructions:**
  1. **Trace Control Flow:** Investigate callers and callees using codebase search tools.
  2. **Ground with Links:** Refer to actual code lines using markdown file links with line ranges.
  3. **Output:** Deliver the explanation in **Japanese**.
