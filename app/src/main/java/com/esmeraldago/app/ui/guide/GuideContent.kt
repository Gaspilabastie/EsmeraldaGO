package com.esmeraldago.app.ui.guide

/**
 * Entradas de la Guía. Solo datos establecidos del juego o de las fuentes que usa la app (PvPoke, Pokébattler,
 * Game Master); nada estimado. Texto breve, formal e impersonal.
 */
enum class GuideCategory(val label: String) {
    MECHANICS("Mecánicas"), ITEMS("Objetos"), BATTLE("Combate"), STRATEGY("Estrategia"), APP("La app"),
}

class GuideEntry(val category: GuideCategory, val title: String, val body: String)

val GUIDE: List<GuideEntry> = listOf(
    // ------------------------------------------------------------ Mecánicas
    GuideEntry(GuideCategory.MECHANICS, "IV (valores individuales)",
        "Cada Pokémon tiene tres IV de 0 a 15: Ataque, Defensa y PS. Se suman a las estadísticas base de la especie. " +
            "La valoración del juego los muestra como barras; 15/15/15 es el máximo."),
    GuideEntry(GuideCategory.MECHANICS, "PC y nivel",
        "Los PC resumen estadísticas, IV y nivel. El nivel sube de a medio punto con Polvo Estelar y caramelos, hasta 50; " +
            "del 40 al 50 también se necesitan Caramelos XL. Un compañero Mejor Amigo suma un nivel mientras acompaña."),
    GuideEntry(GuideCategory.MECHANICS, "IV mínimos según el origen",
        "Incursiones, huevos e investigaciones: IV de al menos 10/10/10. Intercambio con suerte: al menos 12/12/12. " +
            "Salvajes con clima potenciado: al menos 4/4/4. Los Pokémon de incursión salen a nivel 20, o 25 con clima potenciado; " +
            "los de huevo, a nivel 20."),
    GuideEntry(GuideCategory.MECHANICS, "Bonificación por mismo tipo (STAB)",
        "Un ataque del mismo tipo que el Pokémon que lo usa hace ×1,2 de daño. Se acumula con la eficacia de tipos y el clima."),
    GuideEntry(GuideCategory.MECHANICS, "Eficacia de tipos",
        "En Pokémon GO: supereficaz ×1,6; poco eficaz ×0,625; doble resistencia (las inmunidades de la saga) ×0,39. " +
            "Contra dos tipos, los multiplicadores se combinan: doble debilidad es ×2,56."),
    GuideEntry(GuideCategory.MECHANICS, "Clima",
        "Cada clima potencia algunos tipos: sus ataques hacen ×1,2 de daño y sus Pokémon salvajes aparecen con más nivel " +
            "(hasta 35) e IV más altos."),
    GuideEntry(GuideCategory.MECHANICS, "Oscuros",
        "Un Pokémon oscuro hace ×1,2 de daño y recibe ×1,2. Cuesta más Polvo Estelar y caramelos subirlo. " +
            "Al purificarlo pierde esa bonificación, cada IV sube 2 (hasta 15) y cambia Frustración por Retribución."),
    GuideEntry(GuideCategory.MECHANICS, "Megaevolución",
        "Requiere Megaenergía de la especie; la primera vez cuesta más. Dura 8 horas. Durante una incursión potencia el daño " +
            "de todos los entrenadores, más aún con ataques de su mismo tipo, y al atrapar Pokémon de su tipo da caramelos extra."),
    GuideEntry(GuideCategory.MECHANICS, "Con suerte",
        "Los intercambios pueden dar un Pokémon con suerte: IV de al menos 12/12/12 y la mitad de Polvo Estelar para subirlo."),
    GuideEntry(GuideCategory.MECHANICS, "Amistad",
        "Combatir junto a amigos en incursiones suma daño según el nivel de amistad: buen amigo 3 %, gran amigo 5 %, " +
            "ultra amigo 7 %, mejor amigo 10 %. Los intercambios entre mejores amigos dan IV más altos."),
    GuideEntry(GuideCategory.MECHANICS, "Variocolor (shiny)",
        "Cambia solo el color: estadísticas, ataques y PC son los mismos que en la versión normal."),

    // ------------------------------------------------------------ Objetos
    GuideEntry(GuideCategory.ITEMS, "MT rápida y MT cargada",
        "Cambian al azar un ataque rápido o cargado por otro que la especie pueda aprender, excluyendo los de legado."),
    GuideEntry(GuideCategory.ITEMS, "MT Élite",
        "Permite elegir el ataque, incluidos los de legado o de eventos. La app marca esos ataques con LEGADO."),
    GuideEntry(GuideCategory.ITEMS, "Caramelo Raro y Caramelo Raro XL",
        "Se convierten en caramelos (o Caramelos XL) de cualquier especie. Conviene guardarlos para legendarios y especies difíciles."),
    GuideEntry(GuideCategory.ITEMS, "Bayas",
        "Frambu: captura ×1,5. Frambu Dorada: captura ×2,5. Latano: el Pokémon se mueve menos. " +
            "Pinia: el doble de caramelos al atraparlo. Pinia Plateada: captura ×1,8 y doble de caramelos."),
    GuideEntry(GuideCategory.ITEMS, "Huevo Suerte y Trozo Estrella",
        "Durante 30 minutos, el Huevo Suerte duplica la experiencia y el Trozo Estrella suma 50 % de Polvo Estelar."),
    GuideEntry(GuideCategory.ITEMS, "Incienso y Módulo Cebo",
        "El Incienso atrae Pokémon al entrenador; el Módulo Cebo, a una Poképarada durante 30 minutos."),
    GuideEntry(GuideCategory.ITEMS, "Pases de incursión",
        "El gratuito se obtiene una vez por día al girar un gimnasio. El remoto permite unirse a una incursión desde lejos."),
    GuideEntry(GuideCategory.ITEMS, "Partículas Max",
        "Se juntan en los Puntos Power y caminando. Sirven para entrar a combates Max y para subir los ataques Max."),

    // ------------------------------------------------------------ Combate
    GuideEntry(GuideCategory.BATTLE, "Incursiones",
        "Jefes de nivel 1, 3, 5, Mega y Oscuros, en gimnasios. Hasta 20 entrenadores con 6 Pokémon cada uno. " +
            "Al ganar se puede atrapar al jefe."),
    GuideEntry(GuideCategory.BATTLE, "Combates Max",
        "En los Puntos Power, hasta 4 entrenadores con 3 Pokémon Dinamax cada uno. Atacar llena el medidor Max; lleno, " +
            "el Pokémon Dinamaxiza durante tres turnos y elige entre Max Attack (daño), Max Guard (escudo) y Max Spirit (curación). " +
            "Los Gigamax usan su propio ataque G-Max."),
    GuideEntry(GuideCategory.BATTLE, "Zacian y Zamazenta Coronados",
        "Entran a los combates Max sin poder Dinamaxizar, con Behemoth Blade o Behemoth Bash como ataque Max. " +
            "Para cambiar a la forma Coronada necesitan Iron Head y 1000 de su energía Coronada; las formas Héroe no entran."),
    GuideEntry(GuideCategory.BATTLE, "Ligas de Combates GO",
        "Liga Super: hasta 1500 PC. Liga Ultra: hasta 2500 PC. Liga Master: sin límite. Cada entrenador tiene 2 escudos " +
            "por combate. Los ataques rápidos cargan energía y los cargados la gastan."),
    GuideEntry(GuideCategory.BATTLE, "Empates de ataques cargados",
        "Si los dos lanzan un ataque cargado a la vez, va primero el de mayor Ataque. Por eso, en PvP, " +
            "IV bajos de Ataque y altos de Defensa y PS dan más aguante dentro del límite de PC."),

    // ------------------------------------------------------------ Estrategia
    GuideEntry(GuideCategory.STRATEGY, "Qué potenciar primero",
        "Para incursiones, los mejores atacantes de cada tipo, no los de más PC: un equipo del tipo correcto rinde más " +
            "que uno general. RANKINGS › PvE muestra los mejores por tipo."),
    GuideEntry(GuideCategory.STRATEGY, "Armar un equipo de incursión",
        "Seis Pokémon con ataques supereficaces contra el jefe, ambos ataques del mismo tipo si es posible. " +
            "Una megaevolución del tipo correcto ayuda a todo el grupo."),
    GuideEntry(GuideCategory.STRATEGY, "Purificar o no",
        "Para incursiones, el oscuro suele rendir más que el purificado, aunque cueste más subirlo. " +
            "Purificar conviene para PvP en algunas especies o para completar la Pokédex."),
    GuideEntry(GuideCategory.STRATEGY, "Pokémon para PvP",
        "Lo que importa es el puntaje dentro del límite de PC de cada liga, no los IV perfectos. " +
            "RANKINGS › PvP usa el ranking de PvPoke."),

    // ------------------------------------------------------------ La app
    GuideEntry(GuideCategory.APP, "Tiers",
        "Comparan cada Pokémon con el #1 de la lista: S desde 90 % del #1, A desde 80 %, B desde 70 %, C desde 60 %. " +
            "Con el filtro \"en su clase\", el #1 es el mejor de esa clase."),
    GuideEntry(GuideCategory.APP, "En su clase",
        "Compara solo contra Pokémon sin ventajas que este no tenga. Uno común, solo contra comunes; un oscuro, también " +
            "contra oscuros; un mega, también contra megas y oscuros; un legendario, también contra legendarios. " +
            "Así los legendarios, megas y oscuros no tapan al resto."),
    GuideEntry(GuideCategory.APP, "ER (rango efectivo)",
        "Puntaje de atacante PvE: (DPS³ × TDO)^¼. Pondera más el daño por segundo que el daño total, " +
            "con el mismo criterio que GamePress y Pokémon GO Hub."),
    GuideEntry(GuideCategory.APP, "Entrenadores necesarios",
        "Dato de Pokébattler en los counters de incursión: cuántos entrenadores, cada uno con 6 copias de ese Pokémon, " +
            "hacen falta para ganar. Menos es mejor."),
)
