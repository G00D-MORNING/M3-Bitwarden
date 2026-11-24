package com.framepersecond.m3bitwarden.ui.screens

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.framepersecond.m3bitwarden.AppState
import com.framepersecond.m3bitwarden.GeneratorMode
import com.framepersecond.m3bitwarden.GeneratorState
import com.framepersecond.m3bitwarden.generator.PasswordGenerator
import com.framepersecond.m3bitwarden.generator.PasswordOptions
import com.framepersecond.m3bitwarden.ui.LocalCopy
import com.framepersecond.m3bitwarden.ui.components.ConnectedGroup
import com.framepersecond.m3bitwarden.ui.components.GroupOption
import com.framepersecond.m3bitwarden.ui.components.ListRow
import com.framepersecond.m3bitwarden.ui.components.SectionTitle
import com.framepersecond.m3bitwarden.ui.components.SwitchRow
import com.framepersecond.m3bitwarden.ui.components.WavyStrength
import com.framepersecond.m3bitwarden.ui.components.colorizedPassword
import com.framepersecond.m3bitwarden.ui.components.segmentShape
import com.framepersecond.m3bitwarden.ui.icons.Symbols
import kotlin.math.roundToInt

@Composable
fun GeneratorScreen(state: AppState, contentPadding: PaddingValues) {
    val copy = LocalCopy.current
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(top = contentPadding.calculateTopPadding(), bottom = contentPadding.calculateBottomPadding() + 120.dp)
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Generator", style = MaterialTheme.typography.displaySmallEmphasized, modifier = Modifier.padding(start = 4.dp, top = 12.dp))
        GeneratorPanel(state.generator, showHistory = true)
        if (state.generator.history.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SectionTitle("Zuletzt verwendet")
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    state.generator.history.forEachIndexed { i, value ->
                        ListRow(value, segmentShape(i, state.generator.history.size), icon = Symbols.History, onClick = { copy("Passwort", value, true) }) {
                            Icon(Symbols.ContentCopy, "Kopieren", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** Value card, mode switch and options. Shared with the editor's sheet. */
@Composable
fun GeneratorPanel(gen: GeneratorState, showHistory: Boolean) {
    val copy = LocalCopy.current
    LaunchedEffect(gen.mode, gen.passwordOptions, gen.passphraseOptions) { gen.regenerate() }

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(32.dp)).background(MaterialTheme.colorScheme.surface).padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            AnimatedContent(gen.value, transitionSpec = { (fadeIn() + scaleIn(initialScale = 0.92f)) togetherWith fadeOut() }, label = "value") { value ->
                Text(
                    colorizedPassword(value),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 26.sp,
                    lineHeight = 36.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.fillMaxWidth().defaultMinSize(minHeight = 72.dp),
                )
            }
            WavyStrength(gen.entropyBits, thick = true)
            ConnectedGroup(
                listOf(
                    GroupOption("Neu generieren", Symbols.Autorenew) { gen.regenerate() },
                    GroupOption("Kopieren", Symbols.ContentCopy) {
                        gen.record(gen.value)
                        copy(if (gen.mode == GeneratorMode.Password) "Passwort" else "Passphrase", gen.value, true)
                    },
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }

        ConnectedGroup(
            listOf(
                GroupOption("Passwort", Symbols.Password, gen.mode == GeneratorMode.Password) { gen.mode = GeneratorMode.Password },
                GroupOption("Passphrase", Symbols.Notes, gen.mode == GeneratorMode.Passphrase) { gen.mode = GeneratorMode.Passphrase },
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        val isPassword = gen.mode == GeneratorMode.Password
        val (label, value, min, max) = if (isPassword) {
            listOf("Länge", gen.passwordOptions.length, PasswordGenerator.MIN_LENGTH, PasswordGenerator.MAX_LENGTH)
        } else {
            listOf("Wörter", gen.passphraseOptions.words, PasswordGenerator.MIN_WORDS, PasswordGenerator.MAX_WORDS)
        }
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(label as String, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                Text("$value", style = MaterialTheme.typography.headlineSmallEmphasized, color = MaterialTheme.colorScheme.primary)
            }
            Slider(
                value = (value as Int).toFloat(),
                onValueChange = { v ->
                    val n = v.roundToInt()
                    if (isPassword) gen.passwordOptions = gen.passwordOptions.copy(length = n) else gen.passphraseOptions = gen.passphraseOptions.copy(words = n)
                },
                valueRange = (min as Int).toFloat()..(max as Int).toFloat(),
                steps = 0,
            )
        }

        if (!isPassword) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(horizontal = 20.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Trennzeichen", style = MaterialTheme.typography.bodyLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("-" to "Bindestrich", "." to "Punkt", "_" to "Unterstrich", " " to "Leerzeichen").forEach { (sep, name) ->
                        FilterChip(
                            selected = gen.passphraseOptions.separator == sep,
                            onClick = { gen.passphraseOptions = gen.passphraseOptions.copy(separator = sep) },
                            label = { Text(name) },
                            leadingIcon = if (gen.passphraseOptions.separator == sep) ({ Icon(Symbols.Check, null) }) else null,
                        )
                    }
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (isPassword) {
                val o = gen.passwordOptions
                val rows = listOf(
                    Triple("Großbuchstaben", "A–Z", o.uppercase) to { v: Boolean -> o.copy(uppercase = v) },
                    Triple("Kleinbuchstaben", "a–z", o.lowercase) to { v: Boolean -> o.copy(lowercase = v) },
                    Triple("Ziffern", "0–9", o.digits) to { v: Boolean -> o.copy(digits = v) },
                    Triple("Sonderzeichen", "!@#\$%^&*", o.symbols) to { v: Boolean -> o.copy(symbols = v) },
                    Triple("Verwechselbare vermeiden", "Ohne I, l, O, 0, 1", o.avoidAmbiguous) to { v: Boolean -> o.copy(avoidAmbiguous = v) },
                )
                rows.forEachIndexed { i, (info, update) ->
                    SwitchRow(info.first, info.third, segmentShape(i, rows.size), supporting = info.second) { gen.passwordOptions = update(it).ensureOne() }
                }
            } else {
                val o = gen.passphraseOptions
                SwitchRow("Großschreiben", o.capitalize, segmentShape(0, 2), supporting = "Erster Buchstabe jedes Worts") { gen.passphraseOptions = o.copy(capitalize = it) }
                SwitchRow("Zahl einfügen", o.includeNumber, segmentShape(1, 2), supporting = "An ein zufälliges Wort") { gen.passphraseOptions = o.copy(includeNumber = it) }
            }
        }
    }
}

private fun PasswordOptions.ensureOne() = if (!uppercase && !lowercase && !digits && !symbols) copy(lowercase = true) else this
