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

internal actual fun initBenchmarkPlatform() {
    if (isWasmInitialized) return
    val loader = js(
        """
        function() {
            var req = eval('require');
            var fs = req('node:fs');
            var path = req('node:path');
            var candidates = [
                path.resolve('./natives'),
                path.resolve('./kotlin/natives'),
                path.resolve(__dirname, './natives'),
                path.resolve(__dirname, './kotlin/natives'),
                path.resolve(__dirname, '../../../../../kioarch/build/generated/wasm/natives'),
                path.resolve(__dirname, '../../../../kioarch/build/generated/wasm/natives'),
                path.resolve(__dirname, '../../../kioarch/build/generated/wasm/natives')
            ];
            var nativesDir = candidates.find(function(dir) {
                return fs.existsSync(path.join(dir, 'kioarch.js')) &&
                    fs.existsSync(path.join(dir, 'kioarch.wasm'));
            });
            if (!nativesDir) {
                throw new Error('Could not find kioarch.js / kioarch.wasm in ' + candidates.join(', '));
            }
            var jsPath = path.join(nativesDir, 'kioarch.js');
            var jsSource = fs.readFileSync(jsPath, 'utf8')
                .replace(/async\s+function\s*\(\s*moduleArg\s*=\s*\{\}\s*\)/, 'function(moduleArg = {})')
                .replace(/wasmExports\s*=\s*await\s*\(createWasm\(\)\);/, '(createWasm());');
            var cjsModule = { exports: {} };
            var fn = new Function('module', 'exports', 'require', '__filename', '__dirname', jsSource);
            fn(cjsModule, cjsModule.exports, req, jsPath, nativesDir);
            var createKioArchModule = cjsModule.exports;
            var wasmBytes = fs.readFileSync(path.join(nativesDir, 'kioarch.wasm'));
            var wasmMod = new WebAssembly.Module(wasmBytes);
            var wasmModule = createKioArchModule({
                instantiateWasm: function(imports, successCallback) {
                    var instance = new WebAssembly.Instance(wasmMod, imports);
                    successCallback(instance, wasmMod);
                    return instance.exports;
                }
            });
            return wasmModule;
        }
        """
    )
    val wasmModule = loader() as Any
    KioArch.initialize(wasmModule)
    isWasmInitialized = true
}
