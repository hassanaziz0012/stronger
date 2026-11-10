package app.stronger.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Something a session editor screen can read and edit. */
interface SessionStore {
    val session: StateFlow<Session?>
    fun update(f: (Session) -> Session)
}

/**
 * The workout in progress. Survives process death by mirroring itself to a small JSON file on
 * every change, so a killed app resumes exactly where you left off.
 */
class ActiveWorkout(context: Context, private val repo: Repository, private val scope: CoroutineScope) : SessionStore {
    private val file = File(context.filesDir, "active_session.json")
    private val _session = MutableStateFlow(load())
    override val session: StateFlow<Session?> = _session.asStateFlow()

    private val _previous = MutableStateFlow<Map<Long, List<SetEntity>>>(emptyMap())
    /** Last workout's sets per exercise, for the "Previous" column. */
    val previous: StateFlow<Map<Long, List<SetEntity>>> = _previous.asStateFlow()

    private val _bests = MutableStateFlow<Map<Long, Bests>>(emptyMap())
    /** Bests from before this workout, for effort estimates. */
    val bests: StateFlow<Map<Long, Bests>> = _bests.asStateFlow()
    private val _pace = MutableStateFlow<Long?>(null)
    /** Your usual milliseconds per set for this kind of workout, for estimating when it ends. */
    val pace: StateFlow<Long?> = _pace.asStateFlow()
    private val _exercises = mutableMapOf<Long, ExerciseEntity>()
    private val _tracking get() = _exercises.mapValues { it.value.tracking }

    private var unit = WeightUnit.KG
    private var countdownSeconds = AppSettings().setCountdownSeconds

    init {
        _session.value?.let { refreshHistory(it) }
        // Keep exercise details fresh so target changes apply mid-workout.
        scope.launch { repo.exerciseIndex.collect { _exercises.putAll(it) } }
        scope.launch { repo.settings.settings.collect { unit = it.unit; countdownSeconds = it.setCountdownSeconds } }
    }

    private fun load(): Session? = runCatching {
        if (file.exists()) AppJson.decodeFromString<Session>(file.readText()) else null
    }.getOrNull()

    private fun persist(s: Session?) {
        scope.launch(Dispatchers.IO) {
            runCatching { if (s == null) file.delete() else file.writeText(AppJson.encodeToString(s)) }
        }
    }

    val isActive get() = _session.value != null

    fun start(session: Session) {
        _session.value = session
        persist(session)
        refreshHistory(session, starting = true)
    }

    override fun update(f: (Session) -> Session) {
        _session.update { current ->
            current?.let { old ->
                val new = f(old)
                val known = old.exercises.map { it.exerciseId }.toSet()
                if (new.exercises.any { it.exerciseId !in known }) refreshHistory(new)
                withPrs(new).withoutStaleTimer()
            }
        }
        persist(_session.value)
    }

    /** Drops the set timer once its set is deleted or its exercise removed. */
    private fun Session.withoutStaleTimer(): Session {
        val t = setTimer ?: return this
        val exists = exercises.any { e -> e.key == t.exKey && e.sets.any { it.key == t.setKey && !it.done } }
        return if (exists) this else copy(setTimer = null)
    }

    private fun withPrs(s: Session): Session {
        val bests = _bests.value
        return s.copy(exercises = s.exercises.map { ex ->
            val tracking = _tracking[ex.exerciseId] ?: return@map ex
            ex.copy(sets = computePrMasks(ex.sets, tracking, bests[ex.exerciseId], bests.containsKey(ex.exerciseId)))
        })
    }

    private fun refreshHistory(s: Session, starting: Boolean = false) {
        val fromTemplate = starting && s.templateId != null
        scope.launch {
            val ids = s.exercises.map { it.exerciseId }.distinct()
            ids.forEach { id -> if (id !in _exercises) repo.getExercise(id)?.let { _exercises[id] = it } }
            val previous = repo.previousSets(ids, before = s.startedAt)
            _previous.value = previous
            _bests.value = repo.bests(ids, before = s.startedAt)
            val (workouts, setCounts) = repo.workoutsWithSetCounts()
            val templateName = s.templateId?.let { repo.template(it)?.name } ?: s.name
            _pace.value = millisPerSet(workouts.filter { it.startedAt < s.startedAt }, setCounts, s.templateId, templateName)
            _session.update { current ->
                val targeted = { id: Long -> _exercises[id]?.repTarget(unit) != null && !previous[id].isNullOrEmpty() }
                current?.let { if (starting) it.withGoals { id -> _exercises[id]?.tracking } else it }
                    ?.let { if (fromTemplate) it.clearForTargets(s, targeted) else it }?.let(::withPrs)
            }
            if (starting) persist(_session.value)
        }
    }

    /** Toggles a set; completing one fills blanks from its target or last time, and starts the rest timer. */
    fun toggleSet(exKey: Long, setKey: Long) {
        val s = _session.value ?: return
        val ex = s.exercises.firstOrNull { it.key == exKey } ?: return
        val index = ex.sets.indexOfFirst { it.key == setKey }
        if (index < 0) return
        val set = ex.sets[index]
        if (!set.done && s.setTimer?.setKey == setKey) return finishSetTimer()
        if (set.done) {
            // An unticked timed set shows its goal again, ready for another go with the timer.
            val timed = _exercises[ex.exerciseId]?.tracking == TrackingType.DURATION
            update { it.updateSet(exKey, setKey) { x -> x.copy(done = false, rir = null, doneAt = null, seconds = if (timed) x.goal ?: x.seconds else x.seconds) } }
            return
        }
        val hint = suggestionFor(ex, index, _previous.value[ex.exerciseId], _exercises[ex.exerciseId], unit)
        update { it.updateSet(exKey, setKey) { x -> x.applyHint(hint).copy(done = true, doneAt = System.currentTimeMillis()) } }
        val inSuperset = ex.supersetId != null
        val lastOfGroup = !inSuperset || s.exercises.last { it.supersetId == ex.supersetId }.key == exKey
        if (lastOfGroup) scope.launch { startRest(restSecondsFor(ex)) }
    }

    /**
     * Adds a rest-pause to a set. A running rest restarts, so the rest before the next set counts
     * from the last mini-set rather than the first.
     */
    fun addPause(exKey: Long, setKey: Long, goal: Int?) {
        val s = _session.value ?: return
        update { it.updateSet(exKey, setKey) { x -> x.addPause(goal) } }
        if (s.restEndsAt != null) startRest(s.restTotalSeconds)
    }

    /** Completes the first unfinished set, e.g. from the notification. */
    fun completeNextSet() {
        val s = _session.value ?: return
        for (ex in s.exercises) {
            val set = ex.sets.firstOrNull { !it.done } ?: continue
            toggleSet(ex.key, set.key)
            return
        }
    }

    suspend fun restSecondsFor(ex: SessionExercise): Int =
        ex.restSeconds ?: _exercises[ex.exerciseId]?.restSeconds ?: repo.settings.current().defaultRestSeconds

    fun exerciseInfo(id: Long): ExerciseEntity? = _exercises[id]

    // ---- Rest timer ----
    fun startRest(seconds: Int) {
        if (seconds <= 0) return
        update { it.copy(restEndsAt = System.currentTimeMillis() + seconds * 1000L, restTotalSeconds = seconds) }
    }

    fun adjustRest(deltaSeconds: Int) = update { s ->
        val ends = s.restEndsAt ?: return@update s
        val newEnd = ends + deltaSeconds * 1000L
        if (newEnd <= System.currentTimeMillis()) s.copy(restEndsAt = null)
        else s.copy(restEndsAt = newEnd, restTotalSeconds = (s.restTotalSeconds + deltaSeconds).coerceAtLeast(1))
    }

    fun skipRest() = update { it.copy(restEndsAt = null) }

    // ---- Set timer ----
    /**
     * Starts timing a duration set after the get-ready countdown from settings, replacing any rest
     * or other set timer. The goal is the time typed into the set, else last time's; a shorter last
     * time becomes a milestone on the way.
     */
    fun startSetTimer(exKey: Long, setKey: Long) {
        val s = _session.value ?: return
        val ex = s.exercises.firstOrNull { it.key == exKey } ?: return
        val index = ex.sets.indexOfFirst { it.key == setKey }
        if (index < 0) return
        val set = ex.sets[index]
        val previous = _previous.value[ex.exerciseId]
        val target = (set.seconds ?: set.goal ?: suggestionFor(ex, index, previous, _exercises[ex.exerciseId], unit)?.seconds)?.takeIf { it > 0 }
        val last = previousFor(ex, index, previous)?.seconds
        val timer = SetTimer(
            exKey, setKey, System.currentTimeMillis() + countdownSeconds.coerceAtLeast(0) * 1000L,
            targetSeconds = target, milestoneSeconds = last?.takeIf { target != null && it in 1 until target },
        )
        update { it.copy(setTimer = timer, restEndsAt = null).updateSet(exKey, setKey) { x -> x.copy(goal = target ?: x.goal) } }
    }

    /** Skips the rest of the get-ready countdown. */
    fun goNow() = updateTimer { t, now -> if (t.startedAt > now && !t.paused) t.copy(startedAt = now) else t }

    fun pauseSetTimer() = updateTimer { t, now -> if (t.paused) t else t.copy(pausedAt = now.coerceAtLeast(t.startedAt)) }

    fun resumeSetTimer() = updateTimer { t, now -> t.pausedAt?.let { p -> t.copy(startedAt = t.startedAt + (now - p), pausedAt = null) } ?: t }

    fun cancelSetTimer() = update { it.copy(setTimer = null) }

    /** Logs the time on the set and ticks it, which starts the rest timer as usual. */
    fun finishSetTimer() {
        val t = _session.value?.setTimer ?: return
        val seconds = (t.elapsedMillis(System.currentTimeMillis()) / 1000).toInt()
        update { s -> s.copy(setTimer = null).let { if (seconds > 0) it.updateSet(t.exKey, t.setKey) { x -> x.copy(seconds = seconds) } else it } }
        toggleSet(t.exKey, t.setKey)
    }

    private fun updateTimer(f: (SetTimer, Long) -> SetTimer) = update { s ->
        s.setTimer?.let { s.copy(setTimer = f(it, System.currentTimeMillis())) } ?: s
    }

    /** Ticked sets that look like typos, to check before they're saved. */
    fun suspects(): List<Suspect> = _session.value?.let { suspiciousSets(it, _bests.value, ::exerciseInfo) }.orEmpty()

    suspend fun finish(): WorkoutSummary? {
        val s = _session.value ?: return null
        val defaultRest = repo.settings.current().defaultRestSeconds
        val endedAt = s.realEnd(System.currentTimeMillis()) { ex -> ex.restSeconds ?: _exercises[ex.exerciseId]?.restSeconds ?: defaultRest }
        val summary = repo.finishWorkout(s.copy(restEndsAt = null), endedAt)
        discard()
        return summary
    }

    fun discard() {
        _session.value = null
        _previous.value = emptyMap()
        _bests.value = emptyMap()
        _pace.value = null
        persist(null)
    }
}
