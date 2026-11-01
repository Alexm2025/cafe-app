package com.adl.cafe.data

import com.adl.cafe.data.model.DrinkSize
import com.adl.cafe.data.model.MenuItem
import com.adl.cafe.data.model.OrderEntity
import com.adl.cafe.data.model.OrderLine
import com.adl.cafe.data.model.OrderType
import com.adl.cafe.data.model.OrderWithLines
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM tests for the recommendation model. Like [Pricing], [Recommender]
 * holds no Android types, so these need no Robolectric.
 */
class RecommenderTest {

    private val utc = TimeZone.getTimeZone("UTC")

    /** 2026-01-15 00:00 UTC; tests add hours to land in a daypart. */
    private val day = 1_768_435_200_000L
    private val hour = 60 * 60 * 1000L
    private val morning = day + 8 * hour
    private val evening = day + 20 * hour

    private val menu = SeedData.menu.mapIndexed { index, item -> item.copy(id = index + 1L) }
    private fun item(name: String): MenuItem = menu.single { it.name == name }

    private var nextOrderId = 1L
    private fun order(at: Long, vararg names: String, quantity: Int = 1) = OrderWithLines(
        order = OrderEntity(
            id = nextOrderId++,
            placedAtMillis = at,
            customerName = "Sam",
            orderType = OrderType.PICKUP,
            note = "",
            subtotalCents = 0,
            taxCents = 0,
            totalCents = 0
        ),
        lines = names.map { name ->
            val item = item(name)
            OrderLine(
                orderId = 0,
                itemName = name,
                emoji = item.emoji,
                size = DrinkSize.SMALL,
                sizable = item.sizable,
                quantity = quantity,
                unitPriceCents = item.priceCents
            )
        }
    )

    private fun daysBefore(at: Long, days: Int) = at - days * 24 * hour

    private fun forYou(history: List<OrderWithLines>, now: Long = morning, limit: Int = 6) =
        Recommender.forYou(menu, history, now, utc, limit).map { it.name }

    private fun goesWellWith(
        history: List<OrderWithLines>,
        vararg cart: String,
        limit: Int = 4
    ) = Recommender.goesWellWith(
        menu,
        history,
        cart.mapTo(HashSet()) { item(it).id },
        morning,
        limit
    ).map { it.name }

    // ---- forYou -----------------------------------------------------------

    @Test
    fun `no history means no for-you row`() {
        assertEquals(emptyList<String>(), forYou(emptyList()))
    }

    @Test
    fun `the item you order most comes first`() {
        val history = listOf(
            order(daysBefore(morning, 1), "Flat White"),
            order(daysBefore(morning, 2), "Flat White"),
            order(daysBefore(morning, 3), "Cortado")
        )
        assertEquals(listOf("Flat White", "Cortado"), forYou(history).take(2))
    }

    @Test
    fun `a recent order outranks an old one`() {
        val history = listOf(
            order(daysBefore(morning, 1), "Cortado"),
            order(daysBefore(morning, 90), "Flat White")
        )
        val ranked = forYou(history)
        assertTrue(ranked.indexOf("Cortado") < ranked.indexOf("Flat White"))
    }

    @Test
    fun `quantity in one order does not outweigh a habit`() {
        val history = listOf(
            order(daysBefore(morning, 1), "Mocha", quantity = 12),
            order(daysBefore(morning, 1), "Cortado"),
            order(daysBefore(morning, 2), "Cortado")
        )
        assertEquals("Cortado", forYou(history).first())
    }

    @Test
    fun `time of day picks the category you usually have then`() {
        // Equal history for both: lattes in the morning, earl grey in the evening.
        val history = listOf(
            order(daysBefore(morning, 1), "Latte"),
            order(daysBefore(morning, 2), "Latte"),
            order(daysBefore(evening, 1), "Earl Grey"),
            order(daysBefore(evening, 2), "Earl Grey")
        )

        val atBreakfast = forYou(history, now = morning)
        assertTrue(atBreakfast.indexOf("Latte") < atBreakfast.indexOf("Earl Grey"))

        val atNight = forYou(history, now = evening)
        assertTrue(atNight.indexOf("Earl Grey") < atNight.indexOf("Latte"))
    }

    @Test
    fun `items gone from the menu are ignored`() {
        val withoutCortado = menu.filter { it.name != "Cortado" }
        val history = listOf(order(daysBefore(morning, 1), "Cortado", "Flat White"))

        val ranked = Recommender.forYou(withoutCortado, history, morning, utc).map { it.name }

        assertFalse("Cortado" in ranked)
        assertEquals("Flat White", ranked.first())
    }

    @Test
    fun `unavailable items are never recommended`() {
        val soldOut = menu.map { if (it.name == "Flat White") it.copy(isAvailable = false) else it }
        val history = listOf(order(daysBefore(morning, 1), "Flat White"))

        val ranked = Recommender.forYou(soldOut, history, morning, utc).map { it.name }

        assertFalse("Flat White" in ranked)
    }

    @Test
    fun `for-you respects the limit`() {
        val history = listOf(order(daysBefore(morning, 1), "Latte"))
        assertEquals(3, forYou(history, limit = 3).size)
    }

    // ---- goesWellWith -----------------------------------------------------

    @Test
    fun `an empty cart has no pairings`() {
        assertEquals(emptyList<String>(), goesWellWith(emptyList()))
    }

    @Test
    fun `with no history a drink suggests food, popular first`() {
        val pairings = goesWellWith(emptyList(), "Latte")

        assertEquals("Butter Croissant", pairings.first())
        assertTrue(pairings.all { !item(it).sizable })
    }

    @Test
    fun `with no history food suggests a drink`() {
        val pairings = goesWellWith(emptyList(), "Avocado Toast")
        assertTrue(pairings.isNotEmpty())
        assertTrue(pairings.all { item(it).sizable })
    }

    @Test
    fun `what you have had alongside the cart beats a generic pairing`() {
        val history = listOf(
            order(daysBefore(morning, 1), "Latte", "Blueberry Muffin"),
            order(daysBefore(morning, 5), "Latte", "Blueberry Muffin")
        )
        assertEquals("Blueberry Muffin", goesWellWith(history, "Latte").first())
    }

    @Test
    fun `nothing already in the cart is suggested, at any size`() {
        val history = listOf(order(daysBefore(morning, 1), "Latte", "Butter Croissant"))
        val pairings = goesWellWith(history, "Latte", "Butter Croissant", limit = 21)

        assertFalse("Latte" in pairings)
        assertFalse("Butter Croissant" in pairings)
    }

    @Test
    fun `pairings respect the limit`() {
        assertEquals(2, goesWellWith(emptyList(), "Latte", limit = 2).size)
    }

    // ---- learned weights --------------------------------------------------

    @Test
    fun `with no history the weights are the priors`() {
        assertEquals(Recommender.FOR_YOU_PRIOR, Recommender.learnForYouWeights(menu, emptyList(), utc))
        assertEquals(Recommender.PAIRING_PRIOR, Recommender.learnPairingWeights(menu, emptyList()))
    }

    @Test
    fun `a customer led by the time of day raises the daypart weight`() {
        // A different espresso drink every morning and a different tea every evening.
        val espresso = listOf("Americano", "Latte", "Cappuccino", "Flat White", "Mocha", "Cortado")
        val tea = listOf("Earl Grey", "Green Sencha", "Masala Chai")
        val history = (1..30).flatMap { days ->
            listOf(
                order(daysBefore(morning, days), espresso[days % espresso.size]),
                order(daysBefore(evening, days), tea[days % tea.size])
            )
        }

        val (_, daypart, _) = Recommender.learnForYouWeights(menu, history, utc)

        assertTrue(daypart > Recommender.FOR_YOU_PRIOR[1])
    }

    @Test
    fun `a customer who never orders popular items lowers the popular weight`() {
        val usual = listOf("Pour Over", "Cortado", "Green Sencha")
        val history = (1..30).map { days -> order(daysBefore(morning, days), usual[days % usual.size]) }

        val (_, _, popular) = Recommender.learnForYouWeights(menu, history, utc)

        assertTrue(popular < Recommender.FOR_YOU_PRIOR[2])
    }

    @Test
    fun `a customer who pairs drinks with drinks is offered a drink, not food`() {
        val pairs = listOf(
            arrayOf("Latte", "Mocha"),
            arrayOf("Cortado", "Flat White"),
            arrayOf("Americano", "Latte"),
            arrayOf("Earl Grey", "Masala Chai")
        )
        val history = (1..24).map { days -> order(daysBefore(morning, days), *pairs[days % pairs.size]) }
        val cart = setOf(item("Cappuccino").id)

        // The priors' drink-to-food rule wins on a drink never ordered before...
        val byPrior = Recommender.goesWellWith(
            menu, history, cart, morning, weights = Recommender.PAIRING_PRIOR
        )
        assertEquals("Butter Croissant", byPrior.first().name)

        // ...but the learned weights know this customer.
        val learned = Recommender.goesWellWith(menu, history, cart, morning)
        assertTrue(learned.first().sizable)
        val (_, complement, _, _) = Recommender.learnPairingWeights(menu, history)
        assertTrue(complement < Recommender.PAIRING_PRIOR[1])
    }

    // ---- daypart ----------------------------------------------------------

    @Test
    fun `dayparts split at five, eleven and five`() {
        assertEquals(Recommender.Daypart.EVENING, Recommender.daypartOf(day + 4 * hour, utc))
        assertEquals(Recommender.Daypart.MORNING, Recommender.daypartOf(day + 5 * hour, utc))
        assertEquals(Recommender.Daypart.AFTERNOON, Recommender.daypartOf(day + 11 * hour, utc))
        assertEquals(Recommender.Daypart.EVENING, Recommender.daypartOf(day + 17 * hour, utc))
    }
}
