@file:OptIn(ExperimentalMaterial3Api::class)

package de.uhi.enia.ridesafe.ui.screens.settings

import android.app.LocaleManager
import android.os.LocaleList
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import de.uhi.enia.ridesafe.R
import de.uhi.enia.ridesafe.util.currentAppLanguageTag
import kotlinx.coroutines.launch

@Composable
fun LanguageSettingsScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    showBack: Boolean = true,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val localeManager = context.getSystemService(LocaleManager::class.java)
    val currentLocales = localeManager.applicationLocales
    val currentLang =
        if (currentLocales.isEmpty) {
            "system"
        } else {
            currentLocales.get(0).language
        }

    val options =
        listOf(
            "system" to R.string.language_system,
            "en" to R.string.language_english,
            "de" to R.string.language_german,
        )

    SettingsSelectionScreen(
        modifier = modifier,
        title = stringResource(R.string.settings_language_title),
        description = stringResource(R.string.settings_language_detail_description),
        onBack = onBack,
        showBack = showBack,
    ) {
        options.forEachIndexed { index, (tag, labelRes) ->
            SelectableSettingRow(
                index = index,
                count = options.size,
                title = stringResource(labelRes),
                selected = tag == currentLang,
                onClick = {
                    val locales =
                        if (tag == "system") {
                            LocaleList.getEmptyLocaleList()
                        } else {
                            LocaleList.forLanguageTags(tag)
                        }
                    scope.launch {
                        SettingsFade.applyAcrossRestart { localeManager.applicationLocales = locales }
                    }
                },
            )
        }
    }
}

@Composable
internal fun currentLanguageLabel(): String =
    when (currentAppLanguageTag(LocalContext.current)) {
        "en" -> stringResource(R.string.language_english)
        "de" -> stringResource(R.string.language_german)
        else -> stringResource(R.string.language_system)
    }
