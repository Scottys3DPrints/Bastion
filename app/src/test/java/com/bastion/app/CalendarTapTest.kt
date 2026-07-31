package com.bastion.app

import com.bastion.app.core.design.dayAtOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.YearMonth

/**
 * Tapping the 14th must log the 14th.
 *
 * An off-by-one here is invisible — the calendar still looks right, the sheet
 * still opens — and it writes a slip against a day that never happened. So the
 * mapping is checked against real months rather than a hand-picked grid, at the
 * corners where padding maths goes wrong: a month starting on a Sunday (six
 * blank leading cells) and one starting on a Monday (none).
 */
class CalendarTapTest {

    /** Mirrors the layout maths in CalendarMonth. */
    private data class Grid(val rows: Int, val leading: Int, val length: Int) {
        val width = 700f
        val height = rows * 100f

        /** Centre of the cell at the given column and row. */
        fun tapCell(col: Int, row: Int) = dayAtOffset(
            x = col * 100f + 50f,
            y = row * 100f + 50f,
            width = width,
            height = height,
            rows = rows,
            leading = leading,
            length = length,
        )

        fun tapDay(day: Int): Int? {
            val index = leading + day - 1
            return tapCell(index % 7, index / 7)
        }
    }

    private fun gridFor(month: YearMonth): Grid {
        val leading = (month.atDay(1).dayOfWeek.value - DayOfWeek.MONDAY.value + 7) % 7
        val length = month.lengthOfMonth()
        return Grid(rows = (leading + length + 6) / 7, leading = leading, length = length)
    }

    @Test
    fun `every day of a month starting on a Sunday maps to itself`() {
        // February 2026 begins on a Sunday: six blank cells before the 1st.
        val month = YearMonth.of(2026, 2)
        assertEquals(DayOfWeek.SUNDAY, month.atDay(1).dayOfWeek)
        val grid = gridFor(month)
        for (day in 1..grid.length) {
            assertEquals("day $day", day, grid.tapDay(day))
        }
    }

    @Test
    fun `every day of a month starting on a Monday maps to itself`() {
        // June 2026 begins on a Monday: no leading pad at all.
        val month = YearMonth.of(2026, 6)
        assertEquals(DayOfWeek.MONDAY, month.atDay(1).dayOfWeek)
        val grid = gridFor(month)
        assertEquals(0, grid.leading)
        for (day in 1..grid.length) {
            assertEquals("day $day", day, grid.tapDay(day))
        }
    }

    @Test
    fun `the blank cells before the first are not days`() {
        val grid = gridFor(YearMonth.of(2026, 2))
        for (col in 0 until grid.leading) {
            assertNull("leading cell $col", grid.tapCell(col, 0))
        }
        assertEquals(1, grid.tapCell(grid.leading, 0))
    }

    @Test
    fun `the blank cells after the last are not days`() {
        val grid = gridFor(YearMonth.of(2026, 2))
        val lastIndex = grid.leading + grid.length - 1
        assertEquals(grid.length, grid.tapCell(lastIndex % 7, lastIndex / 7))
        val afterEnd = lastIndex + 1
        if (afterEnd / 7 < grid.rows) {
            assertNull(grid.tapCell(afterEnd % 7, afterEnd / 7))
        }
    }

    @Test
    fun `taps outside the grid are ignored rather than clamped`() {
        val grid = gridFor(YearMonth.of(2026, 6))
        assertNull("left of the grid", grid.tapCell(-1, 0))
        assertNull("right of the grid", grid.tapCell(7, 0))
        assertNull("below the grid", grid.tapCell(0, grid.rows))
    }

    @Test
    fun `a zero-sized canvas yields nothing`() {
        assertNull(dayAtOffset(0f, 0f, 0f, 0f, rows = 0, leading = 0, length = 30))
    }
}
