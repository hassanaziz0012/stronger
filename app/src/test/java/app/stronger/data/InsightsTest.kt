package app.stronger.data

import app.stronger.health.HealthSync
import androidx.health.connect.client.records.ExerciseSessionRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InsightsTest {
    private val day = 86_400_000L
    private val now = 1_000 * day

    private val bench = ExerciseEntity(id = 1, name = "Bench Press (Barbell)", bodyPart = BodyPart.CHEST, equipment = Equipment.BARBELL, repMin = 8, repMax = 12)

    private fun prev(vararg sets: Pair<Double?, Int>, type: SetType = SetType.NORMAL) = sets.mapIndexed { i, (w, r) ->
        SetEntity(workoutId = 1, exerciseId = 1, exerciseIndex = 0, setIndex = i, type = type, weightKg = w, reps = r)
    }

    private fun exercise(n: Int, warmups: Int = 0) = SessionExercise(
        key = 1, exerciseId = 1,
        sets = List(warmups) { SessionSet(key = Keys.next(), type = SetType.WARMUP) } + List(n) { SessionSet(key = Keys.next()) },
    )

    @Test fun targetAddsARepUntilTheTopOfTheRange() {
        val t = targetFor(exercise(3), 1, prev(80.0 to 10, 80.0 to 9, 80.0 to 8), bench, WeightUnit.KG)!!
        assertEquals(80.0, t.weightKg!!, 0.0)
        assertEquals(10, t.reps)
    }

    @Test fun targetAddsWeightOnceEverySetHitsTheTop() {
        val t = targetFor(exercise(3), 0, prev(80.0 to 12, 80.0 to 12, 80.0 to 13), bench, WeightUnit.KG)!!
        assertEquals(82.5, t.weightKg!!, 1e-9)
        assertEquals(8, t.reps)
    }

    @Test fun repsBelowTheRangeAimForTheBottom() {
        assertEquals(8, targetFor(exercise(1), 0, prev(80.0 to 5), bench, WeightUnit.KG)!!.reps)
    }

    @Test fun warmupsGetNoTargetAndDontBlockProgress() {
        val previous = prev(40.0 to 5, type = SetType.WARMUP) + prev(80.0 to 12, 80.0 to 12).map { it.copy(setIndex = it.setIndex + 1) }
        val ex = exercise(2, warmups = 1)
        assertNull(targetFor(ex, 0, previous, bench, WeightUnit.KG))
        assertEquals(82.5, targetFor(ex, 1, previous, bench, WeightUnit.KG)!!.weightKg!!, 1e-9)
    }

    @Test fun extraSetsFollowTheLastWorkingSet() {
        val t = targetFor(exercise(3), 2, prev(80.0 to 10, 80.0 to 9), bench, WeightUnit.KG)!!
        assertEquals(80.0, t.weightKg!!, 0.0)
        assertEquals(10, t.reps)
    }

    @Test fun assistedExercisesTakeAssistanceOff() {
        val dip = bench.copy(equipment = Equipment.ASSISTED_BODYWEIGHT, incrementKg = 5.0)
        assertEquals(15.0, targetFor(exercise(1), 0, prev(20.0 to 12), dip, WeightUnit.KG)!!.weightKg!!, 1e-9)
    }

    @Test fun poundUsersStepInFivePounds() {
        val t = targetFor(exercise(1), 0, prev(WeightUnit.LB.let { 135.0 / it.perKg } to 12), bench, WeightUnit.LB)!!
        assertEquals(140.0, t.weightKg!! * WeightUnit.LB.perKg, 1e-6)
    }

    @Test fun noRangeMeansNoTarget() {
        assertNull(targetFor(exercise(1), 0, prev(80.0 to 12), bench.copy(repMin = null, repMax = null), WeightUnit.KG))
    }

    @Test fun templateNumbersMakeWayForTargetsButEditsStay() {
        val a = SessionSet(key = 1, weightKg = 80.0, reps = 10)
        val b = SessionSet(key = 2, weightKg = 80.0, reps = 10)
        val warm = SessionSet(key = 3, type = SetType.WARMUP, weightKg = 40.0, reps = 5)
        val original = Session(name = "Push", exercises = listOf(SessionExercise(key = 9, exerciseId = 1, sets = listOf(warm, a, b))))
        val edited = original.updateSet(9, 2) { it.copy(weightKg = 85.0) }
        val out = edited.clearForTargets(original) { it == 1L }.exercises[0].sets
        assertEquals(40.0, out[0].weightKg!!, 0.0)
        assertNull(out[1].weightKg)
        assertNull(out[1].reps)
        assertEquals(85.0, out[2].weightKg!!, 0.0)
        assertEquals(original, original.clearForTargets(original) { false })
    }

    @Test fun progressingStalledAndRegressing() {
        fun sessions(vararg scores: Double) = scores.mapIndexed { i, s -> now - (scores.size - i) * 3 * day to s }
        assertEquals(Trend.PROGRESSING, progressStatus(sessions(100.0, 102.0, 101.0, 104.0), now)!!.trend)
        val stalled = progressStatus(sessions(100.0, 105.0, 104.0, 105.0, 103.0), now)!!
        assertEquals(Trend.STALLED, stalled.trend)
        assertEquals(3, stalled.sessionsSinceBest)
        assertEquals(Trend.REGRESSING, progressStatus(sessions(100.0, 110.0, 105.0, 100.0, 101.0), now)!!.trend)
    }

    @Test fun tooLittleOrStaleHistoryIsntJudged() {
        assertNull(progressStatus(listOf(now - day to 100.0, now to 100.0), now))
        val old = listOf(100.0, 100.0, 100.0, 100.0).mapIndexed { i, s -> now - (60 - i) * day to s }
        assertNull(progressStatus(old, now))
    }

    @Test fun deloadNeedsSeveralLiftsStuck() {
        val stalled = ProgressStatus(Trend.STALLED, 3, 100.0, 0)
        val down = stalled.copy(trend = Trend.REGRESSING)
        assertFalse(deloadSuggested(listOf(stalled, stalled)))
        assertTrue(deloadSuggested(listOf(stalled, stalled, down)))
        assertTrue(deloadSuggested(listOf(down, down)))
    }

    @Test fun trendSmoothsOutASpike() {
        val points = (0 until 14).map { now - (14 - it) * day to 80.0 } + (now to 82.0)
        val trend = smoothedTrend(points)
        assertTrue(trend.last().second < 80.5)
        assertEquals(trend.last().second - 80.0, trendChange(trend, 7, now)!!, 1e-9)
    }

    @Test fun effortEstimateFromBestOneRepMax() {
        val bests = Bests(oneRm = 120.0, reps = 15)
        // 100 kg is about a 6-rep max when 1RM is 120, so 3 reps leaves about 3.
        assertEquals(3, estimateRir(SessionSet(key = 0, weightKg = 100.0, reps = 3), bench, bests))
        assertEquals(0, estimateRir(SessionSet(key = 0, weightKg = 130.0, reps = 2), bench, bests))
        assertEquals(RIR_MAX, estimateRir(SessionSet(key = 0, weightKg = 40.0, reps = 5), bench, bests))
        val pullUp = bench.copy(equipment = Equipment.REPS_ONLY)
        assertEquals(3, estimateRir(SessionSet(key = 0, reps = 12), pullUp, bests))
        assertNull(estimateRir(SessionSet(key = 0, weightKg = 100.0, reps = 3), bench, null))
        assertNull(estimateRir(SessionSet(key = 0, weightKg = 20.0, reps = 8), bench.copy(equipment = Equipment.ASSISTED_BODYWEIGHT), bests))
    }

    @Test fun easySetsProgressTwiceAsFast() {
        val easy = prev(80.0 to 9, 80.0 to 9).map { it.copy(rir = 3) }
        assertEquals(11, targetFor(exercise(2), 0, easy, bench, WeightUnit.KG)!!.reps)
        val easyTop = prev(80.0 to 12, 80.0 to 12).map { it.copy(rir = 4) }
        assertEquals(85.0, targetFor(exercise(2), 0, easyTop, bench, WeightUnit.KG)!!.weightKg!!, 1e-9)
        // One hard set means a normal step.
        val mixed = easy.mapIndexed { i, s -> if (i == 1) s.copy(rir = 1) else s }
        assertEquals(10, targetFor(exercise(2), 0, mixed, bench, WeightUnit.KG)!!.reps)
    }

    @Test fun repsInReserveCountTowardsTheSessionScore() {
        val hard = prev(80.0 to 8).map { it.copy(rir = 0) }
        val easy = prev(80.0 to 8).map { it.copy(rir = 3) }
        assertTrue(sessionScore(easy, TrackingType.WEIGHT_REPS) > sessionScore(hard, TrackingType.WEIGHT_REPS))
        assertEquals(sessionScore(prev(80.0 to 8), TrackingType.WEIGHT_REPS), sessionScore(hard, TrackingType.WEIGHT_REPS), 0.0)
    }

    @Test fun strongRpeBecomesRir() {
        assertEquals(0, StrongCsvImporter.rpeToRir(10.0))
        assertEquals(2, StrongCsvImporter.rpeToRir(8.0))
        assertEquals(RIR_MAX, StrongCsvImporter.rpeToRir(5.0))
        assertNull(StrongCsvImporter.rpeToRir(0.0))
    }

    @Test fun healthConnectTypes() {
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_STRENGTH_TRAINING, HealthSync.exerciseType(listOf("Bench" to Equipment.BARBELL, "Pull Up" to Equipment.REPS_ONLY)))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_CALISTHENICS, HealthSync.exerciseType(listOf("Pull Up" to Equipment.REPS_ONLY, "Dip" to Equipment.WEIGHTED_BODYWEIGHT)))
        assertEquals(ExerciseSessionRecord.EXERCISE_TYPE_RUNNING_TREADMILL, HealthSync.exerciseType(listOf("Running (Treadmill)" to Equipment.CARDIO)))
    }
}
