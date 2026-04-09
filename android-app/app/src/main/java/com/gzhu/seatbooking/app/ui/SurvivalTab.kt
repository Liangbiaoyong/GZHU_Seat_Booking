package com.gzhu.seatbooking.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gzhu.seatbooking.app.data.model.AppConfig

@Composable
fun SurvivalTab(config: AppConfig, onBasicConfigChange: (AppConfig) -> Unit) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "系统守护与状态监测",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp, bottom = 4.dp, top = 8.dp)
            )
        }
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("存活监测通知", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                        Switch(
                            checked = config.survivalNotifyEnabled,
                            onCheckedChange = { onBasicConfigChange(config.copy(survivalNotifyEnabled = it)) }
                        )
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "定时发送系统通知，汇报Alarm/Work/Job三大定时调度服务的存活状态。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = config.survivalNotifyTime,
                        onValueChange = { onBasicConfigChange(config.copy(survivalNotifyTime = it)) },
                        label = { Text("通知下发时间") },
                        singleLine = true,
                        placeholder = { Text("00:00") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}
