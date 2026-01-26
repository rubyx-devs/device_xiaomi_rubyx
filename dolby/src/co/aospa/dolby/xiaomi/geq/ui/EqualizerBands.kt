/*
 * Copyright (C) 2025 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi.geq.ui

import androidx.compose.material3.CardDefaults
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.android.settingslib.spa.framework.theme.SettingsDimension

@Composable
fun EqualizerBands(viewModel: EqualizerViewModel) {
    val preset by viewModel.preset.collectAsState()
    val bandGains = preset.bandGains

    Card(
        modifier = Modifier
            .padding(SettingsDimension.itemPadding)
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceColorAtElevation(2.dp)
        )
    ) {
        LazyRow(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            item {
                BandGainSliderLabels()
            }
            items(bandGains.size) { index ->
                BandGainSlider(
                    bandGains[index],
                    onValueChangeFinished = {
                        viewModel.setGain(index, it)
                    }
                )
            }
        } 
    }

}
