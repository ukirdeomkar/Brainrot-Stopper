package dev.brainrotstopper.core.data

import dev.brainrotstopper.core.model.AppRule
import dev.brainrotstopper.core.model.BlockSchedule
import dev.brainrotstopper.core.model.TimeWindow
import java.time.DayOfWeek
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemoryRuleRepositoryTest {

    @Test
    fun `upsert replaces an existing rule by package name`() {
        val repository = InMemoryRuleRepository()

        repository.upsertRule(AppRule("com.example.app", "Example"))
        repository.upsertRule(AppRule("com.example.app", "Example renamed"))

        assertEquals(1, repository.rules().size)
        assertEquals("Example renamed", repository.rules().single().displayName)
    }

    @Test
    fun `remove deletes a rule`() {
        val repository = InMemoryRuleRepository()
        repository.upsertRule(AppRule("com.example.app", "Example"))

        repository.removeRule("com.example.app")

        assertTrue(repository.rules().isEmpty())
    }

    @Test
    fun `schedules round trip`() {
        val repository = InMemoryRuleRepository()
        val schedule = BlockSchedule(
            id = "work",
            label = "Work",
            daysOfWeek = setOf(DayOfWeek.MONDAY),
            windows = listOf(TimeWindow(9 * 60, 17 * 60)),
        )

        repository.upsertSchedule(schedule)

        assertEquals(listOf(schedule), repository.schedules())
        repository.removeSchedule("work")
        assertTrue(repository.schedules().isEmpty())
    }
}
