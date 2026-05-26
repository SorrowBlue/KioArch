# AI Agent Guidelines for KioArch

このプロジェクトでは、AIコーディングアシスタント（Antigravity等）とペアプログラミングを行いながら開発を進めます。
開発効率と品質を最大化するため、すべてのAIエージェントは以下のガイドラインを厳守してください。

---

## 1. 成果物の言語規則

- **ドキュメントの日本語化:**
  - 実装計画書（`implementation_plan.md`）
  - 進捗管理タスクリスト（`task.md`）
  - 実装ウォークスルー（`walkthrough.md`）
  - その他すべての開発用成果物・ドキュメントは、**必ず日本語**で記述してください。
- **KDoc（コードコメント）の英語化:**
  - ソースコード内に記述する公開APIや関数のドキュメントコメント（KDoc）は、**必ず英語**で記述してください。

---

## 2. コア設計・開発方針

- **スレッドセーフティの徹底:**
  - JNIハンドルを共有するKotlinプラットフォーム層（JVM/Androidラッパー）は、マルチスレッド環境での並行アクセスを考慮し、適切に排他制御（lockやsynchronized等）を実装してください。
- **プラットフォーム非依存のパス正規化:**
  - アーカイブ（特にZIP）内のファイルパス区切り文字は、Windows形式のバックスラッシュ（`\`）から標準のUnix形式（`/`）へ自動的に正規化して処理してください。
- **堅牢なネットワークI/Oと例外処理:**
  - SMBサーバーなどのリモートストレージ接続を想定し、通信遮断や遅延時にJNI層で例外チェックを確実に行い、メモリリークやグローバル参照リークが一切発生しない構造を維持してください。

---

## 3. 本ガイドラインの継続的な改善について

- **常に改善し続けるドキュメント（Living Document）:**
  - この `AGENT.md` は静的なルールブックではなく、プロジェクトの進行に伴って常に進化し続ける**生きたドキュメント**です。
  - 新たな不具合、隠れた落とし穴（Pitfalls）、独自のコーディング規約、または新たな技術的要件を発見した場合は、**エージェント自身の判断、あるいはユーザーと合意の上で、この `AGENT.md` にルールや知見を追加・更新して改善し続けてください。**

---

## 4. GitHub Issue・リソース管理規則

- **Issue作成時のラベル付与:**
  - AIエージェント（Antigravity等）がGitHub上で新規Issueを作成・登録する際は、エージェントが作成したことを明示し識別しやすくするため、必ず `:robot: antigravity` ラベルを付与して作成してください。

---

## 5. WebAssembly (Emscripten) / JS 相互運用に関する知見 (Pitfalls)

Kotlin/WasmJS ターゲットおよび C++ WebAssembly 間で安全な相互運用を行うために、以下の「落とし穴（Pitfalls）」と解決策を徹底してください。

### ① Kotlin/WasmJS での JS 外部・DOM オブジェクトに対するセーフキャスト (`as?`) の禁止
* **問題**: ブラウザの DOM 要素（`HTMLDivElement` や `HTMLInputElement`）や JS 外部インターフェースに対して `as?`（セーフキャスト）を実行すると、ランタイムの型検証バグにより**常に `null` を返してしまう**制限があります。
* **対策**: あらかじめ非 null であることを確認した上で、**`as`（強制キャスト）** を用いてキャストを行い、スマートキャストを有効にしてください。

### ② JavaScript 相互運用に必要な Emscripten ランタイムヒープ・関数のエクスポート
* **問題**: Kotlin/Wasm 側が C のメモリ空間を直接操作する際、JS ラッパー内の `HEAP32` などのヒープ配列や、`UTF8ToString` などの文字列変換関数を参照します。これらはデフォルトではデッドコード削除 (DCE) され、実行時に `not exported` や `not defined` エラーでクラッシュします。
* **対策**: `CMakeLists.txt` の `-sEXPORTED_RUNTIME_METHODS` リンクオプションに、明示的に `'UTF8ToString'` や必要なヒープ（`'HEAP8'`, `'HEAPU8'`, `'HEAP16'`, `'HEAPU16'`, `'HEAP32'`, `'HEAPU32'`, `'HEAPF32'`, `'HEAPF64'`, `'HEAP64'`, `'HEAPU64'`）を追加してください。

### ③ C 構造体の値渡し (Pass-by-value) によるシグネチャ不一致の回避
* **問題**: C 側の API が構造体の実体（値渡し）を引数に取る場合（例: `kio_open_archive(kio_source_t source, ...)`）、Wasm コンパイル時に Wasm シグネチャがメンバごとに展開されるため、JS/Kotlin 側からポインタ（単一の 32bit 整数値）で呼び出すと `RuntimeError: function signature mismatch` を引き起こします。
* **対策**: Wasm/JS 用のインターフェースには値渡しを直接露出せず、**構造体へのポインタを明示的に受け取る Wasm 相互運用向けのラッパー C 関数**（例: `kio_open_archive_wasm(kio_source_t *source, ...)`）を C 側に定義し、Kotlin からはポインタを介して呼び出してください。

### ④ addFunction におけるコールバックのシグネチャ文字列の厳密性
* **問題**: `addFunction` を使用して JS/Kotlin コールバックを動的登録する際、Wasm の型システム（`i`: 32bit型/ポインタ, `j`: 64bit型, `v`: 戻り値なし 等）とコールバック関数の引数・戻り値を完全に一致させる必要があります。特に `int64_t` は `'j'` (BigInt) に対応します。これを誤って `'viji'` などの不整合なシグネチャで登録すると、C 側から間接呼び出し（`call_indirect`）された瞬間に `signature mismatch` が発生してクラッシュします。
* **対策**: コールバックの引数・戻り値とシグネチャ登録文字列を厳密に一致させてください（例: `(void *opaque, int64_t pos)` で戻り値 `void` の場合は `'vij'`）。

### ⑤ ランタイムユーティリティの module 経由での呼び出し
* **問題**: `UTF8ToString` などの Emscripten ユーティリティはグローバルスコープには存在しないため、直接 `UTF8ToString(...)` と呼び出すと `ReferenceError` になります。
* **対策**: `wasmUtf8ToString` などの `@JsFun` ブリッジ宣言では、必ず `module` インスタンスを第一引数として引き渡し、**`module.UTF8ToString(namePtr)`** のようにインスタンス経由で呼び出してください。

### ⑥ 日本語ファイル名の文字化け問題とエンコーディング自動判定 (Shift_JIS / UTF-8)
* **問題**: Windows 上で作成された ZIP ファイル内のファイル名は多くの場合 **Shift_JIS (CP932)** でエンコードされています。Emscripten の `UTF8ToString` などで単純に UTF-8 デコードを行うと、日本語ファイル名がすべて文字化けを起こします。
* **対策**: `TextDecoder` を用いて UTF-8 と Shift_JIS を自動判別してフォールバックするロジックを JS Interop 層に実装してください。
  ```javascript
  // 1. まず UTF-8 デコードを試みる (fatal: true にしてデコードエラーを検知する)
  try {
      var utf8Decoder = new TextDecoder('utf-8', { fatal: true });
      return utf8Decoder.decode(bytes);
  } catch (e) {
      // 2. 失敗した場合、Shift_JIS (CP932) でデコードする
      var sjisDecoder = new TextDecoder('shift-jis');
      return sjisDecoder.decode(bytes);
  }
  ```

### ⑦ 大容量ファイル解凍時の Wasm メモリ不足 (OOM) 回避
* **問題**: 7z (LZMA/LZMA2) や大容量 ZIP ファイルを解凍する際、解凍アルゴリズムの都合上、大きな一時メモリバッファを C 側で必要とします。Wasm のデフォルトのヒープサイズ上限（16MB）を超えてメモリを確保しようとすると、`Cannot enlarge memory arrays` (OOM) でクラッシュします。
* **対策**: `CMakeLists.txt` の `target_link_options` に、明示的に **`"-sALLOW_MEMORY_GROWTH=1"`** リンクフラグを追加し、実行時にメモリ（HEAP）が必要に応じて自動拡張されるように設定してください。

### ⑧ Kotlin/JS の `js(...)` ブロックにおける変数難読化（Mangle）の罠
* **問題**: Kotlin/JS コンパイラはトランスパイル時に Kotlin 側の変数名（ローカル引数やプロパティ等）を難読化（例: `wasm` を `wasm_0` などにリネーム）します。しかし、`js("...")` に直接記述したコード文字列内の `"wasm.HEAPU8"` などのテキストはそのまま `"wasm"` として出力されるため、実行時に `ReferenceError: wasm is not defined` が発生します。
* **対策**: `js(...)` ブロックを **「引数を受け取る JS 即時関数（IIFE）」** にラップし、Kotlin 側から引数として明示的に引き渡す設計（例：`(js("function(w) { ... }") ...)(wasm)`）に変更し、難読化の影響を完全に無効化してください。

### ⑨ JavaScript の予約語/グローバルオブジェクト `module` との競合
* **問題**: Node.js 等の環境において `module` は CommonJS 仕様のグローバル/ファイルスコープの予約語です。Kotlin 側で `module` という変数名・プロパティ名を使用し、それを `js(...)` 経由で参照しようとすると、CommonJS のグローバル `module` と混同し、`ReferenceError` や `TypeError` を引き起こします。
* **対策**: Interop 関連の Kotlin ファイル内では、`module` という変数名・プロパティ名の使用を完全に避け、**`wasm`** や **`jsModule`** などの別名を使用してください。また、クラスプロパティ（例：`this.handle`）を `js(...)` 内で直接参照するのも難読化による `undefined`（TypeError）の原因となるため、`handle.toString()` などで文字列化した上で IIFE 関数の引数として安全に渡すようにしてください。

### ⑩ Kotlin/JS の `Long` 型内部表現による型不整合（TypeError）の回避
* **問題**: Kotlin/JS では `Long`（64ビット整数）が JS の `number` ではなく `kotlin.Long` クラスのインスタンスとして表現されます。そのため、JS 側から Kotlin 側の `seek(Long)` などのメソッドを `Number(pos)` を渡して直接呼び出そうとしたり、Kotlin の `Long` 戻り値を直接 JS の `BigInt(...)` に渡すと、型不整合による `TypeError` が発生します。
* **対策**: JavaScript との境界部分には、JS の `number` (Double) を引数として受け取り、Kotlin 側で安全に `.toLong()` にキャストして仲介する **「Double 経由の明示的なデータ・ブリッジ関数」** を Kotlin 側に定義して中継してください（例：`bridgeSeek(source, pos)` など）。

### ⑪ JavaScript の生の TypedArray と Kotlin `ByteArray` (Int8Array) の型競合の解消
* **問題**: コールバック内で JS の生の `Uint8Array` を Kotlin の `read(ByteArray)` や `Sink.write` に直接渡すと型不整合による `TypeError` が発生します。また、`js(...)` 内で Kotlin の配列を直接操作することも難読化バグの原因になります。
* **対策**: `js(...)` を完全に排除し、**100% 純粋な Kotlin/JS 標準の WebGL / TypedArray API（`org.khronos.webgl.Int8Array` / `Uint8Array`）** のみを用いて、Kotlin 側で配列を生成してネイティブ一括コピー（`set`）を行ってから Kotlin API を呼び出してください。
  - `wasm.HEAPU8.set(new Uint8Array(tmpArray.buffer, tmpArray.byteOffset, bytesRead), bufPtr)` などのコピーは、Kotlin/JS 側で `tmpArray.asDynamic() as org.khronos.webgl.Int8Array` からビューを作成して純粋な Kotlin で記述可能です。

