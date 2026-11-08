package dev.pbou.stt.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import dev.pbou.stt.AppGraph
import dev.pbou.stt.R
import dev.pbou.stt.data.DownloadState
import dev.pbou.stt.ui.bench.BenchScreen
import dev.pbou.stt.ui.bench.BenchViewModel
import dev.pbou.stt.ui.dictate.DictateScreen
import dev.pbou.stt.ui.file.FileScreen
import dev.pbou.stt.ui.file.FileViewModel
import dev.pbou.stt.ui.dictate.DictateViewModel
import dev.pbou.stt.ui.models.ModelsScreen
import dev.pbou.stt.ui.models.ModelsViewModel
import dev.pbou.stt.ui.settings.LicensesScreen
import dev.pbou.stt.ui.settings.SettingsScreen
import dev.pbou.stt.ui.settings.SettingsViewModel

private enum class Tab(val route: String, @StringRes val label: Int, @DrawableRes val icon: Int, @DrawableRes val selectedIcon: Int) {
    DICTATE("dictate", R.string.nav_dictate, R.drawable.ic_mic, R.drawable.ic_mic_fill),
    MODELS("models", R.string.nav_models, R.drawable.ic_deployed_code, R.drawable.ic_deployed_code_fill),
    SETTINGS("settings", R.string.nav_settings, R.drawable.ic_settings, R.drawable.ic_settings_fill),
}

private const val LICENSES = "licenses"
private const val BENCH = "bench"
private const val FILE = "file"

@Composable
fun SttAppUi(graph: AppGraph) {
    val nav = rememberNavController()
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val downloads by graph.downloads.states.collectAsStateWithLifecycle(emptyMap())
    val downloading = downloads.values.any { it is DownloadState.Running || it is DownloadState.Waiting }

    fun go(tab: Tab) = nav.navigate(tab.route) {
        popUpTo(nav.graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }

    // A file shared to the app: show it being transcribed.
    LaunchedEffect(Unit) {
        for (request in graph.showFile) nav.navigate(FILE) { launchSingleTop = true }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0),
        bottomBar = {
            if (Tab.entries.any { it.route == route }) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
                    Tab.entries.forEach { tab ->
                        val selected = tab.route == route
                        NavigationBarItem(
                            selected = selected,
                            onClick = { go(tab) },
                            icon = {
                                BadgedBox(badge = { if (tab == Tab.MODELS && downloading) androidx.compose.material3.Badge() }) {
                                    Icon(painterResource(if (selected) tab.selectedIcon else tab.icon), contentDescription = null)
                                }
                            },
                            label = { Text(stringResource(tab.label)) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            startDestination = Tab.DICTATE.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
            enterTransition = { fadeIn(tween(180)) },
            exitTransition = { fadeOut(tween(180)) },
        ) {
            composable(Tab.DICTATE.route) {
                DictateScreen(viewModel { DictateViewModel(graph) }, onManageModels = { go(Tab.MODELS) }, onOpenFile = { nav.navigate(FILE) })
            }
            composable(Tab.MODELS.route) {
                ModelsScreen(viewModel { ModelsViewModel(graph) }, onBenchmark = { nav.navigate(BENCH) })
            }
            composable(Tab.SETTINGS.route) {
                SettingsScreen(viewModel { SettingsViewModel(graph) }, onLicenses = { nav.navigate(LICENSES) }, onBenchmark = { nav.navigate(BENCH) })
            }
            composable(FILE) {
                FileScreen(viewModel { FileViewModel(graph) }, onBack = { nav.popBackStack() }, onManageModels = { go(Tab.MODELS) })
            }
            composable(BENCH) {
                BenchScreen(viewModel { BenchViewModel(graph) }, onBack = { nav.popBackStack() })
            }
            composable(LICENSES) {
                LicensesScreen(onBack = { nav.popBackStack() })
            }
        }
    }
}
