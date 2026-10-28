package com.esmeraldago.core.data

import kotlinx.serialization.Serializable

/** Un Pokémon de la lista de regionales: número, código de forma de Bulbapedia ("PA", "E", "B"…), nombre y forma. */
@Serializable
data class RegionalEntry(val dex: Int, val code: String, val name: String, val form: String? = null)

/** Una fila: dónde aparecen (texto de la fuente), aclaración si la hay, y quiénes. [biome]: por bioma, no por región. */
@Serializable
data class RegionalGroup(val region: String, val note: String? = null, val members: List<RegionalEntry>, val biome: Boolean = false)

/**
 * Pokémon que solo aparecen en ciertas regiones (o biomas) del mundo, según Bulbapedia ("List of Pokémon by
 * availability in Pokémon GO", sección "Region-exclusive Pokémon"; CC BY-NC-SA 2.5). Se lee el texto de la página
 * con su API: cada fila trae la región y los Pokémon como plantillas {{MSP/GO|0128PA|Tauros}}.
 */
object Regionals {
    const val URL = "https://bulbapedia.bulbagarden.net/w/api.php?action=parse&page=List_of_Pok%C3%A9mon_by_availability_in_Pok%C3%A9mon_GO&prop=wikitext&section=6&format=json"
    const val PAGE = "https://bulbapedia.bulbagarden.net/wiki/List_of_Pok%C3%A9mon_by_availability_in_Pok%C3%A9mon_GO"

    private val msp = Regex("""\{\{MSP/GO\|(\d{3,4})([A-Za-z]*)\|([^|}]+)(?:\|([^}]+))?\}\}""")
    private val tooltip = Regex("""\{\{tt\|\*\|([^}]+)\}\}""")
    private val cellStyle = Regex("""^\|\s*style="[^"]*"\s*\|""")

    /** El JSON de la API de MediaWiki → el texto de la sección. */
    fun wikitextOf(apiJson: String): String =
        GameDataSources.json.parseToJsonElement(apiJson).let { root ->
            val parse = (root as kotlinx.serialization.json.JsonObject)["parse"] as kotlinx.serialization.json.JsonObject
            val wt = parse["wikitext"] as kotlinx.serialization.json.JsonObject
            (wt["*"] as kotlinx.serialization.json.JsonPrimitive).content
        }

    fun parse(wikitext: String): List<RegionalGroup> {
        val out = mutableListOf<RegionalGroup>()
        var biome = false
        var region: String? = null
        var note: String? = null
        for (raw in wikitext.lines()) {
            val line = raw.trim()
            when {
                line.startsWith("====") && line.contains("Biome", ignoreCase = true) -> { biome = true; region = null }
                line.startsWith("|-") || line.startsWith("{|") || line.startsWith("|}") -> region = null
                line.startsWith("! ") && line.contains("{{MSP/GO") && region != null -> {
                    val members = msp.findAll(line).map { m ->
                        RegionalEntry(m.groupValues[1].toInt(), m.groupValues[2], m.groupValues[3].trim(), m.groupValues[4].trim().ifEmpty { null })
                    }.toList()
                    if (members.isNotEmpty()) out += RegionalGroup(region, note, members, biome)
                    region = null
                }
                line.startsWith("|") && !line.startsWith("| colspan") && !line.startsWith("||") -> {
                    // "| Western Hemisphere" o "| style="…" | United Kingdom"; la aclaración va en {{tt|*|…}}.
                    val cell = line.replaceFirst(cellStyle, "").removePrefix("|").trim()
                    if (cell.isEmpty() || cell.startsWith("*")) continue
                    note = tooltip.find(cell)?.groupValues?.get(1)?.trim()
                    region = cell.replace(tooltip, "").trim()
                }
            }
        }
        return out
    }

    /**
     * Nombre de la forma (en inglés, como el cliente del juego), por el código de forma de Bulbapedia cuando la fila
     * no lo dice. null = la forma común.
     */
    fun formLabel(e: RegionalEntry): String? = e.form?.removeSuffix(" Form") ?: when (e.dex) {
        128 -> mapOf("PA" to "Aqua Breed", "PB" to "Blaze Breed", "PC" to "Combat Breed")[e.code]
        422, 423 -> if (e.code == "E") "East Sea" else "West Sea"
        550 -> if (e.code == "B") "Blue-Striped" else "Red-Striped"
        669, 670 -> mapOf("" to "Red Flower", "Y" to "Yellow Flower", "B" to "Blue Flower")[e.code]
        676 -> mapOf("St" to "Star Trim", "Di" to "Diamond Trim", "De" to "Debutante Trim", "La" to "La Reine Trim",
            "Ka" to "Kabuki Trim", "Ph" to "Pharaoh Trim")[e.code]
        931 -> if (e.code == "B") "Blue Plumage" else "Green Plumage"
        978 -> mapOf("" to "Curly", "D" to "Droopy", "S" to "Stretchy")[e.code]
        else -> null
    }

    /** Las regiones de la fuente, en español (las que no están acá se muestran como vienen). */
    val SPANISH = mapOf(
        "East Asia" to "Este de Asia",
        "Australia and Papua New Guinea" to "Australia y Papúa Nueva Guinea",
        "Europe" to "Europa",
        "United States, southern Canada, and parts of northern Mexico" to "Estados Unidos, sur de Canadá y parte del norte de México",
        "Mexico, Central and South America" to "México, Centroamérica y Sudamérica",
        "West, Central, and South Asia" to "Asia occidental, central y del sur",
        "Africa and Middle East" to "África y Medio Oriente",
        "Certain islands east of Australia" to "Algunas islas al este de Australia",
        "Alaska, Canada, and Asian Russia" to "Alaska, Canadá y Rusia asiática",
        "Europe and Asia-Pacific" to "Europa y Asia-Pacífico",
        "Americas, Africa, and Middle East" to "América, África y Medio Oriente",
        "Western Hemisphere" to "Hemisferio occidental",
        "Eastern Hemisphere" to "Hemisferio oriental",
        "Asia-Pacific" to "Asia-Pacífico",
        "Europe, Middle East, and Africa" to "Europa, Medio Oriente y África",
        "Americas and Greenland" to "América y Groenlandia",
        "Northern Hemisphere" to "Hemisferio norte",
        "Southern Hemisphere" to "Hemisferio sur",
        "Iberian Peninsula" to "Península ibérica",
        "Tropics" to "Trópicos",
        "Southeastern United States and the northern Bahamas" to "Sudeste de Estados Unidos y norte de Bahamas",
        "Southern United States, Mexico, Central America, the Caribbean, and South America" to
            "Sur de Estados Unidos, México, Centroamérica, el Caribe y Sudamérica",
        "Egypt, Greece, and Israel" to "Egipto, Grecia e Israel",
        "Northeastern United States" to "Noreste de Estados Unidos",
        "France" to "Francia",
        "Japan" to "Japón",
        "Egypt" to "Egipto",
        "Mexico" to "México",
        "African, Asian, Pacific and Caribbean islands, and Australia" to "Islas de África, Asia, el Pacífico y el Caribe, y Australia",
        "Hawaii" to "Hawái",
        "United Kingdom" to "Reino Unido",
        "Beach" to "Playas",
        "Forested & Grassy" to "Bosques y pastizales",
        "Desert" to "Desiertos",
    )

    val NOTES_SPANISH = mapOf(
        "Between 29° and 52° N" to "Entre 29° y 52° N",
        "South of 29° N" to "Al sur de 29° N",
        "including New Zealand, New Caledonia, Vanuatu, and others to the east" to "Nueva Zelanda, Nueva Caledonia, Vanuatu y otras más al este",
        "North of 52° N" to "Al norte de 52° N",
        "within ~50 kilometers of coasts between 31° N and 26° S, except in the Atlantic Ocean where the southern reach is 5° S" to
            "A unos 50 km de la costa, entre 31° N y 26° S (en el Atlántico, hasta 5° S)",
    )
}
