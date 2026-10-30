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

    init {
        _session.value?.let { refreshHistory(it) }
        // Keep exercise details fresh so target changes apply mid-workout.
        scope.launch { repo.exerciseIndex.collect { _exercises.putAll(it) } }
        scope.launch { repo.settings.settings.collect { unit = it.unit } }
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
        refreshHistory(session, fromTemplate = session.templateId != null)
    }

    override fun update(f: (Session) -> Session) {
        _session.update { current ->
            current?.let { old ->
                val new = f(old)
                val known = old.exercises.map { it.exerciseId }.toSet()
                if (new.exercises.any { it.exerciseId !in known }) refreshHistory(new)
                withPrs(new)
            }
        }
        persist(_session.value)
    }

    private fun withPrs(s: Session): Session {
        val bests = _bests.value
        return s.copy(exercises = s.exercises.map { ex ->
            val tracking = _tracking[ex.exerciseId] ?: return@map ex
            ex.copy(sets = computePrMasks(ex.sets, tracking, bests[ex.exerciseId], bests.containsKey(ex.exerciseId)))
        })
    }

    private fun refreshHistory(s: Session, fromTemplate: Boolean = false) {
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
                current?.let { if (fromTemplate) it.clearForTargets(s, targeted) else it }?.let(::withPrs)
            }
            if (fromTemplate) persist(_session.value)
        }
    }

    /** Toggles a set; completing one fills blanks from its target or last time, and starts the rest timer. */
    fun toggleSet(exKey: Long, setKey: Long) {
        val s = _session.value ?: return
        val ex = s.exercises.firstOrNull { it.key == exKey } ?: return
        val index = ex.sets.indexOfFirst { it.key == setKey }
        if (index < 0) return
        val set = ex.sets[index]
        if (set.done) {
            update { it.updateSet(exKey, setKey) { x -> x.copy(done = false, rir = null, doneAt = null) } }
            return
        }
        val hint = suggestionFor(ex, index, _previous.value[ex.exerciseId], _exercises[ex.exerciseId], unit)
        update { it.updateSet(exKey, setKey) { x -> x.applyHint(hint).copy(done = true, doneAt = System.currentTimeMillis()) } }
        val inSuperset = ex.supersetId != null
        val lastOfGroup = !inSuperset || s.exercises.last { it.supersetId == ex.supersetId }.key == exKey
        if (lastOfGroup) scope.launch { startRest(restSecondsFor(ex)) }
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
