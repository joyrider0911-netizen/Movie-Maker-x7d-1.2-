package com.moviemaker.x7d

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.moviemaker.x7d.export.ExportManager
import com.moviemaker.x7d.export.ExportState
import com.moviemaker.x7d.model.ClipAnimationType
import com.moviemaker.x7d.model.ClipEffect
import com.moviemaker.x7d.model.ClipTransitionSpec
import com.moviemaker.x7d.model.ColorGradePreset
import com.moviemaker.x7d.model.ProjectState
import com.moviemaker.x7d.model.TextOverlay
import com.moviemaker.x7d.model.VideoClip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Minimal starter UI: pick videos, toggle effects, export an MP4 to Movies/MovieMakerX7d.
 * The real engine (effects, export, models) lives in the gl/, export/ and model/ packages.
 */
class MainActivity : ComponentActivity() {

    private lateinit var exportManager: ExportManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportManager = ExportManager(applicationContext, lifecycleScope)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    EditorScreen(exportManager)
                }
            }
        }
    }
}

private fun readDurationUs(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val ms = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
        (ms ?: 5_000L) * 1_000L
    } catch (e: Exception) {
        5_000_000L
    } finally {
        retriever.release()
    }
}

@Composable
private fun EditorScreen(exportManager: ExportManager) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var project by remember { mutableStateOf(ProjectState()) }
    val exportState by exportManager.exportState.collectAsState()

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val durationUs = withContext(Dispatchers.IO) { readDurationUs(context, uri) }
                val start = project.mainVideoClips.lastOrNull()?.timelineEndUs ?: 0L
                val clip = VideoClip(
                    uri = uri,
                    displayName = "Clip ${project.mainVideoClips.size + 1}",
                    timelineStartUs = start,
                    trimEndUs = durationUs,
                    sourceDurationUs = durationUs
                )
                project = project.copy(mainVideoClips = project.mainVideoClips + clip)
            }
        }
    }

    fun updateClips(transform: (VideoClip) -> VideoClip) {
        project = project.copy(mainVideoClips = project.mainVideoClips.map(transform))
    }

    val first = project.mainVideoClips.firstOrNull()
    val isExporting = exportState is ExportState.Exporting

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Movie Maker X7d", style = MaterialTheme.typography.headlineSmall)

        Button(
            onClick = {
                picker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Add video") }

        if (project.mainVideoClips.isEmpty()) {
            Text("No clips yet. Tap \"Add video\" to start.")
        } else {
            project.mainVideoClips.forEach { clip ->
                Text("${clip.displayName} (${clip.durationUs / 1_000_000L}s)")
            }
        }

        Text("Effects (applied to all clips)", style = MaterialTheme.typography.titleMedium)

        EffectRow("Film grain", first?.filmGrain?.enabled ?: false) { on ->
            updateClips { it.copy(filmGrain = ClipEffect.FilmGrain(enabled = on)) }
        }
        EffectRow("Choppy (12 fps)", first?.choppyCadence?.enabled ?: false) { on ->
            updateClips { it.copy(choppyCadence = ClipEffect.ChoppyCadence(enabled = on)) }
        }
        EffectRow("Glitch", first?.glitch?.enabled ?: false) { on ->
            updateClips { it.copy(glitch = ClipEffect.Glitch(enabled = on)) }
        }
        EffectRow("Blur", first?.blur?.enabled ?: false) { on ->
            updateClips { it.copy(blur = ClipEffect.GaussianBlur(enabled = on)) }
        }
        EffectRow("Vintage sepia", first?.colorAdjustment?.enabled ?: false) { on ->
            updateClips {
                it.copy(
                    colorAdjustment = ClipEffect.ColorAdjustment(
                        enabled = on,
                        preset = ColorGradePreset.VINTAGE_SEPIA
                    )
                )
            }
        }
        EffectRow("Fade in / out", first?.introAnimation?.type == ClipAnimationType.FADE_BLACK) { on ->
            val spec = if (on) ClipTransitionSpec(ClipAnimationType.FADE_BLACK) else ClipTransitionSpec()
            updateClips { it.copy(introAnimation = spec, outroAnimation = spec) }
        }
        EffectRow("Title text", project.textOverlays.isNotEmpty()) { on ->
            project = project.copy(
                textOverlays = if (on) listOf(TextOverlay(text = "My Movie")) else emptyList()
            )
        }

        Button(
            onClick = { exportManager.startExport(project) },
            enabled = project.mainVideoClips.isNotEmpty() && !isExporting,
            modifier = Modifier.fillMaxWidth()
        ) { Text("Export MP4") }

        when (val state = exportState) {
            is ExportState.Exporting -> {
                LinearProgressIndicator(
                    progress = { state.progress },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Exporting… ${(state.progress * 100).toInt()}%")
                Button(onClick = { exportManager.cancelExport() }) { Text("Cancel") }
            }
            is ExportState.Completed ->
                Text("Saved to Movies/MovieMakerX7d: ${state.fileName}")
            is ExportState.Failed ->
                Text("Export failed: ${state.message}")
            else -> {}
        }
    }
}

@Composable
private fun EffectRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
