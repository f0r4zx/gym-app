package app.gymquest.core.avatar

enum class Slot(val label: String) { HEAD("Cabeza"), TORSO("Torso"), LEGS("Piernas"), FEET("Pies"), BACK("Espalda"), HANDS("Manos"), AURA("Aura") }

enum class Rarity(val label: String, val valueMultiplier: Double) {
    COMMON("Común", 1.0), UNCOMMON("Poco común", 2.0), RARE("Raro", 5.0), EPIC("Épico", 12.0), LEGENDARY("Legendario", 30.0)
}

/** How an item is earned. Only [Boss] and [Shop] items can be traded; the rest are earned by you. */
sealed interface Unlock {
    data class Level(val level: Int) : Unlock
    data class Boss(val bossId: String) : Unlock
    data class Achievement(val id: String) : Unlock
    data class Streak(val days: Int) : Unlock
    data class Territory(val cells: Int) : Unlock
    data class WarWins(val wins: Int) : Unlock
    data class Shop(val price: Long) : Unlock
}

data class CosmeticItem(
    val id: String,
    val name: String,
    val slot: Slot,
    val rarity: Rarity,
    val unlock: Unlock,
) {
    val tradable: Boolean get() = unlock is Unlock.Boss || unlock is Unlock.Shop
}

/** Everything the player has done that can unlock cosmetics. */
data class UnlockProgress(
    val level: Int = 1,
    val bossesDefeated: Set<String> = emptySet(),
    val achievements: Set<String> = emptySet(),
    val bestStreak: Int = 0,
    val territoryCells: Int = 0,
    val warWins: Int = 0,
    val purchased: Set<String> = emptySet(),
    /** Items received from trades or raid rewards. */
    val granted: Set<String> = emptySet(),
)

object Cosmetics {
    private fun item(id: String, name: String, slot: Slot, rarity: Rarity, unlock: Unlock) = CosmeticItem(id, name, slot, rarity, unlock)

    val all: List<CosmeticItem> = listOf(
        // ---- Starter kit and level rewards
        item("starter_shirt", "Camiseta de entreno", Slot.TORSO, Rarity.COMMON, Unlock.Level(1)),
        item("starter_shorts", "Shorts deportivos", Slot.LEGS, Rarity.COMMON, Unlock.Level(1)),
        item("starter_shoes", "Tenis básicos", Slot.FEET, Rarity.COMMON, Unlock.Level(1)),
        item("headband", "Banda para la frente", Slot.HEAD, Rarity.COMMON, Unlock.Level(3)),
        item("sport_shirt", "Playera técnica", Slot.TORSO, Rarity.UNCOMMON, Unlock.Level(5)),
        item("runner_shoes", "Tenis de corredor", Slot.FEET, Rarity.UNCOMMON, Unlock.Level(8)),
        item("hoodie", "Sudadera del gym", Slot.TORSO, Rarity.UNCOMMON, Unlock.Level(10)),
        item("aura_blue", "Aura azul", Slot.AURA, Rarity.RARE, Unlock.Level(15)),
        item("compression_pants", "Mallas de compresión", Slot.LEGS, Rarity.RARE, Unlock.Level(20)),
        item("golden_shoes", "Tenis dorados", Slot.FEET, Rarity.EPIC, Unlock.Level(25)),
        item("aura_gold", "Aura dorada", Slot.AURA, Rarity.LEGENDARY, Unlock.Level(40)),
        // ---- Boss loot (ids match the loot each boss drops)
        item("slime_helmet", "Casco de slime", Slot.HEAD, Rarity.UNCOMMON, Unlock.Boss("slime_squat")),
        item("stone_gloves", "Guantes de piedra", Slot.HANDS, Rarity.UNCOMMON, Unlock.Boss("golem_pushup")),
        item("bat_wings", "Alas de murciélago", Slot.BACK, Rarity.RARE, Unlock.Boss("bat_jacks")),
        item("iron_bracers", "Brazaletes de hierro", Slot.HANDS, Rarity.RARE, Unlock.Boss("orc_curl")),
        item("hydra_scale", "Escama de hidra", Slot.TORSO, Rarity.EPIC, Unlock.Boss("hydra_pullup")),
        item("gargoyle_belt", "Cinturón de gárgola", Slot.LEGS, Rarity.RARE, Unlock.Boss("gargoyle_situp")),
        item("minotaur_horns", "Cuernos de minotauro", Slot.HEAD, Rarity.EPIC, Unlock.Boss("minotaur_lunge")),
        item("dragon_pauldrons", "Hombreras de dragón", Slot.TORSO, Rarity.EPIC, Unlock.Boss("dragon_press")),
        item("kraken_tentacle", "Tentáculo de kraken", Slot.BACK, Rarity.EPIC, Unlock.Boss("kraken_plank")),
        item("phoenix_feather", "Pluma de fénix", Slot.HEAD, Rarity.EPIC, Unlock.Boss("phoenix_burpee")),
        item("titan_belt", "Cinturón de titán", Slot.LEGS, Rarity.EPIC, Unlock.Boss("titan_deadlift")),
        item("rope_crown", "Corona del rey de la cuerda", Slot.HEAD, Rarity.RARE, Unlock.Boss("rope_king")),
        item("titan_crown", "Corona del Titán", Slot.HEAD, Rarity.LEGENDARY, Unlock.Boss("titan_gym")),
        // ---- Streaks, territory, wars, achievements
        item("flame_aura", "Aura de fuego", Slot.AURA, Rarity.RARE, Unlock.Streak(7)),
        item("iron_will_belt", "Cinturón de hierro", Slot.LEGS, Rarity.EPIC, Unlock.Streak(30)),
        item("diamond_aura", "Aura de diamante", Slot.AURA, Rarity.LEGENDARY, Unlock.Streak(100)),
        item("explorer_cape", "Capa de explorador", Slot.BACK, Rarity.RARE, Unlock.Territory(100)),
        item("conqueror_crown", "Corona de conquistador", Slot.HEAD, Rarity.LEGENDARY, Unlock.Territory(1_000)),
        item("war_banner", "Estandarte de guerra", Slot.BACK, Rarity.EPIC, Unlock.WarWins(3)),
        item("legend_cloak", "Capa de leyenda", Slot.BACK, Rarity.LEGENDARY, Unlock.Achievement("boss_all")),
        item("shield_of_friends", "Escudo de amistad", Slot.HANDS, Rarity.UNCOMMON, Unlock.Achievement("friends_5")),
        // ---- Shop
        item("sunglasses", "Lentes oscuros", Slot.HEAD, Rarity.COMMON, Unlock.Shop(150)),
        item("cap", "Gorra", Slot.HEAD, Rarity.COMMON, Unlock.Shop(120)),
        item("tank_top", "Playera sin mangas", Slot.TORSO, Rarity.COMMON, Unlock.Shop(180)),
        item("pro_shoes", "Tenis profesionales", Slot.FEET, Rarity.RARE, Unlock.Shop(450)),
        item("gold_chain", "Cadena de oro", Slot.TORSO, Rarity.RARE, Unlock.Shop(700)),
        item("neon_aura", "Aura neón", Slot.AURA, Rarity.EPIC, Unlock.Shop(1_200)),
        item("lifting_gloves", "Guantes de levantamiento", Slot.HANDS, Rarity.COMMON, Unlock.Shop(160)),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): CosmeticItem? = byId[id]

    /** The items [p] qualifies for (earned) plus anything purchased or granted. */
    fun unlocked(p: UnlockProgress): Set<String> = all.filter { qualifies(it, p) }.map { it.id }.toSet()

    private fun qualifies(item: CosmeticItem, p: UnlockProgress): Boolean {
        if (item.id in p.purchased || item.id in p.granted) return true
        return when (val u = item.unlock) {
            is Unlock.Level -> p.level >= u.level
            is Unlock.Boss -> u.bossId in p.bossesDefeated
            is Unlock.Achievement -> u.id in p.achievements
            is Unlock.Streak -> p.bestStreak >= u.days
            is Unlock.Territory -> p.territoryCells >= u.cells
            is Unlock.WarWins -> p.warWins >= u.wins
            is Unlock.Shop -> false
        }
    }

    /** What changed between two snapshots, for the "new item unlocked" popup. */
    fun newlyUnlocked(before: UnlockProgress, after: UnlockProgress): List<CosmeticItem> {
        val had = unlocked(before)
        return unlocked(after).filter { it !in had }.mapNotNull { byId[it] }
    }

    fun shopItems(): List<CosmeticItem> = all.filter { it.unlock is Unlock.Shop }
}

/** The character's current outfit: at most one item per slot, and only items the player owns. */
data class Outfit(val equipped: Map<Slot, String> = emptyMap()) {
    fun equip(itemId: String, owned: Set<String>): Outfit {
        val item = Cosmetics.byId(itemId) ?: return this
        if (itemId !in owned) return this
        return Outfit(equipped + (item.slot to itemId))
    }

    fun unequip(slot: Slot) = Outfit(equipped - slot)

    /** Drops anything no longer owned (e.g. just sold on the market). */
    fun cleaned(owned: Set<String>) = Outfit(equipped.filterValues { it in owned })

    companion object {
        fun starter(): Outfit = Outfit(
            mapOf(Slot.TORSO to "starter_shirt", Slot.LEGS to "starter_shorts", Slot.FEET to "starter_shoes"),
        )
    }
}

/** Rewards handed out at level-up. */
object LevelRewards {
    data class Reward(val coins: Int, val items: List<CosmeticItem>)

    fun forLevel(level: Int, newItems: List<CosmeticItem> = emptyList()): Reward =
        Reward(coins = 20 + level * 5 + if (level % 10 == 0) 100 else 0, items = newItems)
}
