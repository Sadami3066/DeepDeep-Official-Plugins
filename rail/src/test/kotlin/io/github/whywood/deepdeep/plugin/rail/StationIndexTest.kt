package io.github.whywood.superpi.plugin.rail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationIndexTest {
    private fun index(): StationIndex = StationIndex(
        listOf(
            Station("VAP", "北京北", "北京", "beijingbei", "bjb"),
            Station("BOP", "北京东", "北京", "beijingdong", "bjd"),
            Station("BJP", "北京", "北京", "beijing", "bj"),
            Station("VNP", "北京南", "北京", "beijingnan", "bjn"),
            Station("BXP", "北京西", "北京", "beijingxi", "bjx"),
            Station("LTU", "练塘", "上海", "liantang", "lt"),
            Station("SHH", "上海", "上海", "shanghai", "sh"),
            Station("SNH", "上海南", "上海", "shanghainan", "shn"),
            Station("AOH", "上海虹桥", "上海", "shanghaihongqiao", "shhq"),
            Station("GBQ", "广州北", "广州", "guangzhoubei", "gzb"),
            Station("GGQ", "广州东", "广州", "guangzhoudong", "gzd"),
            Station("GZQ", "广州", "广州", "guangzhou", "gz"),
            Station("IZQ", "广州南", "广州", "guangzhounan", "gzn"),
            Station("HGH", "杭州东", "杭州", "hangzhoudong", "hzd"),
            Station("HZH", "杭州", "杭州", "hangzhou", "hz"),
            Station("XHH", "杭州南", "杭州", "hangzhounan", "hzn"),
            Station("TJP", "天津", "天津", "tianjin", "tj"),
            Station("TBP", "天津北", "天津", "tianjinbei", "tjb"),
            Station("XJA", "香港西九龙", "香港", "xianggangxijiulong", "xgxjl"),
            Station("JJB", "香港红磡", "香港", "xiangganghongkan", "xghk"),
            Station("SZH", "苏州", "苏州", "suzhou", "sz"),
            Station("SUH", "宿州", "宿州", "suzhou", "szh"),
        ),
    )

    private fun stationName(query: String): String? {
        val resolution = index().resolve(query)
        return (resolution as? StationResolution.Found)?.station?.name
    }

    @Test
    fun cityNamesResolveToSameNamedStation() {
        assertEquals("北京", stationName("北京"))
        assertEquals("上海", stationName("上海"))
        assertEquals("广州", stationName("广州"))
        assertEquals("杭州", stationName("杭州"))
        assertEquals("天津", stationName("天津"))
    }

    @Test
    fun exactStationNamesStillResolve() {
        assertEquals("北京北", stationName("北京北"))
        assertEquals("北京南", stationName("北京南"))
        assertEquals("上海虹桥", stationName("上海虹桥"))
        assertEquals("练塘", stationName("练塘"))
        assertEquals("广州北", stationName("广州北"))
        assertEquals("广州东", stationName("广州东"))
        assertEquals("广州南", stationName("广州南"))
    }

    @Test
    fun stationSuffixesAndCitySuffixesAreAccepted() {
        assertEquals("北京", stationName("北京站"))
        assertEquals("北京南", stationName("北京南站"))
        assertEquals("上海", stationName("上海市"))
        assertEquals("广州", stationName("广州市"))
    }

    @Test
    fun pinyinResolvesToStation() {
        assertEquals("北京", stationName("beijing"))
        assertEquals("北京北", stationName("beijingbei"))
        assertEquals("上海", stationName("shanghai"))
        assertEquals("广州", stationName("guangzhou"))
        assertEquals("杭州", stationName("hangzhou"))
        assertEquals("北京南", stationName("beijingnan"))
    }

    @Test
    fun cityWithoutSameNamedStationReturnsCandidates() {
        val resolution = index().resolve("香港")

        assertTrue(resolution is StationResolution.Candidates)
        val names = (resolution as StationResolution.Candidates).stations.map { it.name }
        assertEquals(listOf("香港西九龙", "香港红磡"), names)
    }

    @Test
    fun unknownStationIsNotFound() {
        assertTrue(index().resolve("不存在的地方") is StationResolution.NotFound)
        assertTrue(index().resolve("") is StationResolution.NotFound)
        assertTrue(index().resolve("   ") is StationResolution.NotFound)
    }

    @Test
    fun cityStationNamesAreResolvedByCode() {
        val index = index()
        assertEquals("北京", index.nameOf("BJP"))
        assertEquals("杭州", index.nameOf("HZH"))
        assertEquals("广州", index.nameOf("GZQ"))
        assertEquals(2, index.stationsOfCity("香港").size)
    }

    @Test
    fun ambiguousPinyinReturnsCandidates() {
        val resolution = index().resolve("suzhou")

        assertTrue(resolution is StationResolution.Candidates)
        assertEquals(
            listOf("苏州", "宿州"),
            (resolution as StationResolution.Candidates).stations.map { it.name },
        )
    }
}
