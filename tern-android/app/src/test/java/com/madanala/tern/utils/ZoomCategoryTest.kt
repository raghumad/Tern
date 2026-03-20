package com.madanala.tern.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomCategoryTest {

    @Test
    fun testFromZoomMapping() {
        // Detail
        assertEquals(ZoomCategory.DETAIL, ZoomCategory.fromZoom(15.0))
        assertEquals(ZoomCategory.DETAIL, ZoomCategory.fromZoom(13.0))

        // Intermediate
        assertEquals(ZoomCategory.INTERMEDIATE, ZoomCategory.fromZoom(12.9))
        assertEquals(ZoomCategory.INTERMEDIATE, ZoomCategory.fromZoom(10.0))

        // Regional
        assertEquals(ZoomCategory.REGIONAL, ZoomCategory.fromZoom(9.9))
        assertEquals(ZoomCategory.REGIONAL, ZoomCategory.fromZoom(7.0))

        // Continental
        assertEquals(ZoomCategory.CONTINENTAL, ZoomCategory.fromZoom(6.9))
        assertEquals(ZoomCategory.CONTINENTAL, ZoomCategory.fromZoom(0.0))
    }

    @Test
    fun testCategoryProperties() {
        // Detail properties
        val detail = ZoomCategory.DETAIL
        assertEquals(0.5f, detail.iconScale)
        assertEquals(1.0f, detail.iconAlpha)
        assertEquals(20.0, detail.queryRadiusKm, 0.001)
        assertEquals(true, detail.showHazardIndicators)

        // Continental properties
        val continental = ZoomCategory.CONTINENTAL
        assertEquals(0.15f, continental.iconScale)
        assertEquals(0.4f, continental.iconAlpha)
        assertEquals(1000.0, continental.queryRadiusKm, 0.001)
        assertEquals(false, continental.showHazardIndicators)
    }
}
