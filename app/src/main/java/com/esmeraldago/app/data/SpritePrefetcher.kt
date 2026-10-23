package com.esmeraldago.app.data

import android.content.Context
import android.net.ConnectivityManager
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ErrorResult
import coil.request.ImageRequest
import com.esmeraldago.app.ui.common.spriteUrls
import com.esmeraldago.core.GameIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** ¿Wi-Fi (o red sin tope)? Las descargas grandes de fondo solo se hacen así. */
fun Context.onUnmeteredNetwork(): Boolean {
    val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    return cm.activeNetwork != null && !cm.isActiveNetworkMetered
}

/**
 * Baja una vez, en segundo plano y con Wi-Fi, el sprite normal de cada especie al caché en disco: después
 * las listas los muestran al instante y sin conexión. Si salen especies nuevas, baja solo las que faltan.
 */
object SpritePrefetcher {
    private const val PREFS = "sprites"

    suspend fun run(context: Context, idx: GameIndex) = withContext(Dispatchers.IO) {
        if (!context.onUnmeteredNetwork()) return@withContext
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val done = prefs.getStringSet("done", emptySet())!!.toMutableSet()
        val todo = idx.byDex.keys.sorted().filter { it.toString() !in done }
        if (todo.isEmpty()) return@withContext
        val gate = Semaphore(6) // pocas a la vez: no satura la red ni a GitHub
        coroutineScope {
            todo.map { dex ->
                async {
                    gate.withPermit {
                        // Cada fuente de la cadena, hasta la primera que exista (la misma que usa Sprite()).
                        val ok = spriteUrls(dex).any { url ->
                            val r = ImageRequest.Builder(context).data(url)
                                .memoryCachePolicy(CachePolicy.DISABLED).diskCachePolicy(CachePolicy.ENABLED).build()
                            context.imageLoader.execute(r) !is ErrorResult
                        }
                        if (ok) synchronized(done) { done += dex.toString() }
                    }
                }
            }.awaitAll()
        }
        prefs.edit().putStringSet("done", done).apply()
    }
}
