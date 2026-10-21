package app.gymquest.core.exercise

import app.gymquest.core.pose.PoseFrame
import app.gymquest.core.sim.Camera
import app.gymquest.core.sim.Noise
import app.gymquest.core.sim.RepTimeline
import app.gymquest.core.sim.Sequences
import app.gymquest.core.sim.World

class RunResult(
    val count: Int,
    val events: List<RepEvent>,
    val partials: List<PartialRep>,
    val maxProgress: Double,
    val issues: Set<FormIssue>,
)

fun run(counter: RepCounter, frames: List<PoseFrame>, smooth: Boolean = true): RunResult {
    val smoother = if (smooth) Smoothers.forExercise(counter.type) else null
    val events = mutableListOf<RepEvent>()
    val partials = mutableListOf<PartialRep>()
    val issues = mutableSetOf<FormIssue>()
    var maxProgress = 0.0
    for (raw in frames) {
        val frame = smoother?.smooth(raw) ?: raw
        val update = counter.update(frame)
        update.rep?.let { events += it; issues += it.issues }
        update.partial?.let { partials += it }
        update.progress?.let { if (it > maxProgress) maxProgress = it }
    }
    return RunResult(counter.count, events, partials, maxProgress, issues)
}

fun repsFrames(
    reps: Int,
    repMs: Long,
    yaw: Double,
    noise: Double = 0.003,
    roll: Double = 0.0,
    personHeight: Double = 0.62,
    motion: (Double) -> World,
): List<PoseFrame> = Sequences.reps(
    RepTimeline(reps, repMs),
    Camera(yawDeg = yaw, rollDeg = roll, personHeight = personHeight).fittedTo(motion(0.0), motion(1.0)),
    Noise(noise, seed = (yaw * 31).toLong() + reps),
    motion = motion,
)
