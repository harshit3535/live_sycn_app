package com.example.gitsync.ui

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

@Composable
fun FolderBrowserDialog(
    startPath: String,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit
) {
    val rootStorage = Environment.getExternalStorageDirectory()
    var current by remember {
        mutableStateOf(
            startPath.takeIf { it.isNotBlank() && File(it).isDirectory }?.let { File(it) } ?: rootStorage
        )
    }

    val subDirs = remember(current) {
        current.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SurfaceColor,
        title = {
            Column {
                Text("Select folder", color = TextPrimary, fontWeight = FontWeight.Bold)
                Text(
                    current.path,
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2
                )
            }
        },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                if (current.path != rootStorage.path && current.parentFile != null) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { current = current.parentFile ?: rootStorage }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.ArrowUpward, contentDescription = null, tint = TextSecondary)
                            Spacer(Modifier.width(12.dp))
                            Text("..", color = TextSecondary)
                        }
                    }
                }
                items(subDirs) { dir ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { current = dir }
                            .padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Folder, contentDescription = null, tint = AccentBlue)
                        Spacer(Modifier.width(12.dp))
                        Text(dir.name, color = TextPrimary)
                    }
                }
                if (subDirs.isEmpty()) {
                    item {
                        Text(
                            "No sub-folders here",
                            color = TextSecondary,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(current.path) }) {
                Text("Use this folder", color = AccentGreen)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = TextSecondary) }
        }
    )
}
