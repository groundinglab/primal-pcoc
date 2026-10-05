package net.primal.android.pcoc

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import net.primal.android.R
import net.primal.android.core.compose.PrimalDivider
import net.primal.android.theme.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PcocScreen(onClose: () -> Unit, onDoor: (PcocDoor) -> Unit) {
    PcocScaffold(
        title = stringResource(id = R.string.pcoc_title),
        onClose = onClose,
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues),
        ) {
            items(items = PcocDoor.entries, key = { it.name }) { door ->
                PcocDoorRow(door = door, onClick = { onDoor(door) })
                PrimalDivider()
            }
        }
    }
}

@Composable
private fun PcocDoorRow(door: PcocDoor, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier
            .height(60.dp)
            .clickable(onClick = onClick),
        colors = ListItemDefaults.colors(
            containerColor = AppTheme.colorScheme.surfaceVariant,
        ),
        headlineContent = {
            Text(
                text = door.title(),
                style = AppTheme.typography.titleLarge,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = null,
                )
            }
        },
    )
}

@Composable
private fun PcocDoor.title(): String =
    when (this) {
        PcocDoor.Enroll -> stringResource(id = R.string.pcoc_enroll_title)
        PcocDoor.Verify -> stringResource(id = R.string.pcoc_verify_title)
        PcocDoor.Create -> stringResource(id = R.string.pcoc_create_title)
        PcocDoor.Admit -> stringResource(id = R.string.pcoc_admit_title)
    }
