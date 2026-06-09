package com.sorrowblue.kioarch

import kotlin.js.Promise

@JsFun("() => createKioArchModule()")
private external fun createKioArchModuleWasm(): Promise<JsAny>

public actual fun loadKioArchModule(): Promise<JsAny> {
    return createKioArchModuleWasm()
}
