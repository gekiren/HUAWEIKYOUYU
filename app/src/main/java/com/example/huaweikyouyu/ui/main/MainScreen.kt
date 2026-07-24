package com.example.huaweikyouyu.ui.main

import android.net.Uri
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import com.example.huaweikyouyu.health.HealthData
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    onItemClick: (NavKey) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MainScreenViewModel = viewModel(),
) {
    val context = LocalContext.current
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // Initialize ViewModel parameters on start
    LaunchedEffect(Unit) {
        viewModel.init(context)
    }

    // SAF Directory Picker Launcher
    val folderPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri: Uri? ->
        uri?.let {
            viewModel.saveVaultUri(context, it)
        }
    }

    // 2段階目: Health Kit 権限同意画面のランチャー
    val healthKitAuthLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK) {
            viewModel.fetchHealthData(context)
        } else {
            viewModel.onAuthResult(false, context)
        }
    }

    // 1段階目: Huawei ID Sign-in Launcher
    val signInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val authHuaweiId = com.example.huaweikyouyu.health.HealthKitManager.parseAuthResult(result.data)
        if (authHuaweiId != null) {
            try {
                val intent = com.example.huaweikyouyu.health.HealthKitManager.getHealthKitAuthIntent(context, authHuaweiId)
                healthKitAuthLauncher.launch(intent)
            } catch (e: Exception) {
                Log.e("MainScreen", "Failed to launch Health Kit Auth", e)
                viewModel.onAuthResult(false, context)
            }
        } else {
            viewModel.onAuthResult(false, context)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "HUAWEI KYOUYU (共有)",
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ),
                actions = {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Settings",
                        tint = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(end = 16.dp)
                    )
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Error Alert Card
                state.errorMessage?.let { error ->
                    AlertCard(
                        message = error,
                        isError = true,
                        onDismiss = { viewModel.clearMessages() }
                    )
                }

                // Success Alert Card
                state.successMessage?.let { success ->
                    AlertCard(
                        message = success,
                        isError = false,
                        onDismiss = { viewModel.clearMessages() }
                    )
                }

                // 1. Settings Card (API Key & Obsidian Folder)
                SettingsSection(
                    apiKey = state.geminiApiKey,
                    folderUri = state.obsidianFolderUri,
                    onApiKeyChange = { viewModel.saveApiKey(context, it) },
                    onSelectFolder = {
                        folderPickerLauncher.launch(null)
                    }
                )

                // 2. Health Data Card
                HealthDataSection(
                    healthData = state.healthData,
                    isMockMode = state.isMockMode,
                    onMockModeToggle = { viewModel.toggleMockMode(it) },
                    onFetchClick = {
                        viewModel.fetchHealthData(context, onSignInRequired = {
                            val intent = com.example.huaweikyouyu.health.HealthKitManager.getSignInIntent(context)
                            signInLauncher.launch(intent)
                        })
                    }
                )

                // 3. Gemini Analysis & Action Card
                AnalysisSection(
                    analysisResult = state.analysisResult,
                    isDataLoaded = state.healthData != null,
                    onRunAnalysis = { viewModel.runAiAnalysis(context) },
                    onSaveToObsidian = { viewModel.saveToObsidian(context) },
                    onSaveRawData = { viewModel.saveRawDataToObsidian(context) }
                )
            }

            // Loading overlay
            if (state.isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

@Composable
fun AlertCard(message: String, isError: Boolean, onDismiss: () -> Unit) {
    val backgroundColor = if (isError) Color(0xFFFDE8E8) else Color(0xFFEAFEFA)
    val contentColor = if (isError) Color(0xFF9B1C1C) else Color(0xFF0F5B4B)
    val icon = if (isError) Icons.Default.Info else Icons.Default.CheckCircle

    Card(
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = if (isError) "Error" else "Success",
                    tint = contentColor,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = message,
                    color = contentColor,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            TextButton(onClick = onDismiss) {
                Text("閉じる", color = contentColor, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun SettingsSection(
    apiKey: String,
    folderUri: String,
    onApiKeyChange: (String) -> Unit,
    onSelectFolder: () -> Unit
) {
    var keyText by remember(apiKey) { mutableStateOf(apiKey) }
    var isKeyVisible by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "⚙️ 設定",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            // API Key Input
            OutlinedTextField(
                value = keyText,
                onValueChange = {
                    keyText = it
                    onApiKeyChange(it)
                },
                label = { Text("Gemini API キー") },
                placeholder = { Text("AIzaSy...") },
                visualTransformation = if (isKeyVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
                trailingIcon = {
                    TextButton(onClick = { isKeyVisible = !isKeyVisible }) {
                        Text(if (isKeyVisible) "非表示" else "表示")
                    }
                }
            )

            // Obsidian Folder Selection
            val hasFolder = folderUri.isNotEmpty()
            val folderPathDisplay = if (hasFolder) {
                val decoded = Uri.decode(folderUri)
                decoded.substringAfterLast("/")
            } else {
                "未設定（フォルダを選択してください）"
            }

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    "Obsidian 出力先フォルダ:",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    folderPathDisplay,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (hasFolder) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Button(
                    onClick = onSelectFolder,
                    modifier = Modifier.align(Alignment.End),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.secondary
                    )
                ) {
                    Text("フォルダを選択")
                }
            }
        }
    }
}

@Composable
fun HealthDataSection(
    healthData: HealthData?,
    isMockMode: Boolean,
    onMockModeToggle: (Boolean) -> Unit,
    onFetchClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "🏃 今日のデータ取得",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                // Mock Mode Toggle
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text("テストデータ", fontSize = 12.sp)
                    Switch(
                        checked = isMockMode,
                        onCheckedChange = onMockModeToggle
                    )
                }
            }

            Button(
                onClick = onFetchClick,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("データを取り込む")
            }

            if (healthData != null) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    HorizontalDivider()
                    Text("📊 取得したデータ (${healthData.date})", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    
                    // 1. アクティビティ & 睡眠
                    Text("🏃 アクティビティ & 睡眠", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "歩数",
                            value = "${healthData.steps} 歩",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "アクティビティ時間",
                            value = "${healthData.activityDurationMinutes} 分",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "移動距離",
                            value = if (healthData.distanceMeters > 0) String.format(Locale.US, "%.1f m", healthData.distanceMeters) else "0.0 m",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "消費カロリー",
                            value = if (healthData.activeCaloriesBurned > 0) String.format(Locale.US, "%.1f kcal", healthData.activeCaloriesBurned) else "0.0 kcal",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "中高強度運動時間",
                            value = "${healthData.moderateHighIntensityDurationMinutes} 分",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "平均心拍数",
                            value = "${healthData.averageHeartRate} bpm",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "平均血中酸素",
                            value = "${healthData.bloodOxygenAverage} %",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "ストレス",
                            value = "${healthData.stressLevel} / 100",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "総睡眠時間",
                            value = "${healthData.sleepDurationMinutes / 60}時間 ${healthData.sleepDurationMinutes % 60}分",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "深い睡眠",
                            value = "${healthData.deepSleepMinutes} 分",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "浅い睡眠",
                            value = "${healthData.lightSleepMinutes} 分",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "レム睡眠",
                            value = "${healthData.remSleepMinutes} 分",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "覚醒時間",
                            value = "${healthData.awakeMinutes} 分",
                            modifier = Modifier.weight(0.5f)
                        )
                        Spacer(modifier = Modifier.weight(0.5f))
                    }

                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // 2. 体重 & 基本体組成
                    Text("⚖️ 体重 & 基本体組成", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "体重",
                            value = if (healthData.weight > 0) String.format(Locale.US, "%.1f kg", healthData.weight) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "BMI",
                            value = if (healthData.bmi > 0) String.format(Locale.US, "%.1f", healthData.bmi) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "体脂肪率",
                            value = if (healthData.bodyFatRate > 0) String.format(Locale.US, "%.1f %%", healthData.bodyFatRate) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "体脂肪量",
                            value = if (healthData.bodyFat > 0) String.format(Locale.US, "%.1f kg", healthData.bodyFat) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "筋肉量",
                            value = if (healthData.muscleMass > 0) String.format(Locale.US, "%.1f kg", healthData.muscleMass) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "骨格筋量",
                            value = if (healthData.skeletalMuscleMass > 0) String.format(Locale.US, "%.1f kg", healthData.skeletalMuscleMass) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "骨量 (骨塩量)",
                            value = if (healthData.boneSalt > 0) String.format(Locale.US, "%.2f kg", healthData.boneSalt) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "タンパク質率",
                            value = if (healthData.proteinRate > 0) String.format(Locale.US, "%.1f %%", healthData.proteinRate) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // 3. 水分 & その他測定値
                    Text("🧬 水分 & その他測定値", fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "水分量",
                            value = if (healthData.moisture > 0) String.format(Locale.US, "%.1f L", healthData.moisture) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "水分率",
                            value = if (healthData.moistureRate > 0) String.format(Locale.US, "%.1f %%", healthData.moistureRate) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "基礎代謝量",
                            value = if (healthData.basalMetabolism > 0) String.format(Locale.US, "%.0f kcal", healthData.basalMetabolism) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "内臓脂肪レベル",
                            value = if (healthData.visceralFatLevel > 0) String.format(Locale.US, "%.1f", healthData.visceralFatLevel) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "体内年齢",
                            value = if (healthData.bodyAge > 0) "${healthData.bodyAge} 才" else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "体組成スコア",
                            value = if (healthData.bodyScore > 0) String.format(Locale.US, "%.1f 点", healthData.bodyScore) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "VO2 Max",
                            value = if (healthData.vo2Max > 0) String.format(Locale.US, "%.1f ml/kg", healthData.vo2Max) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                        HealthStatItem(
                            label = "皮膚温度",
                            value = if (healthData.skinTemperature > 0) String.format(Locale.US, "%.1f ℃", healthData.skinTemperature) else "データ無し",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        HealthStatItem(
                            label = "インピーダンス",
                            value = if (healthData.impedance > 0) String.format(Locale.US, "%.1f Ω", healthData.impedance) else "データ無し",
                            modifier = Modifier.weight(0.5f)
                        )
                        Spacer(modifier = Modifier.weight(0.5f))
                    }
                }
            } else {
                Text(
                    "データがまだ取り込まれていません。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
fun HealthStatItem(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
            .padding(12.dp)
    ) {
        Column {
            Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
fun AnalysisSection(
    analysisResult: String,
    isDataLoaded: Boolean,
    onRunAnalysis: () -> Unit,
    onSaveToObsidian: () -> Unit,
    onSaveRawData: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "🤖 Gemini 分析 ＆ 書き出し",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Button(
                onClick = onRunAnalysis,
                enabled = isDataLoaded,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                )
            ) {
                Text("AI要約を生成 (gemini-3.5-flash)")
            }

            Button(
                onClick = onSaveRawData,
                enabled = isDataLoaded,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                )
            ) {
                Text("生データを直接保存")
            }

            if (analysisResult.isNotEmpty()) {
                HorizontalDivider()
                Text("📝 分析レポートプレビュー:", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 200.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f))
                        .padding(12.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(analysisResult, fontSize = 14.sp)
                }

                Button(
                    onClick = onSaveToObsidian,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF6B46C1)
                    )
                ) {
                    Text("Obsidian Daily Note に保存", color = Color.White)
                }
            }
        }
    }
}
