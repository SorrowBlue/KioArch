package com.sorrowblue.kioarch.sample.components

import androidx.compose.ui.graphics.ImageBitmap

expect fun decodeByteArrayToImageBitmap(bytes: ByteArray): ImageBitmap
