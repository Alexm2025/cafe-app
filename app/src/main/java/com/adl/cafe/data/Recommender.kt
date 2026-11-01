package com.adl.cafe.data

import com.adl.cafe.data.model.MenuItem
import com.adl.cafe.data.model.OrderWithLines
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.pow

/**
 * On-device menu recommendations from this device's own order history.
 * Holds no Android types, so it is tested on the plain JVM like [Pricing].
 *
 * Each row scores an item as a weighted sum of a few signals, each a count of
 * past orders decayed by age so tastes can change. An order counts once per
 * item it contains, whatever the quantity, so a single office run of six
 * lattes does not outweigh a daily habit.
 *
 * The weights are learned by [LogisticRegression] (Smile) from replayed history:
 * every past order becomes one example per menu item, with features computed only
 * from the orders placed before it, labelled by whether that order included the
 * item. The fit is blended with hand-picked prior weights ([FOR_YOU_PRIOR],
 * [PAIRING_PRIOR]), which is what it returns with no history, so a new device
 * ranks sensibly and the data takes over as it builds up.
 *
 * History is matched to the menu **by name**: [com.adl.cafe.data.model.OrderLine]
 * snapshots the name rather than pointing at a menu row, so an item renamed on
 * the server starts with no history. Items no longer on the menu are ignored.
 *
 * Scores are Doubles because they only rank; none of this touches a price.
 */
object Recommender {

    /** An order this many days old counts half as much as one placed today. */
    const val HALF_LIFE_DAYS = 14.0

    /** "For you" weights before any learning: personal affinity, daypart fit, popular. */
    val FOR_YOU_PRIOR = listOf(1.0, 0.5, 0.25)

    /** "Goes well with" weights before any learning: paired before, complements, affinity, popular. */
    val PAIRING_PRIOR = listOf(1.0, 0.6, 0.3, 0.15)

    /**
     * How firmly the weights hold to their priors: the number of purchases in
     * the replayed history at which the learned weights count as much as the
     * priors. A few orders barely move them; a few dozen mostly replace them.
     */
    private const val PRIOR_STRENGTH = 20.0

    /** Learning replays only the most recent orders, which bounds its cost. */
    private const val MAX_TRAINING_ORDERS = 200

    private const val DAY_MILLIS = 24 * 60 * 60 * 1000.0

    /**
     * Items for the menu's "For you" row, best first. Empty until the first
     * order: with no history this would only repeat the Popular items, which
     * already lead the menu.
     */
    fun forYou(
        menu: List<MenuItem>,
        history: List<OrderWithLines>,
        nowMillis: Long,
        timeZone: TimeZone = TimeZone.getDefault(),
        limit: Int = 6,
        weights: List<Double> = learnForYouWeights(menu, history, timeZone)
    ): List<MenuItem> {
        if (history.isEmpty()) return emptyList()
        val features = ForYouFeatures(menu.associateBy { it.name }, history, nowMillis, timeZone)
        return rank(menu, limit) { weights.dot(features.of(it)) }
    }

    /**
     * Items for the cart's "Goes well with" row, best first, never including
     * anything already in the cart. Works before the first order: a cart of
     * only drinks suggests food, and vice versa.
     */
    fun goesWellWith(
        menu: List<MenuItem>,
        history: List<OrderWithLines>,
        cartMenuItemIds: Set<Long>,
        nowMillis: Long,
        limit: Int = 4,
        weights: List<Double> = learnPairingWeights(menu, history)
    ): List<MenuItem> {
        val inCart = menu.filter { it.id in cartMenuItemIds }
        if (inCart.isEmpty()) return emptyList()
        val byName = menu.associateBy { it.name }
        val features = PairingFeatures(
            byName, history, inCart, nowMillis, Affinity(history, byName, nowMillis)
        )
        return rank(menu.filter { it.id !in cartMenuItemIds }, limit) { weights.dot(features.of(it)) }
    }

    /**
     * Fits the "For you" weights, in [FOR_YOU_PRIOR]'s order. Each past order
     * after the first is replayed at the time it was placed: every menu item is
     * an example, positive if the order included it.
     */
    fun learnForYouWeights(
        menu: List<MenuItem>,
        history: List<OrderWithLines>,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<Double> {
        val byName = menu.associateBy { it.name }
        val ordered = history.sortedBy { it.order.placedAtMillis }
        val examples = ArrayList<DoubleArray>()
        val labels = ArrayList<Boolean>()
        // The row is hidden until there is history, so the first order teaches it nothing.
        for (k in trainingRange(ordered.size, first = 1)) {
            val target = ordered[k]
            val bought = target.itemNames().filterTo(HashSet()) { it in byName }
            if (bought.isEmpty()) continue
            val features = ForYouFeatures(
                byName, ordered.subList(0, k), target.order.placedAtMillis, timeZone
            )
            for (item in menu) {
                examples += features.of(item)
                labels += item.name in bought
            }
        }
        return LogisticRegression.fit(examples, labels, FOR_YOU_PRIOR, PRIOR_STRENGTH)
    }

    /**
     * Fits the "Goes well with" weights, in [PAIRING_PRIOR]'s order. Each past
     * order of two or more items is replayed once per item, as though that item
     * alone were in the cart: every other menu item is an example, positive if
     * the order included it.
     */
    fun learnPairingWeights(menu: List<MenuItem>, history: List<OrderWithLines>): List<Double> {
        val byName = menu.associateBy { it.name }
        val ordered = history.sortedBy { it.order.placedAtMillis }
        val examples = ArrayList<DoubleArray>()
        val labels = ArrayList<Boolean>()
        for (k in trainingRange(ordered.size, first = 0)) {
            val target = ordered[k]
            val bought = target.itemNames().mapNotNull { byName[it] }
            if (bought.size < 2) continue
            val before = ordered.subList(0, k)
            val at = target.order.placedAtMillis
            val affinity = Affinity(before, byName, at)
            for (cartItem in bought) {
                val features = PairingFeatures(byName, before, listOf(cartItem), at, affinity)
                for (item in menu) {
                    if (item.name == cartItem.name) continue
                    examples += features.of(item)
                    labels += item in bought
                }
            }
        }
        return LogisticRegression.fit(examples, labels, PAIRING_PRIOR, PRIOR_STRENGTH)
    }

    /** The orders to replay, oldest first: at most [MAX_TRAINING_ORDERS], never before [first]. */
    private fun trainingRange(size: Int, first: Int): IntRange =
        maxOf(first, size - MAX_TRAINING_ORDERS) until size

    /**
     * "For you" features as of [nowMillis], from [history]: personal affinity
     * scaled to 0..1, and the share of this daypart's orders that included the
     * item's category.
     */
    private class ForYouFeatures(
        byName: Map<String, MenuItem>,
        history: List<OrderWithLines>,
        nowMillis: Long,
        timeZone: TimeZone
    ) {
        private val affinity = Affinity(history, byName, nowMillis)
        private val categoryWeight = HashMap<String, Double>()
        private var daypartTotal = 0.0

        init {
            val daypart = daypartOf(nowMillis, timeZone)
            for (order in history) {
                if (daypartOf(order.order.placedAtMillis, timeZone) != daypart) continue
                val w = decay(order.order.placedAtMillis, nowMillis)
                val categories = order.lines.mapNotNullTo(HashSet()) { byName[it.itemName]?.category }
                if (categories.isEmpty()) continue
                daypartTotal += w
                for (category in categories) categoryWeight.merge(category, w, Double::plus)
            }
        }

        fun of(item: MenuItem): DoubleArray {
            val share = if (daypartTotal > 0) (categoryWeight[item.category] ?: 0.0) / daypartTotal else 0.0
            return doubleArrayOf(affinity.of(item), share, item.isPopular.toScore())
        }
    }

    /**
     * "Goes well with" features for a [cart], from [history]: how often the
     * item was ordered alongside something in the cart (scaled to 0..1),
     * whether it is food to the cart's drinks or a drink to its food, affinity
     * and popularity.
     */
    private class PairingFeatures(
        byName: Map<String, MenuItem>,
        history: List<OrderWithLines>,
        cart: List<MenuItem>,
        nowMillis: Long,
        private val affinity: Affinity
    ) {
        private val pairing = HashMap<String, Double>()
        private val maxPairing: Double

        // `sizable` is the menu's own drink/food split, so no category names are hardcoded.
        private val cartHasDrink = cart.any { it.sizable }
        private val cartHasFood = cart.any { !it.sizable }

        init {
            val cartNames = cart.mapTo(HashSet()) { it.name }
            for (order in history) {
                val names = order.itemNames()
                if (names.none { it in cartNames }) continue
                val w = decay(order.order.placedAtMillis, nowMillis)
                for (name in names) {
                    if (name !in cartNames && name in byName) pairing.merge(name, w, Double::plus)
                }
            }
            maxPairing = pairing.values.maxOrNull() ?: 0.0
        }

        fun of(item: MenuItem): DoubleArray {
            val pair = if (maxPairing > 0) (pairing[item.name] ?: 0.0) / maxPairing else 0.0
            val complements = (cartHasDrink && !cartHasFood && !item.sizable) ||
                (cartHasFood && !cartHasDrink && item.sizable)
            return doubleArrayOf(pair, complements.toScore(), affinity.of(item), item.isPopular.toScore())
        }
    }

    /** Decayed count of orders containing each item still on the menu, scaled so the top item is 1. */
    private class Affinity(
        history: List<OrderWithLines>,
        byName: Map<String, MenuItem>,
        nowMillis: Long
    ) {
        private val counts = HashMap<String, Double>()
        private val max: Double

        init {
            for (order in history) {
                val w = decay(order.order.placedAtMillis, nowMillis)
                order.itemNames()
                    .filter { it in byName }
                    .forEach { counts.merge(it, w, Double::plus) }
            }
            max = counts.values.maxOrNull() ?: 0.0
        }

        fun of(item: MenuItem): Double = if (max > 0) (counts[item.name] ?: 0.0) / max else 0.0
    }

    /** Positive scores only, best first; ties break by name so the row is stable. */
    private inline fun rank(
        candidates: List<MenuItem>,
        limit: Int,
        score: (MenuItem) -> Double
    ): List<MenuItem> = candidates
        .filter { it.isAvailable }
        .map { it to score(it) }
        .filter { it.second > 0.0 }
        .sortedWith(compareByDescending<Pair<MenuItem, Double>> { it.second }.thenBy { it.first.name })
        .take(limit)
        .map { it.first }

    /** Halves every [HALF_LIFE_DAYS]. A clock that has gone backwards counts as today. */
    private fun decay(placedAtMillis: Long, nowMillis: Long): Double {
        val ageDays = (nowMillis - placedAtMillis).coerceAtLeast(0) / DAY_MILLIS
        return 0.5.pow(ageDays / HALF_LIFE_DAYS)
    }

    internal enum class Daypart { MORNING, AFTERNOON, EVENING }

    /** Calendar rather than java.time, which needs API 26 without desugaring. */
    internal fun daypartOf(millis: Long, timeZone: TimeZone): Daypart {
        val hour = Calendar.getInstance(timeZone).apply { timeInMillis = millis }
            .get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..10 -> Daypart.MORNING
            in 11..16 -> Daypart.AFTERNOON
            else -> Daypart.EVENING
        }
    }

    /** Each item once, however many lines or sizes it was ordered in. */
    private fun OrderWithLines.itemNames(): Set<String> = lines.mapTo(HashSet()) { it.itemName }

    private fun List<Double>.dot(x: DoubleArray): Double {
        var sum = 0.0
        for (j in indices) sum += this[j] * x[j]
        return sum
    }

    private fun Boolean.toScore(): Double = if (this) 1.0 else 0.0
}
