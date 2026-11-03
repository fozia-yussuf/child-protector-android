package com.childprotector.app

class FaceMatchWindow(
    private val threshold: Double = 0.363,
    private val windowSize: Int = 5,
    private val matchesRequired: Int = 3
) {
    private val scores = ArrayDeque<Double?>()

    init {
        require(windowSize > 0)
        require(matchesRequired in 1..windowSize)
    }

    fun add(score: Double): Double? {
        scores.addLast(score.takeIf { it >= threshold })
        if (scores.size > windowSize) scores.removeFirst()

        val matchingScores = scores.filterNotNull()
        return matchingScores.average().takeIf { matchingScores.size >= matchesRequired }
    }

    fun reset() {
        scores.clear()
    }
}
