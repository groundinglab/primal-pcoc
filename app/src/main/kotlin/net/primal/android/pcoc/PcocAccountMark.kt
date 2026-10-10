package net.primal.android.pcoc

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.theme.AppTheme

private val PcocMarkOrange = Color(0xFFE8772E)

/** Cosmetic. True only on the active account's own surface while a PCOC is saved. */
fun pcocMarkVisible(isOwnProfile: Boolean, hasSavedPcoc: Boolean): Boolean = isOwnProfile && hasSavedPcoc

@Composable
fun PcocOrangeCheck(modifier: Modifier = Modifier) {
    Icon(
        modifier = modifier.size(16.dp),
        imageVector = Icons.Filled.Check,
        contentDescription = stringResource(id = R.string.pcoc_saved_mark),
        tint = PcocMarkOrange,
    )
}

@Composable
fun PcocAccountNameRow(
    name: String,
    showMark: Boolean,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = name,
            style = AppTheme.typography.titleLarge,
            color = AppTheme.colorScheme.onPrimary,
        )
        if (showMark) {
            PcocOrangeCheck(modifier = Modifier.padding(start = 6.dp))
        }
    }
}
