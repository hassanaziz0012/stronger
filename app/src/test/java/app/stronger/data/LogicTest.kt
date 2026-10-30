package app.stronger.data

import app.stronger.ui.workout.loadPlates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LogicTest {
    private fun set(w: Double?, r: Int?, done: Boolean = true, type: SetType = SetType.NORMAL) =
        SessionSet(key = Keys.next(), weightKg = w, reps = r, done = done, type = type)

    @Test fun prMarksOnlyTheBestSetPerRecord() {
        val sets = listOf(set(100.0, 5), set(105.0, 3), set(90.0, 10))
        val out = computePrMasks(sets, TrackingType.WEIGHT_REPS, Bests(weight = 100.0, oneRm = 115.0, volume = 800.0), hasHistory = true)
        assertEquals(0, out[0].prMask)
        assertEquals(Pr.WEIGHT, out[1].prMask) // 105 kg beats 100; 1RM 115.5 vs 90×10 = 120
        assertEquals(Pr.ONE_RM or Pr.VOLUME, out[2].prMask)
    }

    @Test fun noRecordsWithoutHistoryOrForWarmups() {
        val first = computePrMasks(listOf(set(100.0, 5)), TrackingType.WEIGHT_REPS, null, hasHistory = false)
        assertEquals(0, first[0].prMask)
        val warm = computePrMasks(listOf(set(200.0, 5, type = SetType.WARMUP)), TrackingType.WEIGHT_REPS, Bests(), hasHistory = true)
        assertEquals(0, warm[0].prMask)
    }

    @Test fun undoneSetsNeverCount() {
        val out = computePrMasks(listOf(set(200.0, 5, done = false)), TrackingType.WEIGHT_REPS, Bests(), hasHistory = true)
        assertEquals(0, out[0].prMask)
    }

    @Test fun epley() {
        assertEquals(100.0, estimatedOneRepMax(100.0, 1), 1e-9)
        assertEquals(116.666, estimatedOneRepMax(100.0, 5), 1e-2)
    }

    @Test fun hintsPreferLastTimeThenTheSetAbove() {
        val ex = SessionExercise(key = 1, exerciseId = 1, sets = listOf(set(60.0, 8, done = false), set(null, null, done = false)))
        val prev = listOf(SetEntity(workoutId = 1, exerciseId = 1, exerciseIndex = 0, setIndex = 0, weightKg = 50.0, reps = 10))
        assertEquals(50.0, hintFor(ex, 0, prev)!!.weightKg!!, 0.0)
        assertEquals(60.0, hintFor(ex, 1, prev)!!.weightKg!!, 0.0)
        assertEquals(60.0, hintFor(ex, 1, null)!!.weightKg!!, 0.0)
        val filled = set(null, 12, done = false).applyHint(SessionSet(key = 0, weightKg = 40.0, reps = 5))
        assertEquals(40.0, filled.weightKg!!, 0.0)
        assertEquals(12, filled.reps)
    }

    @Test fun supersetsGroupAndStayAdjacent() {
        var s = Session(name = "t").addExercises(listOf(1, 2, 3))
        val (a, b, c) = s.exercises.map { it.key }
        s = s.superset(a, c)
        assertEquals(listOf(a, c, b), s.exercises.map { it.key })
        assertTrue(s.exercises[0].supersetId != null && s.exercises[0].supersetId == s.exercises[1].supersetId)
        s = s.removeFromSuperset(c)
        assertTrue(s.exercises.all { it.supersetId == null }) // a lone member dissolves
    }

    @Test fun movingExercises() {
        val s = Session(name = "t").addExercises(listOf(1, 2, 3))
        val moved = s.moveExercise(s.exercises[2].key, -1)
        assertEquals(listOf(1L, 3L, 2L), moved.exercises.map { it.exerciseId })
        assertEquals(s, s.moveExercise(s.exercises[0].key, -1))
    }

    @Test fun plateLoading() {
        val load = loadPlates(102.5, 20.0, listOf(25.0, 20.0, 15.0, 10.0, 5.0, 2.5, 1.25))
        assertEquals(listOf(25.0, 15.0, 1.25), load.perSide)
        assertEquals(102.5, load.achieved, 1e-9)
        val odd = loadPlates(21.0, 20.0, listOf(1.25))
        assertEquals(20.0, odd.achieved, 1e-9)
    }

    @Test fun csvParsing() {
        val line = "\"2024-01-02 18:00:00\",\"Push, Day\",1h 5m,\"Bench Press (Barbell)\",W,60,8,,,\"said \"\"easy\"\"\",,"
        val c = StrongCsvImporter.parseLine(line, ',')
        assertEquals("Push, Day", c[1])
        assertEquals("W", c[4])
        assertEquals("said \"easy\"", c[9])
        assertEquals(3900L, StrongCsvImporter.parseDuration("1h 5m", false))
        assertEquals(2700L, StrongCsvImporter.parseDuration("45m", false))
        assertEquals(754L, StrongCsvImporter.parseDuration("754", true))
    }

    @Test fun guessing() {
        assertEquals(Equipment.DUMBBELL, StrongCsvImporter.guessEquipment("Romanian Deadlift (Dumbbell)", emptyList()))
        assertEquals(Equipment.DURATION, StrongCsvImporter.guessEquipment("Pike Hold", listOf(Triple(null, null, 30 to null))))
        assertEquals(Equipment.REPS_ONLY, StrongCsvImporter.guessEquipment("Frog Stand", listOf(Triple(null, null, null to 8))))
        assertEquals(BodyPart.LEGS, StrongCsvImporter.guessBodyPart("Leg Press"))
        assertEquals(BodyPart.CORE, StrongCsvImporter.guessBodyPart("Hanging Leg Raise"))
        assertEquals(BodyPart.SHOULDERS, StrongCsvImporter.guessBodyPart("Pike Hold"))
        assertEquals(BodyPart.BACK, StrongCsvImporter.guessBodyPart("Dead Arm Hang"))
    }
}
