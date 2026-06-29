package com.example.wcam.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class FlashModeTest {
    @Test
    fun flashModeLabels_matchUiText() {
        assertEquals("关闭", FlashMode.OFF.label)
        assertEquals("打开", FlashMode.SINGLE.label)
        assertEquals("常亮", FlashMode.TORCH.label)
    }
}
