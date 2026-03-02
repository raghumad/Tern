# Consolidate Docs Skill

This skill governs the organization and maintenance of the project's centralized documentation.

## 🎯 Objective
Ensure all project documentation is discoverable, well-structured, and accurate.

## 🛠️ Instructions

### 1. Structure Maintenance
Ensure files are placed in the correct sub-directory:
*   `docs/architecture/`: System design and core patterns.
*   `docs/features/`: Feature-specific analysis and plans.
*   `docs/guides/`: Actionable development and performance tips.
*   `docs/archive/`: Historical state and completed plans.

### 2. Documentation Standards
*   Use relative paths for internal linking.
*   Keep files concise and focused on a single topic.
*   Prune obsolete content into the `archive/` folder.

### 3. Link Verification
Whenever moving a file:
*   Update individual links in the root `README.md`.
*   Verify that any other `.md` files pointing to it are updated.

### 📜 Related Principles
*   **Offline-First**: Documentation should be as accessible as the code.
*   **Transparency**: Keep architectural decisions clear and documented.
