import kotlin.test.Test
import kotlin.test.assertEquals

class VersionParserTest {

    @Test
    fun testNormalTaggedReleases() {
        assertEquals("1.2.3", VersionParser.parse("v1.2.3"))
        assertEquals("11.22.33-alpha.4", VersionParser.parse("v11.22.33-alpha.4"))
        assertEquals("11.22.33-beta.5", VersionParser.parse("v11.22.33-beta.5"))
        assertEquals("111.222.333-rc.6", VersionParser.parse("v111.222.333-rc.6"))
    }

    @Test
    fun testTaggedReleasesWithDirty() {
        assertEquals("1.2.3-dirty", VersionParser.parse("v1.2.3-dirty"))
        assertEquals("11.22.33-alpha.4-dirty", VersionParser.parse("v11.22.33-alpha.4-dirty"))
        assertEquals("111.222.333-rc.6-dirty", VersionParser.parse("v111.222.333-rc.6-dirty"))
    }

    @Test
    fun testNoTagOnHeadSnapshotReleases() {
        // プレリリースなしから進んだ場合
        assertEquals("1.2.4-SNAPSHOT", VersionParser.parse("v1.2.3-4-g1a2b3c4"))
        
        // プレリリースありから進んだ場合（プレリリースが消えてパッチがインクリメントされ、SNAPSHOTになる）
        assertEquals("11.22.34-SNAPSHOT", VersionParser.parse("v11.22.33-alpha.4-5-g6c7d8e9"))
        assertEquals("111.222.334-SNAPSHOT", VersionParser.parse("v111.222.333-rc.6-10-gabcdef0"))
    }

    @Test
    fun testNoTagOnHeadSnapshotReleasesWithDirty() {
        assertEquals("1.2.4-SNAPSHOT", VersionParser.parse("v1.2.3-4-g1a2b3c4-dirty"))
        assertEquals("11.22.34-SNAPSHOT", VersionParser.parse("v11.22.33-alpha.4-5-g6c7d8e9-dirty"))
    }

    @Test
    fun testEdgeCasesAndTrimming() {
        // 前後に空白がある場合、トリムされて正しく処理されるべき
        assertEquals("1.2.3", VersionParser.parse("  v1.2.3  "))
        assertEquals("111.222.334-SNAPSHOT", VersionParser.parse("\n v111.222.333-rc.6-10-gabcdef0 \t"))
        
        // パッチインクリメント時の桁上がりテスト
        assertEquals("1.2.10-SNAPSHOT", VersionParser.parse("v1.2.9-1-g1234567"))
        assertEquals("1.9.10-SNAPSHOT", VersionParser.parse("v1.9.9-1-g1234567"))
    }

    @Test
    fun testInvalidAndFallbackCases() {
        val defaultVal = VersionParser.DEFAULT_VERSION

        // 空文字や空白のみ
        assertEquals(defaultVal, VersionParser.parse(""))
        assertEquals(defaultVal, VersionParser.parse("   "))
        
        // vで始まらない（コミットハッシュのみ）
        assertEquals(defaultVal, VersionParser.parse("1a2b3c4"))
        assertEquals(defaultVal, VersionParser.parse("1a2b3c4-dirty"))
        
        // メジャー・マイナーのみ
        assertEquals(defaultVal, VersionParser.parse("v1.2"))
        
        // サポート外のプレリリース名（今回の正規表現は alpha, beta, rc のみ対象）
        assertEquals(defaultVal, VersionParser.parse("v1.2.3-dev.1"))
        assertEquals(defaultVal, VersionParser.parse("v1.2.3-milestone.2"))

        // 不正な文字列
        assertEquals(defaultVal, VersionParser.parse("va.b.c"))
        assertEquals(defaultVal, VersionParser.parse("v1.2.3-alpha")) // ドットと数値がない
    }
}
