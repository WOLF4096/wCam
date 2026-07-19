package com.example.wcam.ui

object FormatOptions {
    /**
     * Returns true if the selected output formats include JPEG.
     * formatString is a comma-separated list like "JPG,RAW" or "JPG".
     */
    fun showsJpgAdvancedOptions(formatString: String): Boolean {
        return formatString.contains("JPG")
    }
}
