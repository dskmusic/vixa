package com.dskmusic.vixa

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.lifecycleScope
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.dskmusic.vixa.data.ErrorLog
import com.dskmusic.vixa.media.Importer
import com.dskmusic.vixa.ui.HomeScreen
import com.dskmusic.vixa.ui.LocalSettings
import com.dskmusic.vixa.ui.SettingsScreen
import com.dskmusic.vixa.ui.TutorialScreen
import com.dskmusic.vixa.ui.VixaTheme
import com.dskmusic.vixa.ui.editor.EditorScreen
import com.dskmusic.vixa.ui.isDark
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    /** Rutas locales de los medios recibidos con "Compartir con / Abrir con". */
    private val incoming = MutableStateFlow<List<String>>(emptyList())

    /** La app se minimiza, se cambia de app o se bloquea la pantalla: se detiene cualquier reproducción en curso. */
    override fun onPause() {
        com.dskmusic.vixa.engine.PlaybackGuard.stopAll()
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as VixaApp).container
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by container.settings.flow.collectAsState()
            val dark = isDark(settings, isSystemInDarkTheme())
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                    navigationBarStyle = if (dark) SystemBarStyle.dark(Color.TRANSPARENT) else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
                )
            }
            CompositionLocalProvider(LocalSettings provides settings) {
                VixaTheme(settings) { Root(container) }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        val uris = ArrayList<Uri>()
        try {
            when (intent.action) {
                Intent.ACTION_VIEW -> intent.data?.let { uris.add(it) }
                Intent.ACTION_SEND -> {
                    @Suppress("DEPRECATION")
                    (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { uris.add(it) }
                }
                Intent.ACTION_SEND_MULTIPLE -> {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)?.let { uris.addAll(it) }
                }
            }
        } catch (e: Throwable) {
            ErrorLog.log("Intent", e)
        }
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            val paths = uris.mapNotNull { Importer.copyUri(applicationContext, it) }
            if (paths.isNotEmpty()) incoming.value = paths
        }
    }

    private fun hasStorageAccess(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    @Composable
    private fun Root(container: AppContainer) {
        var storageOk by remember { mutableStateOf(hasStorageAccess()) }
        var skipped by remember { mutableStateOf(false) }
        val owner = LocalLifecycleOwner.current
        DisposableEffect(owner) {
            val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) storageOk = hasStorageAccess() }
            owner.lifecycle.addObserver(obs)
            onDispose { owner.lifecycle.removeObserver(obs) }
        }
        val legacy = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { storageOk = hasStorageAccess() }
        val notif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
        LaunchedEffect(Unit) {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notif.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        LaunchedEffect(storageOk) { if (storageOk) com.dskmusic.vixa.data.Storage.ensure() }
        if (!storageOk && !skipped) {
            Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.perm_storage_title), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    Text(stringResource(R.string.perm_storage_body), Modifier.padding(vertical = 16.dp).widthIn(max = 460.dp), textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button({
                        if (Build.VERSION.SDK_INT >= 30) {
                            try {
                                startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                            } catch (e: Throwable) {
                                try { startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) } catch (e2: Throwable) { ErrorLog.log("Permission", e2) }
                            }
                        } else legacy.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
                    }) { Text(stringResource(R.string.perm_grant)) }
                    TextButton({ skipped = true }) { Text(stringResource(R.string.perm_skip)) }
                }
            }
            return
        }

        val nav = rememberNavController()
        val settings = container.settings.value
        // Primera ejecución: tutorial. Si el sistema mató la app con un proyecto abierto, se restaura.
        val start = remember { if (!settings.tutorialSeen) "tutorial" else "home" }
        // El proyecto demo ya no se crea en el primer arranque: solo a demanda, desde el menú de ayuda (ver HomeScreen.startDemo).
        val pending by incoming.collectAsState()
        LaunchedEffect(Unit) {
            val open = container.settings.value.openProjectId
            if (start == "home" && open.isNotEmpty() && pending.isEmpty() && container.projects.load(open) != null) nav.navigate("editor/$open")
        }
        LaunchedEffect(pending) {
            if (pending.isNotEmpty() && nav.currentDestination?.route != "home") nav.popBackStack("home", inclusive = false)
        }
        NavHost(nav, startDestination = start) {
            composable("home") {
                HomeScreen(container, pending, onIncomingConsumed = { incoming.value = emptyList() }, onOpen = { id -> nav.navigate("editor/$id") { launchSingleTop = true } }, onSettings = { nav.navigate("settings") }, onTutorial = { nav.navigate("tutorial") }, onHelp = { nav.navigate("help") })
            }
            composable("editor/{id}", arguments = listOf(navArgument("id") { type = NavType.StringType })) { entry ->
                val id = entry.arguments?.getString("id") ?: ""
                EditorScreen(container, id, onExit = { if (!nav.popBackStack()) nav.navigate("home") }, onOpenProject = { other -> nav.navigate("editor/$other") { popUpTo("home") } })
            }
            composable("settings") { SettingsScreen(container, onBack = { nav.popBackStack() }, onTutorial = { nav.navigate("tutorial") }, onHelp = { nav.navigate("help") }) }
            composable("help") { com.dskmusic.vixa.ui.HelpScreen(onBack = { nav.popBackStack() }) }
            composable("tutorial") {
                TutorialScreen(onDone = {
                    container.settings.update { it.copy(tutorialSeen = true) }
                    if (!nav.popBackStack()) nav.navigate("home") { popUpTo("tutorial") { inclusive = true } }
                })
            }
        }
    }
}
