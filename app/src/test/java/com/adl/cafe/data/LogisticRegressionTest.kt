package com.adl.cafe.data

import java.util.Random
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure JVM tests for the Smile-backed fitter behind [Recommender]'s learned weights. */
class LogisticRegressionTest {

    /** Examples drawn from a known model: intercept -1, weights 2 and -1.5 on uniform features. */
    private fun synthetic(n: Int, seed: Long = 7): Pair<List<DoubleArray>, List<Boolean>> {
        val random = Random(seed)
        val features = List(n) { doubleArrayOf(random.nextDouble(), random.nextDouble()) }
        val labels = features.map { x ->
            val p = 1 / (1 + exp(-(-1.0 + 2.0 * x[0] - 1.5 * x[1])))
            random.nextDouble() < p
        }
        return features to labels
    }

    @Test
    fun `no examples returns the prior`() {
        val prior = listOf(1.0, 0.5)
        assertEquals(prior, LogisticRegression.fit(emptyList(), emptyList(), prior, 5.0))
    }

    @Test
    fun `examples of only one class return the prior`() {
        val prior = listOf(1.0, 0.5)
        val features = List(10) { doubleArrayOf(it / 10.0, 1.0) }
        assertEquals(prior, LogisticRegression.fit(features, List(10) { false }, prior, 5.0))
    }

    @Test
    fun `plenty of data recovers the true weights`() {
        val (features, labels) = synthetic(20_000)

        val weights = LogisticRegression.fit(features, labels, listOf(0.0, 0.0), 0.01)

        assertEquals(2.0, weights[0], 0.15)
        assertEquals(-1.5, weights[1], 0.15)
    }

    @Test
    fun `more examples move the weights further from the prior`() {
        val prior = listOf(-3.0, 3.0)
        val (few, fewLabels) = synthetic(40)
        val (many, manyLabels) = synthetic(4_000)

        val small = LogisticRegression.fit(few, fewLabels, prior, 20.0)
        val large = LogisticRegression.fit(many, manyLabels, prior, 20.0)

        assertTrue(abs(small[0] - prior[0]) < abs(large[0] - prior[0]))
        assertTrue(abs(small[1] - prior[1]) < abs(large[1] - prior[1]))
        assertEquals(2.0, large[0], 0.5)
    }

    @Test
    fun `a stronger prior keeps the weights nearer to it`() {
        val (features, labels) = synthetic(200)
        val prior = listOf(0.0, 0.0)

        val loose = LogisticRegression.fit(features, labels, prior, 0.1)
        val firm = LogisticRegression.fit(features, labels, prior, 100.0)

        assertTrue(abs(firm[0]) < abs(loose[0]))
        assertTrue(abs(firm[1]) < abs(loose[1]))
    }
}
