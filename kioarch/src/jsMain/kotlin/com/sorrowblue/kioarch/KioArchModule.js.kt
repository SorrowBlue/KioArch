package com.sorrowblue.kioarch

import kotlin.js.Promise

@JsName("createKioArchModule")
private external fun createKioArchModuleJs(): Promise<Any>

public actual fun loadKioArchModule(): Promise<Any> {
    return createKioArchModuleJs()
}
