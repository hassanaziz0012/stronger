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

    @Test fun previousPairsWarmupsSeparately() {
        val prev = listOf(
            SetEntity(workoutId = 1, exerciseId = 1, exerciseIndex = 0, setIndex = 0, type = SetType.WARMUP, weightKg = 30.0, reps = 8),
            SetEntity(workoutId = 1, exerciseId = 1, exerciseIndex = 0, setIndex = 1, weightKg = 80.0, reps = 8),
        )
        val noWarmup = SessionExercise(key = 1, exerciseId = 1, sets = listOf(set(null, null, done = false)))
        assertEquals(80.0, previousFor(noWarmup, 0, prev)!!.weightKg!!, 0.0)
        val withWarmup = SessionExercise(key = 1, exerciseId = 1, sets = listOf(set(null, null, false, SetType.WARMUP), set(null, null, done = false)))
        assertEquals(30.0, previousFor(withWarmup, 0, prev)!!.weightKg!!, 0.0)
        assertEquals(80.0, previousFor(withWarmup, 1, prev)!!.weightKg!!, 0.0)
    }

    @Test fun durationDigitsRoundTrip() {
        assertEquals("130", app.stronger.ui.secondsToDigits(90))
        assertEquals("45", app.stronger.ui.secondsToDigits(45))
        assertEquals("1000", app.stronger.ui.secondsToDigits(600))
        assertEquals("", app.stronger.ui.secondsToDigits(null))
        assertEquals(90, app.stronger.ui.digitsToSeconds("130"))
        assertEquals(90, app.stronger.ui.digitsToSeconds("90")) // seconds over 59 carry
        assertEquals(5, app.stronger.ui.digitsToSeconds("5"))
        assertEquals(null, app.stronger.ui.digitsToSeconds(""))
        listOf(0, 7, 59, 60, 61, 125, 3599, 3600).forEach {
            assertEquals(it, app.stronger.ui.digitsToSeconds(app.stronger.ui.secondsToDigits(it)))
        }
    }

    @Test fun setTimerPausesAndCountsDown() {
        val t = SetTimer(exKey = 1, setKey = 2, startedAt = 10_000)
        assertEquals(-3_000, t.elapsedMillis(7_000))
        assertEquals(5_000, t.elapsedMillis(15_000))
        assertEquals(5_000, t.copy(pausedAt = 15_000).elapsedMillis(60_000))
    }

    @Test fun restPauseAddsUpToTheGoalAndEditsKeepTheTotal() {
        val s = set(60.0, 8).addPause(12)
        assertEquals(12, s.reps)
        assertEquals(listOf(4), s.pauseReps)
        assertEquals(8, s.straightReps)
        val tweaked = s.setPause(0, 1).addPause(12)
        assertEquals(listOf(1, 3), tweaked.pauseReps)
        assertEquals(12, tweaked.reps)
        assertEquals(13, tweaked.withStraightReps(9).reps)
        val removed = tweaked.setPause(0, 0)
        assertEquals(listOf(3), removed.pauseReps)
        assertEquals(11, removed.reps)
        // With no goal, or one already reached, a pause starts at one rep.
        assertEquals(listOf(1), set(60.0, 12).addPause(12).pauseReps)
    }

    @Test fun restPausedRepsCountForVolumeButNotStrength() {
        val paused = set(100.0, 12).copy(pauseReps = listOf(1, 3))
        val out = computePrMasks(listOf(paused), TrackingType.WEIGHT_REPS, Bests(weight = 100.0, oneRm = 130.0, volume = 1000.0), hasHistory = true)
        // 100×12 is a volume record, but 1RM goes by the straight 8: 126.7 < 130.
        assertEquals(Pr.VOLUME, out[0].prMask)
        val entity = SetEntity(workoutId = 1, exerciseId = 1, exerciseIndex = 0, setIndex = 0, weightKg = 100.0, reps = 12, pauseReps = listOf(1, 3))
        assertEquals(estimatedOneRepMax(100.0, 8), entity.oneRm, 1e-9)
        assertEquals(8, bestsOf(listOf(entity)).reps)
    }

    @Test fun progressionWaitsForTheRangeWithoutPauses() {
        val info = ExerciseEntity(id = 1, name = "Curl", bodyPart = BodyPart.values().first(), equipment = Equipment.DUMBBELL, repMin = 8, repMax = 12)
        val target = info.repTarget(WeightUnit.KG)!!
        val paused = listOf(SetEntity(workoutId = 1, exerciseId = 1, exerciseIndex = 0, setIndex = 0, weightKg = 20.0, reps = 12, pauseReps = listOf(4)))
        assertTrue(!readyToProgress(paused, target))
        val ex = SessionExercise(key = 1, exerciseId = 1, sets = listOf(set(null, null, done = false)))
        // Same weight, still aiming for all 12.
        val next = targetFor(ex, 0, paused, info, WeightUnit.KG)!!
        assertEquals(20.0, next.weightKg!!, 0.0)
        assertEquals(12, next.reps)
    }

    @Test fun pauseFreeWinsNeedLastTimesRepsWithoutAPause() {
        val prev = listOf(SetEntity(workoutId = 1, exerciseId = 1, exerciseIndex = 0, setIndex = 0, weightKg = 60.0, reps = 12, pauseReps = listOf(1, 3)))
        val clean = SessionExercise(key = 1, exerciseId = 1, sets = listOf(set(60.0, 12)))
        assertEquals(1, pauseFreeWins(clean, prev).size)
        val stillPaused = SessionExercise(key = 1, exerciseId = 1, sets = listOf(set(60.0, 12).copy(pauseReps = listOf(2))))
        assertEquals(0, pauseFreeWins(stillPaused, prev).size)
        val fewer = SessionExercise(key = 1, exerciseId = 1, sets = listOf(set(60.0, 10)))
        assertEquals(0, pauseFreeWins(fewer, prev).size)
    }

    @Test fun laterSetsAimForTheSameRepsAsTheFirst() {
        // Last time had one set of 12, so set 2 copies the set above: 8 today.
        val prev = listOf(SetEntity(workoutId = 1, exerciseId = 1, exerciseIndex = 0, setIndex = 0, weightKg = 60.0, reps = 12))
        val ex = SessionExercise(key = 1, exerciseId = 1, sets = listOf(set(60.0, 8), set(60.0, 8), set(40.0, 6, type = SetType.DROP)))
        assertEquals(8, hintFor(ex, 1, prev)!!.reps)
        val goal = pauseGoal(ex) { hintFor(ex, it, prev) }
        assertEquals(12, goal)
        assertTrue(offersPause(ex.sets[1], goal))
        // Drop sets and sets already rest-paused aren't offered one.
        assertTrue(!offersPause(ex.sets[2], goal))
        assertTrue(!offersPause(ex.sets[0].addPause(12), goal))
    }

    @Test
    fun goalsMetMakeAnExerciseReadyToProgress() {
        fun info(e: Equipment) = ExerciseEntity(id = 1, name = "x", bodyPart = BodyPart.CHEST, equipment = e)
        fun set(goal: Int?, reps: Int? = null, seconds: Int? = null, type: SetType = SetType.NORMAL, kg: Double? = null, goalKg: Double? = null) =
            SetEntity(workoutId = 1, exerciseId = 1, exerciseIndex = 0, setIndex = 0, type = type, reps = reps, seconds = seconds, goal = goal, weightKg = kg, goalKg = goalKg)
        val plank = info(Equipment.DURATION)
        assertTrue(readyToProgress(listOf(set(60, seconds = 60), set(60, seconds = 75)), plank, WeightUnit.KG))
        assertTrue(!readyToProgress(listOf(set(60, seconds = 60), set(60, seconds = 50)), plank, WeightUnit.KG))
        // Sets that aimed for nothing neither count nor block; with no goals at all there's nothing to go by.
        assertTrue(readyToProgress(listOf(set(60, seconds = 60), set(null, seconds = 20)), plank, WeightUnit.KG))
        assertTrue(!readyToProgress(listOf(set(null, seconds = 60)), plank, WeightUnit.KG))
        val curl = info(Equipment.DUMBBELL)
        assertTrue(readyToProgress(listOf(set(10, reps = 10), set(10, reps = 11), set(5, reps = 3, type = SetType.WARMUP)), curl, WeightUnit.KG))
        assertTrue(!readyToProgress(listOf(set(10, reps = 10), set(10, reps = 8)), curl, WeightUnit.KG))
        // The reps only count at the weight they were meant for.
        assertTrue(readyToProgress(listOf(set(10, reps = 10, kg = 30.0, goalKg = 30.0)), curl, WeightUnit.KG))
        assertTrue(!readyToProgress(listOf(set(10, reps = 10, kg = 25.0, goalKg = 30.0)), curl, WeightUnit.KG))
        val assisted = info(Equipment.ASSISTED_BODYWEIGHT)
        assertTrue(readyToProgress(listOf(set(10, reps = 10, kg = 15.0, goalKg = 20.0)), assisted, WeightUnit.KG))
        assertTrue(!readyToProgress(listOf(set(10, reps = 10, kg = 25.0, goalKg = 20.0)), assisted, WeightUnit.KG))
        // Workouts from before goals were recorded are held against today's plan.
        val plan = listOf(SessionSet(key = 1, goal = 10, goalKg = 30.0), SessionSet(key = 2, goal = 10, goalKg = 30.0))
        val old = listOf(set(null, reps = 10, kg = 30.0), set(null, reps = 10, kg = 30.0))
        assertTrue(readyToProgress(old, curl, WeightUnit.KG, plan))
        assertTrue(!readyToProgress(old.dropLast(1) + set(null, reps = 9, kg = 30.0), curl, WeightUnit.KG, plan))
        assertTrue(!readyToProgress(old, curl, WeightUnit.KG))

        val live = SessionExercise(key = 1, exerciseId = 1, sets = listOf(
            SessionSet(key = 1, seconds = 61, done = true, goal = 60), SessionSet(key = 2, seconds = 60, goal = 60),
        ))
        assertTrue(!readyNextTime(live, plank, WeightUnit.KG))
        assertTrue(readyNextTime(live.copy(sets = live.sets.map { it.copy(done = true) }), plank, WeightUnit.KG))
    }

    @Test
    fun startingAWorkoutRecordsWhatEachSetAimsFor() {
        val s = Session(name = "w", exercises = listOf(
            SessionExercise(key = 1, exerciseId = 1, sets = listOf(SessionSet(key = 1, weightKg = 50.0, reps = 10), SessionSet(key = 2))),
            SessionExercise(key = 2, exerciseId = 2, sets = listOf(SessionSet(key = 3, seconds = 90))),
        )).withGoals { if (it == 1L) TrackingType.WEIGHT_REPS else TrackingType.DURATION }
        assertEquals(listOf(10, null, 90), s.exercises.flatMap { it.sets }.map { it.goal })
        assertEquals(listOf(50.0, null, null), s.exercises.flatMap { it.sets }.map { it.goalKg })
    }
}
