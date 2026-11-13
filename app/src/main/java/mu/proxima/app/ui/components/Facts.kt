package mu.proxima.app.ui.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import mu.proxima.app.ui.theme.sizes
import mu.proxima.app.ui.theme.spacing

/** Label on the left, value on the right: aligned facts instead of dot-joined meta strings. */
@Composable
fun Fact(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = MaterialTheme.spacing.xs)) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(MaterialTheme.sizes.touchTarget * 2 + MaterialTheme.spacing.lg),
        )
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}
