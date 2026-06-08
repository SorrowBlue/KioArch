package com.sorrowblue.kioarch.sample.io

import com.sorrowblue.kioarch.sample.PlatformContext
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.io.RawSink

/**
 * 現在の位置の子ディレクトリの位置を返す
 */
internal expect fun PlatformFile.div2(child: String): PlatformFile

/**
 * 現在の位置のSinkを返す
 */
internal expect fun PlatformFile.sink2(): RawSink

/**
 * 現在の位置のディレクトリを作成する
 */
internal expect fun PlatformFile.createDirectories2()

/**
 * nameのファイルを作成する
 */
internal expect fun PlatformFile.createFile(context: PlatformContext, name: String): PlatformFile
