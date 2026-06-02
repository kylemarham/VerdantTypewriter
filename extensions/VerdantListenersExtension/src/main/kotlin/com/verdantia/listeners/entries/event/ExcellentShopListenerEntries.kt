package com.verdantia.listeners.entries.event

import com.typewritermc.core.books.pages.Colors
import com.typewritermc.core.entries.Query
import com.typewritermc.core.extension.Initializable
import com.typewritermc.core.extension.annotations.Entry
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Singleton
import com.typewritermc.engine.paper.entry.StaticEntry
import com.typewritermc.engine.paper.entry.entries.ReadableFactEntry
import com.typewritermc.engine.paper.entry.entries.WritableFactEntry
import com.typewritermc.engine.paper.plugin
import org.bukkit.entity.Player
import org.bukkit.event.EventPriority
import org.bukkit.event.Listener
import org.koin.core.component.KoinComponent

// ---------------------------------------------------------------------------
// ExcellentShop sell listener
// ---------------------------------------------------------------------------

/**
 * Sets a Typewriter fact to 1 (one-shot) when a player sells at an ExcellentShop.
 *
 * ExcellentShop's sell event class is `su.nightexpress.nexshop.shop.virtual.impl.VirtualShopTransactionEvent`
 * on some builds, or `su.nightexpress.nexshop.api.shop.event.ShopSellEvent` on others.
 * We hook both via reflection so the extension compiles without the ExcellentShop jar.
 */
@Entry(
    "excellent_shop_sell_listener",
    "Set a fact (one-shot) when a player makes a sale at an ExcellentShop.",
    Colors.GREEN,
    "fa6-solid:coins",
)
class ExcellentShopSellListenerEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("Typewriter fact entry id to set to 1 on first sale.")
    val factName: String = "",
    @Help("If true, fact is only set once per player (default). If false, increments on every sale.")
    val oneShot: Boolean = true,
) : StaticEntry

// ---------------------------------------------------------------------------
// ExcellentShop buy listener
// ---------------------------------------------------------------------------

/**
 * Sets a Typewriter fact to 1 (one-shot) when a player buys from an ExcellentShop.
 */
@Entry(
    "excellent_shop_buy_listener",
    "Set a fact (one-shot) when a player makes a purchase at an ExcellentShop.",
    Colors.BLUE,
    "fa6-solid:cart-shopping",
)
class ExcellentShopBuyListenerEntry(
    override val id: String = "",
    override val name: String = "",
    @Help("Typewriter fact entry id to set to 1 on first purchase.")
    val factName: String = "",
    @Help("If true, fact is only set once per player (default). If false, increments on every purchase.")
    val oneShot: Boolean = true,
) : StaticEntry

// ---------------------------------------------------------------------------
// Shared registry — handles both sell and buy events
// ---------------------------------------------------------------------------

/**
 * Reflection-based ExcellentShop event bridge. Attempts to find and register
 * both the sell and buy event classes at server startup. Falls back gracefully
 * if ExcellentShop is not installed.
 *
 * Supported event class names (tried in order):
 *   Sell: su.nightexpress.nexshop.api.shop.event.ShopSellEvent
 *         su.nightexpress.nexshop.shop.virtual.impl.VirtualShopTransactionEvent (sell)
 *   Buy:  su.nightexpress.nexshop.api.shop.event.ShopBuyEvent
 *         su.nightexpress.nexshop.shop.virtual.impl.VirtualShopTransactionEvent (buy)
 *
 * Both event classes expose `getPlayer(): Player`.
 * The transaction type (sell vs buy) is read via `getTransaction().getType()` or
 * `getType()` returning a string/enum containing "SELL" or "BUY".
 */
@Singleton
class ExcellentShopListenerRegistry : Initializable, Listener, KoinComponent {

    private val SELL_EVENT_CLASSES = listOf(
        "su.nightexpress.nexshop.api.shop.event.ShopSellEvent",
        "su.nightexpress.nexshop.api.shop.event.ShopTransactionEvent",
        "su.nightexpress.nexshop.shop.virtual.impl.VirtualShopTransactionEvent",
    )
    private val BUY_EVENT_CLASSES = listOf(
        "su.nightexpress.nexshop.api.shop.event.ShopBuyEvent",
        "su.nightexpress.nexshop.api.shop.event.ShopTransactionEvent",
        "su.nightexpress.nexshop.shop.virtual.impl.VirtualShopTransactionEvent",
    )

    // Tracks which event classes we've already registered to avoid double-register.
    private val registered = mutableSetOf<String>()

    override suspend fun initialize() {
        val sellEntries = Query.find<ExcellentShopSellListenerEntry>().toList()
        val buyEntries = Query.find<ExcellentShopBuyListenerEntry>().toList()
        if (sellEntries.isEmpty() && buyEntries.isEmpty()) {
            plugin.logger.info("[VerdantiaListeners] No excellent_shop_sell/buy_listener entries found.")
            return
        }

        var anyRegistered = false
        val candidateClasses = (SELL_EVENT_CLASSES + BUY_EVENT_CLASSES).distinct()
        for (className in candidateClasses) {
            val klass = try {
                @Suppress("UNCHECKED_CAST")
                Class.forName(className) as Class<out org.bukkit.event.Event>
            } catch (_: Throwable) {
                continue
            }
            if (registered.add(className)) {
                plugin.server.pluginManager.registerEvent(
                    klass,
                    this,
                    EventPriority.MONITOR,
                    { _, event -> if (klass.isInstance(event)) handleShopEvent(event) },
                    plugin,
                    true,
                )
                plugin.logger.info("[VerdantiaListeners] Hooked ExcellentShop event: $className")
                anyRegistered = true
            }
        }

        if (!anyRegistered) {
            // ExcellentShop 5.x removed the public buy/sell/transaction Bukkit events
            // (5.0.1 only fires ChestShopCreate/Remove + AuctionListingCreate). There is
            // no transaction event to hook on this version regardless of class FQN, so
            // these entries are inert by environment, not misconfigured. INFO, not WARN.
            plugin.logger.info(
                "[VerdantiaListeners] excellent_shop_*_listener: no ExcellentShop buy/sell " +
                    "transaction event on this version (ExcellentShop 5.x exposes none) — " +
                    "${sellEntries.size + buyEntries.size} entry(ies) inert. This is expected.",
            )
        }
    }

    private fun handleShopEvent(event: org.bukkit.event.Event) {
        val player = extractPlayer(event) ?: return
        val transactionType = extractTransactionType(event)  // "SELL", "BUY", or null (unknown)

        // Sell-side
        if (transactionType == null || transactionType.contains("SELL", ignoreCase = true)) {
            val sellEntries = Query.find<ExcellentShopSellListenerEntry>().toList()
            for (entry in sellEntries) {
                applyFact(player, entry.factName, entry.oneShot, "excellent_shop_sell_listener")
            }
        }

        // Buy-side
        if (transactionType == null || transactionType.contains("BUY", ignoreCase = true)) {
            val buyEntries = Query.find<ExcellentShopBuyListenerEntry>().toList()
            for (entry in buyEntries) {
                applyFact(player, entry.factName, entry.oneShot, "excellent_shop_buy_listener")
            }
        }
    }

    private fun extractPlayer(event: org.bukkit.event.Event): Player? {
        return try {
            val m = event.javaClass.methods.firstOrNull { it.name == "getPlayer" && it.parameterCount == 0 }
            m?.invoke(event) as? Player
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Attempts to read the transaction type string from the event.
     * ExcellentShop's ShopTransactionEvent has getType() -> TradeType enum; others may have getTransaction().getType().
     * Returns null if the type cannot be determined (caller applies to both sell and buy).
     */
    private fun extractTransactionType(event: org.bukkit.event.Event): String? {
        return try {
            // Try getType() directly first
            val typeDirect = event.javaClass.methods.firstOrNull { it.name == "getType" && it.parameterCount == 0 }
                ?.invoke(event)?.toString()
            if (typeDirect != null) return typeDirect

            // Try getTransaction().getType()
            val tx = event.javaClass.methods.firstOrNull { it.name == "getTransaction" && it.parameterCount == 0 }
                ?.invoke(event) ?: return null
            tx.javaClass.methods.firstOrNull { it.name == "getType" && it.parameterCount == 0 }
                ?.invoke(tx)?.toString()
        } catch (_: Throwable) {
            null
        }
    }

    private fun applyFact(player: Player, factName: String, oneShot: Boolean, source: String) {
        try {
            val current = Query.findById<ReadableFactEntry>(factName)?.readForPlayersGroup(player)?.value ?: 0
            if (oneShot && current >= 1) return
            val writable = Query.findById<WritableFactEntry>(factName) ?: run {
                plugin.logger.warning("[VerdantiaListeners] $source: fact '$factName' not writable.")
                return
            }
            writable.write(player, if (oneShot) 1 else current + 1)
            plugin.logger.fine("[VerdantiaListeners] $source: player=${player.name} fact=$factName -> written")
        } catch (t: Throwable) {
            plugin.logger.warning("[VerdantiaListeners] $source: error for ${player.name}: ${t.message}")
        }
    }

    override suspend fun shutdown() {
        org.bukkit.event.HandlerList.unregisterAll(this)
    }
}
