# AI Agent Guidelines for KioArch (SSoT)

このファイルは、KioArchプロジェクトにおけるAIエージェント（Antigravity等）の全体ガイドラインとなるSingle Source of Truth (SSoT) です。

## 1. プロジェクト概要

KioArchは、Kotlin Multiplatform (KMP) を用いて、ZIP、7z、TarGzなどのアーカイブファイルをオンデマンドで（ファイルシステムを介さずメモリ上で直接）高速に解凍・操作するためのコアライブラリです。
C/C++ ネティブコード (CSzArEx / miniz等)、JNI、および WebAssembly (Emscripten) / JS 相互運用が高度に絡み合うアーキテクチャを採用しています。

---

## 2. エージェントルールとワークフローの参照先

AIエージェントの具体的な振る舞い、開発ルール、および手順は `.agent/` ディレクトリ配下に定義されています。エージェントはセッション開始時にこれらのルールを自動的、またはコマンド経由で読み込みます。

- **常時適用ルール (`.agent/rules/`)**:
  - [言語戦略 (`language-strategies.md`)](file:///d:/KioArch/.agent/rules/language-strategies.md): ドキュメントは日本語、コードとKDocは英語で記述するバイリンガル制御。
  - [行動規範 (`senior-engineer-conduct.md`)](file:///d:/KioArch/.agent/rules/senior-engineer-conduct.md): シニアエンジニアとしての開発姿勢、計画（Planning Mode）と実行（Execution Mode）の徹底。
  - [Gitコミット規約 (`git-commit-rules.md`)](file:///d:/KioArch/.agent/rules/git-commit-rules.md): コミットメッセージの形式定義（英語/日本語コマンド対応）。
  - [Wasm/JS/JNI開発の落とし穴 (`kioarch-pitfalls.md`)](file:///d:/KioArch/.agent/rules/kioarch-pitfalls.md): Kotlin/Wasm、Emscripten、JNI、メモリ管理、スレッドセーフティ、データフローなどの最重要技術知見。

- **ワークフロー（スラッシュコマンド） (`.agent/workflows/`)**:
  - [計画作成 `/plan` (`plan.md`)](file:///d:/KioArch/.agent/workflows/plan.md): コード変更を行わず、調査と実装計画書（`implementation_plan.md`）の作成に専念する手順。
  - [技術相談 `/ask` (`ask.md`)](file:///d:/KioArch/.agent/workflows/ask.md): 完全読み取り専用で、設計相談やコードの挙動質問に答える手順。
  - [コードレビュー `/review` (`review.md`)](file:///d:/KioArch/.agent/workflows/review.md): 正確性、バグ、落とし穴の有無、スタイルを一貫して評価する手順。
  - [コード解説 `/explain` (`explain.md`)](file:///d:/KioArch/.agent/workflows/explain.md): 特定のロジックやデータフロー、メモリ管理を詳細に解説する手順。

---

## 3. プロジェクト共通コマンド

エージェントがビルドや検証を行う際は、以下のコマンドを使用してください。

- **ビルドとテスト (JVM & Android)**:
  ```powershell
  ./gradlew test
  ```
- **Wasm/JS Node.js環境でのテスト**:
  ```powershell
  ./gradlew jsNodeTest
  ```
- **静的解析 (Detekt)**:
  ```powershell
  ./gradlew detekt
  ```

---

## 4. 本ガイドラインの継続的な改善について

- **常に改善し続けるドキュメント（Living Document）**:
  - この `AGENTS.md` および `.agent/rules/kioarch-pitfalls.md` は、プロジェクトの進行に伴って常に進化し続ける**生きた知見**です。
  - 新たな不具合、隠れた落とし穴（Pitfalls）、または新たな技術的要件を発見した場合は、**エージェント自身の判断、あるいはユーザーと合意の上で、必ずルールや知見を追加・更新して改善し続けてください。**

---

## 5. GitHub Issue・リソース管理規則

- **Issue作成時のラベル付与**:
  - AIエージェント（Antigravity等）がGitHub上で新規Issueを作成・登録する際は、エージェントが作成したことを明示し識別しやすくするため、必ず `:robot: antigravity` ラベルを付与して作成してください。
