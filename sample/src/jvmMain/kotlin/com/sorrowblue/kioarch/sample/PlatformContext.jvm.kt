package com.sorrowblue.kioarch.sample

@Suppress("AbstractClassCanBeInterface")
actual abstract class PlatformContext

object JvmContext : PlatformContext()
