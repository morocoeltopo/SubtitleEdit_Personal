package com.subtitleedit.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CutPasteControllerTest {

    @Test
    fun initialState_noPendingCut() {
        val controller = CutPasteController()
        assertFalse(controller.hasPendingCut())
        assertTrue(controller.snapshotDeletedIndices().isEmpty())
        assertEquals(7, controller.adjustPastePositionAfterCut(7))
        assertTrue(controller.consumeDeletedIndicesDesc().isEmpty())
    }

    @Test
    fun markSingleCut_tracksPosition() {
        val controller = CutPasteController()
        controller.markSingleCut(3)
        assertTrue(controller.hasPendingCut())
        assertEquals(setOf(3), controller.snapshotDeletedIndices())
    }

    @Test
    fun markMultiCut_deduplicatesAndSortsDescending() {
        val controller = CutPasteController()
        controller.markMultiCut(listOf(5, 2, 5))
        assertEquals(setOf(5, 2), controller.snapshotDeletedIndices())
        assertEquals(listOf(5, 2), controller.consumeDeletedIndicesDesc())
    }

    @Test
    fun adjustPastePosition_shiftsByCutsBeforePosition() {
        val controller = CutPasteController()
        controller.markMultiCut(listOf(5, 2))
        // 位置 7 前面有 2 个被剪切的条目
        assertEquals(5, controller.adjustPastePositionAfterCut(7))
        // 位置 4 前面只有位置 2 被剪切
        assertEquals(3, controller.adjustPastePositionAfterCut(4))
        // 位置 1 前面没有被剪切的条目
        assertEquals(1, controller.adjustPastePositionAfterCut(1))
        // 等于剪切位置本身不计入偏移
        assertEquals(2, controller.adjustPastePositionAfterCut(2))
    }

    @Test
    fun adjustPastePosition_clampsAtZero() {
        val controller = CutPasteController()
        controller.markMultiCut(listOf(0))
        assertEquals(0, controller.adjustPastePositionAfterCut(0))
        assertEquals(0, controller.adjustPastePositionAfterCut(1))
    }

    @Test
    fun consume_returnsDescendingAndClearsState() {
        val controller = CutPasteController()
        controller.markMultiCut(listOf(1, 4, 2))
        assertEquals(listOf(4, 2, 1), controller.consumeDeletedIndicesDesc())
        assertFalse(controller.hasPendingCut())
        // 消费后位置不再偏移
        assertEquals(7, controller.adjustPastePositionAfterCut(7))
    }

    @Test
    fun clear_resetsState() {
        val controller = CutPasteController()
        controller.markSingleCut(3)
        controller.clear()
        assertFalse(controller.hasPendingCut())
        assertEquals(3, controller.adjustPastePositionAfterCut(3))
    }
}
