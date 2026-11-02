package com.yashwant.paramapadida.ui.components

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.yashwant.paramapadida.data.model.Lesson
import com.yashwant.paramapadida.engine.ExternalPlayerHelper
import com.yashwant.paramapadida.ui.theme.*
import com.yashwant.paramapadida.util.AppLogger
import com.yashwant.paramapadida.util.LogLevel
import kotlinx.coroutines.launch

@Composable
fun DebugLogsDialog(
    onDismissRequest: () -> Unit,
    currentLesson: Lesson? = null
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val logs by AppLogger.logsFlow.collectAsState()
    val listState = rememberLazyListState()
    var copyBannerText by remember { mutableStateOf<String?>(null) }

    val kyotoPkg = remember { ExternalPlayerHelper.findKyotoPackage(context) }
    val isKyotoInstalled = kyotoPkg != null

    // Auto-scroll to latest log entry
    LaunchedEffect(logs.size) {
        if (logs.isNotEmpty()) {
            listState.animateScrollToItem(logs.size - 1)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            color = DarkBackground,
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF2E2E2E))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFF261C1C)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.BugReport,
                                contentDescription = null,
                                tint = Color(0xFFFF5370),
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "Bug Logs & Diagnostics",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                            Text(
                                text = "Inspect player errors and export full system log",
                                fontSize = 11.sp,
                                color = TextSecondary
                            )
                        }
                    }

                    IconButton(onClick = onDismissRequest) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = TextPrimary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                // Status Badges Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Kyoto Status
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurface)
                            .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(if (isKyotoInstalled) AccentGreen else Color(0xFFF59E0B))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (kyotoPkg != null) "Kyoto: $kyotoPkg" else "Kyoto: Not Detected",
                            fontSize = 11.sp,
                            color = TextPrimary
                        )
                    }

                    // Logs Count
                    Row(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(DarkSurface)
                            .border(1.dp, Color(0xFF333333), RoundedCornerShape(8.dp))
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "${logs.size} logs recorded",
                            fontSize = 11.sp,
                            color = TextSecondary
                        )
                    }
                }

                if (currentLesson != null) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = "Active Target: Ep #${currentLesson.videoIndex} - ${currentLesson.title}",
                        fontSize = 11.sp,
                        color = AccentBlue,
                        maxLines = 1
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Action Buttons Row (Copy Full Logs, Run Diagnostics, Clear)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Big Prominent "Copy Full Logs" Button
                    Button(
                        onClick = {
                            val fullReport = AppLogger.buildDiagnosticReport(context, currentLesson)
                            val success = AppLogger.copyToClipboard(context, fullReport)
                            if (success) {
                                copyBannerText = "Full logs copied! Paste it into our chat."
                                Toast.makeText(context, "Full logs copied to clipboard!", Toast.LENGTH_SHORT).show()
                            }
                        },
                        modifier = Modifier
                            .weight(1.4f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = AccentBlue,
                            contentColor = Color.White
                        )
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "Copy Full Logs",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    // Run Diagnostics
                    OutlinedButton(
                        onClick = {
                            coroutineScope.launch {
                                AppLogger.i("Diagnostics", "Running on-demand diagnostic probe...")
                                val report = AppLogger.buildDiagnosticReport(context, currentLesson)
                                AppLogger.i("Diagnostics", "Probe finished.")
                                copyBannerText = "Diagnostics refreshed"
                            }
                        },
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = TextPrimary
                        ),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF444444))
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Probe", fontSize = 12.sp)
                    }

                    // Clear
                    IconButton(
                        onClick = {
                            AppLogger.clear()
                            copyBannerText = null
                        },
                        modifier = Modifier
                            .size(44.dp)
                            .border(1.dp, Color(0xFF444444), RoundedCornerShape(10.dp))
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "Clear logs",
                            tint = Color(0xFFEF4444)
                        )
                    }
                }

                copyBannerText?.let { banner ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF132B1C))
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "✓ $banner",
                            fontSize = 12.sp,
                            color = AccentGreen,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Console / Terminal Box
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFF0D1117))
                        .border(1.dp, Color(0xFF21262D), RoundedCornerShape(8.dp))
                        .padding(8.dp)
                ) {
                    if (logs.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "No logs recorded yet.\nInteract with the app or play a video to view logs.",
                                color = TextMuted,
                                fontSize = 12.sp,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier
                                .fillMaxSize()
                                .horizontalScroll(rememberScrollState())
                        ) {
                            items(logs, key = { it.id }) { entry ->
                                val color = when (entry.level) {
                                    LogLevel.ERROR -> Color(0xFFFF5370)
                                    LogLevel.WARN -> Color(0xFFFFCB6B)
                                    LogLevel.INFO -> Color(0xFF89DDFF)
                                    LogLevel.DEBUG -> Color(0xFF717CB4)
                                }

                                Column(modifier = Modifier.padding(vertical = 2.dp)) {
                                    Row {
                                        Text(
                                            text = "[${entry.formattedTime}] ",
                                            color = Color(0xFF546E7A),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "[${entry.level.name}] ",
                                            color = color,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = "[${entry.tag}] ",
                                            color = Color(0xFFC792EA),
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text(
                                            text = entry.message,
                                            color = TextPrimary,
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                    entry.throwableStr?.let { trace ->
                                        Text(
                                            text = trace,
                                            color = Color(0xFFFF8B94),
                                            fontSize = 10.sp,
                                            fontFamily = FontFamily.Monospace,
                                            modifier = Modifier.padding(start = 16.dp, top = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
