package com.adl.cafe.data

import smile.classification.LogisticRegression as SmileLogisticRegression

/**
 * Logistic regression for [Recommender]'s weights, fitted by the Smile ML
 * library (L-BFGS with a light L2 penalty). Holds no Android types, so it is
 * tested on the plain JVM.
 *
 * Smile's penalty pulls towards zero, but the recommender wants its weights to
 * start at hand-picked priors and move away only as evidence builds up. So the
 * fitted weights are blended with [fit]'s `prior`, trusting the fit more the
 * more examples of the rarer label there are: with no data, or only one label,
 * the prior comes back unchanged. The intercept is fitted but not returned,
 * because callers only rank by the weighted sum and a shared constant cannot
 * change a ranking.
 */
internal object LogisticRegression {

    /** Smile's own penalty: only enough to keep the weights finite on separable data. */
    private const val LAMBDA = 1.0
    private const val TOLERANCE = 1e-5
    private const val MAX_ITERATIONS = 500

    /**
     * Weights for [features] predicting [labels], one per feature, in the
     * order of [prior]. [priorStrength] is how many examples of the rarer
     * label it takes for the fit to count as much as the prior.
     */
    fun fit(
        features: List<DoubleArray>,
        labels: List<Boolean>,
        prior: List<Double>,
        priorStrength: Double
    ): List<Double> {
        require(features.size == labels.size) { "one label per example" }
        require(features.all { it.size == prior.size }) { "one prior per feature" }
        require(priorStrength > 0) { "priorStrength must be positive" }

        // With only one label there is nothing to separate, and Smile rejects it.
        val positives = labels.count { it }
        val evidence = minOf(positives, labels.size - positives)
        if (evidence == 0) return prior

        val model = SmileLogisticRegression.binomial(
            features.toTypedArray(),
            IntArray(labels.size) { if (labels[it]) 1 else 0 },
            LAMBDA,
            TOLERANCE,
            MAX_ITERATIONS
        )
        // The weights, then the intercept last.
        val fitted = model.coefficients()
        val trust = evidence / (evidence + priorStrength)
        return prior.indices.map { j -> trust * fitted[j] + (1 - trust) * prior[j] }
    }
}
