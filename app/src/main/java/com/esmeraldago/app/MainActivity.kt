package com.esmeraldago.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.esmeraldago.app.ui.advice.AdviceScreen
import com.esmeraldago.app.ui.collection.CollectionScreen
import com.esmeraldago.app.ui.collection.EditPokemonScreen
import com.esmeraldago.app.ui.collection.OwnedDetailScreen
import com.esmeraldago.app.ui.common.ProvideContainer
import com.esmeraldago.app.ui.home.HomeScreen
import com.esmeraldago.app.ui.max.MaxBattleScreen
import com.esmeraldago.app.ui.raids.CountersScreen
import com.esmeraldago.app.ui.raids.RaidsScreen
import com.esmeraldago.app.ui.rankings.RankingsScreen
import com.esmeraldago.app.ui.rankings.SpeciesDetailScreen
import com.esmeraldago.app.ui.scan.ScanScreen
import com.esmeraldago.app.ui.settings.SettingsScreen
import com.esmeraldago.app.ui.teams.TeamsScreen
import com.esmeraldago.app.ui.theme.EmeraldBackground
import com.esmeraldago.app.ui.theme.EmeraldTheme

class MainActivity : ComponentActivity() {
    private val container get() = (application as EsmeraldaApp).container

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Solo la primera vez: si la Activity se recrea (cambio de tema/idioma o el sistema la restauró)
        // no hay que volver a escanear las mismas capturas.
        if (savedInstanceState == null) {
            handleShare(intent)
            handleRoute(intent)
        }
        // La pantalla de carga solo al abrir la app (no al volver de otra o si la Activity se recrea).
        val coldStart = savedInstanceState == null
        setContent {
            EmeraldTheme {
                ProvideContainer(container) {
                    var splash by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(coldStart) }
                    val game by container.game.state.collectAsState()
                    if (splash) {
                        com.esmeraldago.app.ui.home.SplashScreen(
                            ready = game !is com.esmeraldago.app.data.GameState.Loading,
                            status = "Cargando datos del juego…",
                            onDone = { splash = false },
                        )
                    } else EmeraldBackground(Modifier.systemBarsPadding()) {
                        Column(Modifier.fillMaxSize()) { AppNav() }
                        NoticeHost(Modifier.align(androidx.compose.ui.Alignment.BottomCenter))
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Al volver de dar el permiso de "instalar apps desconocidas", la actualización ya bajada se instala sola.
        container.updates.resumePendingInstall()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShare(intent)
        handleRoute(intent)
    }

    /** Pantalla pedida desde afuera (ej. "REVISAR" en la burbuja de escaneo en vivo). */
    private fun handleRoute(intent: Intent?) {
        intent?.getStringExtra(EXTRA_ROUTE)?.let {
            container.pendingNavigation.value = it
            intent.removeExtra(EXTRA_ROUTE)
        }
    }

    companion object {
        const val EXTRA_ROUTE = "com.esmeraldago.ROUTE"
    }

    /** Capturas compartidas desde la galería -> cola de escaneo. */
    private fun handleShare(intent: Intent?) {
        intent ?: return
        val uris: List<Uri> = when (intent.action) {
            Intent.ACTION_SEND -> listOfNotNull(intent.parcelable(Intent.EXTRA_STREAM))
            Intent.ACTION_SEND_MULTIPLE -> intent.parcelableList(Intent.EXTRA_STREAM)
            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            container.scan.enqueue(uris)
            container.pendingNavigation.value = "scan"
            // Consumido: que no se reprocese.
            intent.action = Intent.ACTION_MAIN
            intent.removeExtra(Intent.EXTRA_STREAM)
        }
    }

    @Suppress("DEPRECATION")
    private fun Intent.parcelable(key: String): Uri? =
        if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, Uri::class.java) else getParcelableExtra(key)

    @Suppress("DEPRECATION")
    private fun Intent.parcelableList(key: String): List<Uri> =
        (if (Build.VERSION.SDK_INT >= 33) getParcelableArrayListExtra(key, Uri::class.java) else getParcelableArrayListExtra(key))
            .orEmpty()
}

/** Aviso breve abajo (guardado, borrado con DESHACER…), estilo cuadro de diálogo de Esmeralda. */
@androidx.compose.runtime.Composable
private fun NoticeHost(modifier: Modifier) {
    val container = com.esmeraldago.app.ui.common.LocalContainer.current
    val notice by container.notice.collectAsState()
    val n = notice ?: return
    LaunchedEffect(n.id) {
        kotlinx.coroutines.delay(if (n.action != null) 6000 else 3500)
        if (container.notice.value?.id == n.id) container.dismissNotice()
    }
    androidx.compose.foundation.layout.Row(
        modifier.padding(10.dp),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
    ) {
        com.esmeraldago.app.ui.theme.DialogBox(n.text, Modifier.weight(1f))
        if (n.actionLabel != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
            com.esmeraldago.app.ui.theme.PixelButton(n.actionLabel, { n.action?.invoke(); container.dismissNotice() },
                color = com.esmeraldago.app.ui.theme.Pal.Blue)
        }
    }
}

@androidx.compose.runtime.Composable
private fun AppNav() {
    val nav = rememberNavController()
    val container = com.esmeraldago.app.ui.common.LocalContainer.current
    val pending by container.pendingNavigation.collectAsState()
    LaunchedEffect(pending) {
        val route = pending ?: return@LaunchedEffect
        container.pendingNavigation.value = null
        if (nav.currentDestination?.route != route) nav.navigate(route) { launchSingleTop = true }
    }
    val back: () -> Unit = { nav.popBackStack() }

    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(onOpen = { nav.navigate(it) }) }
        composable("pokedex") { com.esmeraldago.app.ui.pokedex.PokedexScreen(back, onSpecies = { nav.navigate("species/$it") }) }
        composable("rankings?focus={focus}", listOf(navArgument("focus") { type = NavType.StringType; nullable = true; defaultValue = null })) {
            RankingsScreen(back, onSpecies = { id -> nav.navigate("species/$id") }, focus = it.arguments?.getString("focus"))
        }
        composable("species/{id}", listOf(navArgument("id") { type = NavType.StringType })) {
            // De una entrada a otra se reemplaza: "atrás" vuelve a la pantalla de donde se entró a la Pokédex, no
            // recorre todas las entradas visitadas.
            SpeciesDetailScreen(it.arguments!!.getString("id")!!, back, onSpecies = { id ->
                nav.navigate("species/$id") { popUpTo(it.destination.id) { inclusive = true } }
            }, onRanking = { focus -> nav.navigate("rankings?focus=$focus") })
        }
        composable("raids") {
            RaidsScreen(back, onBoss = { id, tier -> nav.navigate("counters/$id/$tier") })
        }
        composable(
            "counters/{id}/{tier}",
            listOf(navArgument("id") { type = NavType.StringType }, navArgument("tier") { type = NavType.StringType }),
        ) {
            CountersScreen(it.arguments!!.getString("id")!!, it.arguments!!.getString("tier")!!, back,
                onSpecies = { id -> nav.navigate("species/$id") }, onScan = { nav.navigate("scan") })
        }
        composable("max") { MaxBattleScreen(back, onSpecies = { nav.navigate("species/$it") }) }
        composable("collection") {
            CollectionScreen(back, onOpen = { nav.navigate("owned/$it") }, onAdd = { nav.navigate("edit/new") },
                onScan = { nav.navigate("scan") })
        }
        composable("owned/{uid}", listOf(navArgument("uid") { type = NavType.StringType })) {
            val uid = it.arguments!!.getString("uid")!!
            OwnedDetailScreen(uid, back, onEdit = { nav.navigate("edit/$uid") })
        }
        composable("edit/{uid}", listOf(navArgument("uid") { type = NavType.StringType })) {
            EditPokemonScreen(it.arguments!!.getString("uid")!!.takeIf { u -> u != "new" }, back)
        }
        composable("scan") { ScanScreen(back) }
        composable("advice") {
            AdviceScreen(back, onOwned = { nav.navigate("owned/$it") }, onSpecies = { nav.navigate("species/$it") },
                onScan = { nav.navigate("scan") })
        }
        composable("teams") {
            TeamsScreen(back, onOwned = { nav.navigate("owned/$it") }, onAdvice = { nav.navigate("advice") },
                onScan = { nav.navigate("scan") })
        }
        composable("settings") { SettingsScreen(back) }
        composable("guide") { com.esmeraldago.app.ui.guide.GuideScreen(back) }
        composable("types") { com.esmeraldago.app.ui.types.TypesScreen(back) }
    }
}
