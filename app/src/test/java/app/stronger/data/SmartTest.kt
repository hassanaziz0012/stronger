package app.stronger.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset

class SmartTest {
    private val day = 86_400_000L
    private val min = 60_000L
    private val now = 1_000 * day

    private val bench = ExerciseEntity(id = 1, name = "Bench Press (Barbell)", bodyPart = BodyPart.CHEST, equipment = Equipment.BARBELL)
    private val pullUp = ExerciseEntity(id = 2, name = "Pull Up", bodyPart = BodyPart.BACK, equipment = Equipment.REPS_ONLY)
    private val info = mapOf(1L to bench, 2L to pullUp)

    private fun set(w: Double?, r: Int?, done: Boolean = false, type: SetType = SetType.NORMAL, doneAt: Long? = null) =
        SessionSet(key = Keys.next(), type = type, weightKg = w, reps = r, done = done, doneAt = doneAt)

    private fun ex(vararg sets: SessionSet, id: Long = 1) = SessionExercise(key = Keys.next(), exerciseId = id, sets = sets.toList())

    // ---- Record chances ----

    private val bests = Bests(weight = 100.0, oneRm = 100.0 * (1 + 5 / 30.0), volume = 600.0, reps = 6)

    @Test fun heavierThanEverIsARecordNow() {
        val e = ex(set(102.5, 3))
        assertEquals(RecordChance.NOW, recordChance(e, 0, null, TrackingType.WEIGHT_REPS, bests))
    }

    @Test fun oneMoreRepAtTheBestWeightIsFlagged() {
        // 100 × 5 matches the best est. 1RM exactly; 6 reps beats it.
        val e = ex(set(null, null))
        assertEquals(RecordChance.ONE_MORE_REP, recordChance(e, 0, set(100.0, 5), TrackingType.WEIGHT_REPS, bests))
    }

    @Test fun easySetsHaveNoChance() {
        assertNull(recordChance(ex(set(60.0, 5)), 0, null, TrackingType.WEIGHT_REPS, bests))
    }

    @Test fun recordsSetEarlierTodayRaiseTheBar() {
        val e = ex(set(102.5, 3, done = true), set(102.5, 3))
        assertNull(recordChance(e, 1, null, TrackingType.WEIGHT_REPS, bests))
    }

    @Test fun noHistoryAndWarmupsHaveNoChance() {
        assertNull(recordChance(ex(set(200.0, 5)), 0, null, TrackingType.WEIGHT_REPS, null))
        assertNull(recordChance(ex(set(200.0, 5, type = SetType.WARMUP)), 0, null, TrackingType.WEIGHT_REPS, bests))
    }

    @Test fun bodyweightRepsCompareAgainstBestReps() {
        assertEquals(RecordChance.ONE_MORE_REP, recordChance(ex(set(null, 6), id = 2), 0, null, TrackingType.REPS, bests))
        assertEquals(RecordChance.NOW, recordChance(ex(set(null, 7), id = 2), 0, null, TrackingType.REPS, bests))
    }

    // ---- Typos ----

    private fun suspects(vararg exercises: SessionExercise, b: Map<Long, Bests> = mapOf(1L to bests, 2L to Bests(reps = 12))) =
        suspiciousSets(Session(name = "W", exercises = exercises.toList()), b) { info[it] }

    @Test fun aLostDecimalPointIsSuspicious() {
        val found = suspects(ex(set(1000.0, 5, done = true)))
        assertEquals(SuspectField.WEIGHT, found.single().field)
        assertEquals(100.0, found.single().best, 0.0)
    }

    @Test fun anExtraRepDigitIsSuspicious() {
        assertEquals(SuspectField.REPS, suspects(ex(set(null, 120, done = true), id = 2)).single().field)
        assertEquals(SuspectField.REPS, suspects(ex(set(80.0, 50, done = true))).single().field)
    }

    @Test fun realProgressAndUnfinishedSetsAreFine() {
        assertTrue(suspects(ex(set(110.0, 5, done = true), set(1000.0, 5))).isEmpty())
        assertTrue(suspects(ex(set(null, 20, done = true), id = 2)).isEmpty())
    }

    @Test fun withoutHistoryOnlyTheAbsurdIsFlagged() {
        assertTrue(suspects(ex(set(250.0, 5, done = true)), b = emptyMap()).isEmpty())
        assertEquals(1, suspects(ex(set(2250.0, 5, done = true)), b = emptyMap()).size)
    }

    // ---- Real end ----

    @Test fun aWorkoutLeftOpenEndsOneRestAfterTheLastSet() {
        val s = Session(name = "W", startedAt = now, exercises = listOf(ex(set(100.0, 5, done = true, doneAt = now + 50 * min))))
        assertEquals(now + 50 * min + 120_000, s.realEnd(now + 3 * 60 * min) { 120 })
    }

    @Test fun aPromptFinishKeepsTheCurrentTime() {
        val s = Session(name = "W", startedAt = now, exercises = listOf(ex(set(100.0, 5, done = true, doneAt = now + 50 * min))))
        assertEquals(now + 70 * min, s.realEnd(now + 70 * min) { 120 })
    }

    @Test fun setsWithoutTimesKeepTheCurrentTime() {
        val s = Session(name = "W", startedAt = now, exercises = listOf(ex(set(100.0, 5, done = true))))
        assertEquals(now + 5 * 60 * min, s.realEnd(now + 5 * 60 * min) { 120 })
    }

    // ---- Record recompute ----

    private fun row(workout: Long, at: Long, w: Double, r: Int, mask: Int = 0, id: Long = workout) = SetWithWorkout(
        SetEntity(id = id, workoutId = workout, exerciseId = 1, exerciseIndex = 0, setIndex = 0, weightKg = w, reps = r, prMask = mask),
        startedAt = at, workoutName = "W",
    )

    @Test fun fixingATypoGivesLaterWorkoutsTheirTrophiesBack() {
        // Workout 2 had a typo fixed down to 80; workout 3's 105 was a record all along.
        val rows = listOf(row(1, 1, 100.0, 5), row(2, 2, 80.0, 5, mask = Pr.WEIGHT), row(3, 3, 105.0, 5))
        val changed = recomputePrMasks(rows, TrackingType.WEIGHT_REPS).associateBy { it.workoutId }
        assertEquals(0, changed.getValue(2).prMask)
        assertTrue(changed.getValue(3).prMask and Pr.WEIGHT != 0)
        assertNull(changed[1])
    }

    // ---- Timing ----

    private fun workout(id: Long, at: Long, minutes: Long, templateId: Long? = null, name: String = "W") =
        WorkoutEntity(id = id, name = name, startedAt = at, endedAt = at + minutes * min, templateId = templateId)

    @Test fun typicalDurationIgnoresForgottenTimersAndMatchesImportsByName() {
        val ws = listOf(
            workout(1, day, 50, templateId = 7), workout(2, 2 * day, 60, name = "Push"),
            workout(3, 3 * day, 600, templateId = 7), workout(4, 4 * day, 55, templateId = 8),
        )
        assertEquals(60 * min, typicalDuration(ws, 7, "Push"))
    }

    @Test fun paceUsesTheSameTemplateWhenThereIsEnough() {
        val ws = listOf(workout(1, day, 60, templateId = 7), workout(2, 2 * day, 40, templateId = 7), workout(3, 3 * day, 90))
        val counts = mapOf(1L to 20, 2L to 20, 3L to 10)
        assertEquals(3 * min, millisPerSet(ws, counts, 7, "Push"))
        assertEquals(9 * min, millisPerSet(listOf(ws[2]), counts, 7, "Push"))
        val s = Session(name = "W", exercises = listOf(ex(set(1.0, 1, done = true), set(null, null), set(null, null))))
        assertEquals(now + 6 * min, s.estimatedEnd(3 * min, now))
    }

    // ---- Next template ----

    private val templates = listOf("Push", "Pull", "Legs", "Arms").mapIndexed { i, n -> TemplateEntity(id = i + 1L, name = n, exercisesJson = "[]") }

    @Test fun nextIsTheRotationMemberDoneLongestAgo() {
        // Push, Pull, Legs, Push, Pull, Legs, Push: Pull is next.
        val ws = listOf(1L, 2, 3, 1, 2, 3, 1).mapIndexed { i, t -> workout(i + 1L, now - (10 - i) * day, 60, templateId = t) }
        assertEquals(2L, nextTemplate(templates, ws, now))
    }

    @Test fun oneOffsAndDroppedTemplatesLeaveTheRotation() {
        // Arms was a one-off; Legs was dropped 7 template-workouts ago.
        val order = listOf(3L, 3, 1, 2, 1, 2, 4, 1, 2, 1)
        val ws = order.mapIndexed { i, t -> workout(i + 1L, now - (12 - i) * day, 60, templateId = t) }
        assertEquals(2L, nextTemplate(templates, ws, now))
    }

    @Test fun noRotationNoGuess() {
        val ws = listOf(workout(1, now - day, 60, templateId = 1), workout(2, now - 2 * day, 60, templateId = 1))
        assertNull(nextTemplate(templates, ws, now))
    }

    // ---- Goal nudges ----

    private val monday = LocalDate.of(2026, 10, 26)

    @Test fun nudgesOnlyWhenTodayIsNeeded() {
        val fri = monday.plusDays(4)
        // Goal 3, 1 done by Friday: 2 needed with 3 days left, no nudge yet.
        assertNull(goalNudge(listOf(monday), 3, 1, fri, DayOfWeek.MONDAY))
        // Saturday: 2 needed, 2 left.
        assertNotNull(goalNudge(listOf(monday), 3, 1, fri.plusDays(1), DayOfWeek.MONDAY))
        // Already trained today.
        assertNull(goalNudge(listOf(monday, fri.plusDays(1)), 3, 1, fri.plusDays(1), DayOfWeek.MONDAY))
        // Goal already met.
        assertNull(goalNudge(listOf(monday, monday.plusDays(1), monday.plusDays(2)), 3, 1, monday.plusDays(6), DayOfWeek.MONDAY))
    }

    @Test fun lastDayNudgeSavesTheStreakEvenWhenTheGoalIsLost() {
        val sunday = monday.plusDays(6)
        assertTrue(goalNudge(emptyList(), 3, 4, sunday, DayOfWeek.MONDAY)!!.contains("4-week streak"))
        assertNull(goalNudge(emptyList(), 3, 0, sunday, DayOfWeek.MONDAY))
    }

    @Test fun usualStartIsTheMedianTimeOfDay() {
        val base = now - 5 * day
        val starts = listOf(base + 18 * 60 * min, base + day + 19 * 60 * min, base + 2 * day + 7 * 60 * min)
        assertEquals(18 * 60, usualStartMinute(starts, ZoneOffset.UTC, now))
        assertNull(usualStartMinute(starts.take(2), ZoneOffset.UTC, now))
    }

    // ---- Monthly recap ----

    @Test fun recapSumsTheMonthAndFindsGains() {
        val zone = ZoneOffset.UTC
        fun at(date: LocalDate) = date.atStartOfDay(zone).toInstant().toEpochMilli() + 18 * 60 * min
        val ws = listOf(
            workout(1, at(LocalDate.of(2026, 9, 10)), 60),
            workout(2, at(LocalDate.of(2026, 10, 5)), 60),
            workout(3, at(LocalDate.of(2026, 10, 20)), 30),
        )
        fun s(w: Long, kg: Double, mask: Int = 0) = SetEntity(workoutId = w, exerciseId = 1, exerciseIndex = 0, setIndex = 0, weightKg = kg, reps = 5, prMask = mask)
        val sets = listOf(s(1, 100.0), s(2, 100.0), s(3, 105.0, Pr.WEIGHT))
        val weights = listOf(at(LocalDate.of(2026, 9, 28)) to 80.0, at(LocalDate.of(2026, 10, 28)) to 79.0)
        val r = monthRecap(YearMonth.of(2026, 10), ws, sets, mapOf(1L to TrackingType.WEIGHT_REPS), weights, zone)!!
        assertEquals(2, r.workouts)
        assertEquals(1, r.previousWorkouts)
        assertEquals(90 * min, r.durationMillis)
        assertEquals(1025.0, r.volumeKg, 1e-9)
        assertEquals(1, r.records)
        assertEquals(5.0 * (1 + 5 / 30.0), r.gains.single().second, 1e-9)
        assertTrue(r.bodyWeightChange!! < 0)
        assertNull(monthRecap(YearMonth.of(2026, 8), ws, sets, emptyMap(), weights, zone))
    }
}
