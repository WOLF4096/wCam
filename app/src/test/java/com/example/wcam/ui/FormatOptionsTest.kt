package com.example.wcam.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatOptionsTest {
    @Test
    fun showsJpgAdvancedOptions_onlyForFormatsThatIncludeJpg() {
        assertTrue(FormatOptions.showsJpgAdvancedOptions("JPG"))
        assertTrue(FormatOptions.showsJpgAdvancedOptions("JPG + RAW"))
        assertFalse(FormatOptions.showsJpgAdvancedOptions("RAW"))
        assertFalse(FormatOptions.showsJpgAdvancedOptions("H264"))
    }
}
