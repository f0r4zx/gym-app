package app.gymquest.core.exercise

import app.gymquest.core.pose.PoseSmoother

object Smoothers {
    /**
     * A smoother tuned to the speed of [type]: slow lifts get heavy smoothing (steadier counting),
     * fast cardio gets a wide-open filter so jumps and knee drives are not flattened.
     */
    fun forExercise(type: ExerciseType): PoseSmoother =
        PoseSmoother(minCutoff = (type.motionHz * 2.5).coerceAtLeast(1.5), beta = 0.8)
}
