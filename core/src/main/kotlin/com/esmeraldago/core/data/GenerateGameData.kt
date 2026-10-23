package com.esmeraldago.core.data

import java.io.File

/** Uso: generateGameData <salida.json> [dirCache] */
fun main(args: Array<String>) {
    val out = File(args[0])
    val cache = File(args.getOrElse(1) { "data-cache" })
    val data = GameDataSources.downloadAndBuild(cache, maxAgeMs = 12 * 3600_000L) { println(it) }
    out.parentFile.mkdirs()
    out.writeText(GameDataSources.encode(data))
    println(
        "OK: ${data.species.size} especies, ${data.moves.size} movimientos, " +
            "${data.species.count { it.dynamax }} Dynamax (${data.species.count { it.gigantamax }} Gigamax), " +
            "rankings PvP: ${data.pvp.mapValues { it.value.size }} -> ${out.absolutePath} (${out.length() / 1024} KB)"
    )
}
