# ADL Cafe

An Android cafe ordering app: browse the menu, configure a drink, build a cart,
check out, and keep a history of past orders. Orders are sent to a
[Supabase](https://supabase.com) backend, which also serves the menu. The cart,
order history and a cached copy of the menu live in a Room database on the
device, so they survive an app restart and the menu works offline.

**Stack:** Kotlin · XML layouts + ViewBinding · Navigation component · Room · Coroutines/Flow · Material 3 · Supabase (supabase-kt) · Media3 ExoPlayer

## Screens

| Screen | What it does |
| --- | --- |
| **Menu** | Search box, horizontal category filters, list of available items with a quick-add button; a new search or filter starts from the top of the list. A **For you** row at the top ranks items from your own order history, using weights learned on the device by logistic regression (the Smile ML library). Popular items carry a tag beside the price. Quick-add confirms with a snackbar above the bottom bar, so Cart stays one tap away. Tapping an item's emoji opens a half-screen sheet with a looping video of it and its description. |
| **Item detail** | Size selector (drinks only), quantity stepper, live total, add to cart. |
| **Cart** | Per-line quantity steppers and remove, a **Goes well with** row of suggestions, subtotal / tax / total, empty state. |
| **Checkout** | Name, pickup vs dine-in, note for the barista, order summary. |
| **Order confirmed** | Server-assigned order number (`ADL-1001`), totals, fulfilment message. |
| **Orders** | Past orders, newest first, with their line items and status. |

## Opening it

1. Set up the backend (below) once.
2. Open Android Studio → **File ▸ Open** → select this folder.
3. Let Gradle sync. Android Studio adds your SDK path to `local.properties` on
   first sync; the file is intentionally not checked in.
4. Run the `app` configuration on an emulator or device (minSdk 24).

Command line, once the Android SDK path is set:

```bash
./gradlew assembleDebug   # build
./gradlew test            # unit tests
```

## Backend (Supabase)

1. Create a Supabase project. Leave **Data API** on; turn off **Automatically
   expose new tables** and turn on **automatic RLS**.
2. Paste `supabase/schema.sql` into the SQL Editor and run it. It creates the
   tables, the `place_order` function, the grants, and seeds the menu.
3. Add the project URL and **publishable** key (Project Settings → API) to
   `local.properties`:

   ```properties
   supabase.url=https://<project-ref>.supabase.co
   supabase.key=sb_publishable_...
   ```

   The publishable key ships inside the APK by design. Never put the secret /
   `service_role` key in the app.

With the publishable key the app can only read `menu_items` and call
`place_order`. Orders are not readable through the API; see them in the
dashboard's Table Editor. `place_order` prices the order from the server's own
menu, so a modified client cannot set its own prices.

## Tests

Everything lives in `src/test/` and runs on the JVM — no emulator, no device:

```bash
./gradlew test
./gradlew test --tests 'com.adl.cafe.data.PricingTest'
```

| Suite | Covers |
| --- | --- |
| `PricingTest` | Plain JUnit. Integer tax arithmetic: the exact-half-cent boundary, monotonicity, overflow at the top of the `Int` range, and that the displayed rate matches the rate charged. |
| `RecommenderTest` | Plain JUnit. The recommendation model: frequency and recency, time-of-day category fit, pairings from past orders, the drink/food fallback before any history, and never suggesting what is unavailable or already in the cart. Covers the learned weights too: they equal the priors with no history, and they move with the customer's habits (time of day, avoiding popular items, pairing drinks with drinks). |
| `LogisticRegressionTest` | Plain JUnit. The Smile-backed fitter behind those weights: recovers known coefficients from synthetic data, returns the prior with no data or a single class, moves further from the prior as examples build up, and a stronger prior holds the weights closer. |
| `CafeRepositoryTest` | An in-memory Room database under [Robolectric](https://robolectric.org), with a fake backend. Cart merging under 24 concurrent adds; order placement storing the server's receipt, keeping the cart when the server fails, and rolling back cleanly when a local write fails; menu refresh upserting without emptying the cart. |

Robolectric downloads an `android-all` jar on first run, so the initial
`./gradlew test` needs network access.

## Architecture

```
ui/<screen>/   Fragment + ViewModel (+ RecyclerView adapter)
      ↓ observes StateFlow
data/CafeRepository      the only thing the UI talks to
      ↓                  (also holds AppDatabase, for withTransaction)
data/dao/*      MenuDao · CartDao · OrderDao  (Flow-returning queries)     data/remote/CafeRemote
      ↓                                                                        ↓
data/AppDatabase         Room, 4 entities                                  Supabase
```

- **`CafeApplication`** builds the database, the Supabase client and the
  repository, seeds the menu on first launch, then refreshes it from Supabase.
  Dependencies are passed by hand through `CafeViewModelFactory` — the project
  is too small for a DI framework to pay for itself.
- **Room is what the UI observes.** Supabase is the source of truth for the menu
  (copied into Room by `refreshMenu`) and the place orders go; the screens never
  talk to the network directly.
- **Money is integer cents** everywhere (`priceCents`, `totalCents`) and only
  formatted for display by `Int.asMoney()`. No floating point arithmetic on prices.
- **`Pricing`** holds the tax rate, so the cart and checkout screen agree. The
  rate is basis points (850 = 8.5%) with half-up integer rounding, and the tax
  row's label is derived from that same constant rather than hardcoded in
  `strings.xml`. `place_order` on the server charges its own copy of the rate
  and the size surcharges, and the stored receipt is the server's; change both
  together.
- **Order lines snapshot** the item name, emoji and unit price at the time of
  ordering. Editing or deleting a menu item later does not rewrite old receipts.
- **The cart merges duplicates**: adding an item that is already in the cart at
  the same size bumps the quantity instead of adding a second row (enforced by a
  unique index on `menuItemId + size`). The lookup and the write share a
  transaction, so concurrent quick-adds cannot both miss the row and collide.
- **Checkout is atomic on both sides.** `place_order` writes the order and its
  lines in one database transaction on the server. Only after the server accepts
  it does the app store the receipt and clear the cart, again in one local
  transaction. A failed request leaves the cart intact for a retry.

### Data model

| Entity | Notes |
| --- | --- |
| `MenuItem` | `sizable` distinguishes drinks (S/M/L, with surcharge) from food. |
| `CartItem` | References `MenuItem`; `CartLine` joins the two via `@Relation`. |
| `OrderEntity` | Totals, customer name, order type, status. |
| `OrderLine` | Snapshotted line; `OrderWithLines` joins via `@Relation`. |

Enums are persisted by name through `Converters`, so reordering an enum cannot
corrupt saved rows.

## Changing the menu

Edit `menu_items` in the Supabase Table Editor. The app picks up changes the
next time it starts. To hide an item without deleting it, set `is_available` to
false; deleting it also removes it from any customer's cart.

`data/SeedData.kt` is only the offline fallback shown before the first
successful refresh. Its ids match the server seed in `supabase/schema.sql`
(same items, same order), so keep the two in step if you change either.

## Item videos

The preview sheet plays muted, looping MP4s bundled in
`app/src/main/assets/video/`, found by the item's name, not its id. For an
item called "Pain au Chocolat" it tries, in order:

1. `video/pain_au_chocolat.mp4`: the name lowercased, with anything that is
   not a letter or digit replaced by `_`.
2. `video/category/pastries.mp4`: the same rule applied to the item's category.
3. Otherwise the item's emoji, shown large.

So a new menu item needs no code change, only a clip with the right file name.
Renaming an item in Supabase means renaming its clip too, or it falls back to
the emoji.

The current clips are from [Pexels](https://www.pexels.com/videos/), which
allows free use in apps without attribution. Keep new clips short (5–15 s),
at SD size (about 960×540), and ideally under 3 MB, because every clip is built
into the APK. The 21 clips add about 32 MB.
