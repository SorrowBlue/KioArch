/*
 * Copyright 2026 SorrowBlue
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.sorrowblue.kioarch.benchmark

import com.sorrowblue.kioarch.KioArch
import com.sorrowblue.kioarch.initialize

private var isWasmInitialized = false

@JsFun(
    """((loadedModule) => () => loadedModule)(
        await (async () => {
            if (typeof process === 'undefined' || !process.versions || !process.versions.node) {
                return null;
            }
            const fsMod = await import(/* webpackIgnore: true */ 'node:fs');
            const pathMod = await import(/* webpackIgnore: true */ 'node:path');
            const urlMod = await import(/* webpackIgnore: true */ 'node:url');
            const moduleMod = await import(/* webpackIgnore: true */ 'node:module');
            const fs = fsMod.default || fsMod;
            const path = pathMod.default || pathMod;
            const createRequire = moduleMod.createRequire || (moduleMod.default && moduleMod.default.createRequire);
            const customRequire = createRequire(import.meta.url);
            const currentDir = path.dirname(urlMod.fileURLToPath(import.meta.url));
            const candidates = [
                path.resolve('./natives'),
                path.resolve('./kotlin/natives'),
                path.resolve(currentDir, './natives'),
                path.resolve(currentDir, './kotlin/natives'),
                path.resolve(currentDir, '../../../../../kioarch/build/generated/wasm/natives'),
                path.resolve(currentDir, '../../../../kioarch/build/generated/wasm/natives'),
                path.resolve(currentDir, '../../../kioarch/build/generated/wasm/natives')
            ];
            const nativesDir = candidates.find((dir) =>
                fs.existsSync(path.join(dir, 'kioarch.js')) &&
                fs.existsSync(path.join(dir, 'kioarch.wasm'))
            );
            if (!nativesDir) return null;
            const createKioArchModule = customRequire(path.join(nativesDir, 'kioarch.js'));
            return await createKioArchModule({
                locateFile: (file) => path.join(nativesDir, file)
            });
        })()
    )"""
)
private external fun getPreloadedWasmModule(): JsAny?

internal actual fun initBenchmarkPlatform() {
    if (isWasmInitialized) return
    val module = getPreloadedWasmModule()
        ?: error("Failed to preload KioArch Wasm module for benchmark")
    KioArch.initialize(module)
    isWasmInitialized = true
}
