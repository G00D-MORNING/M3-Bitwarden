package com.framepersecond.m3bitwarden.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** Copies (label, text, sensitive) and confirms it with a snackbar. */
val LocalCopy = staticCompositionLocalOf<(String, String, Boolean) -> Unit> { { _, _, _ -> } }
