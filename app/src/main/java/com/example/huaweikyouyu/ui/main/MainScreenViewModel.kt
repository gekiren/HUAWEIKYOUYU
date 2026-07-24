package com.example.huaweikyouyu.ui.main

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.example.huaweikyouyu.ai.GeminiClient
import com.example.huaweikyouyu.health.HealthData
import com.example.huaweikyouyu.health.HealthKitManager
import com.example.huaweikyouyu.storage.ObsidianStorageManager
import com.huawei.hms.support.hwid.result.AuthHuaweiId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class MainUiState(
    val geminiApiKey: String = "",
    val obsidianFolderUri: String = "",
    val healthData: HealthData? = null,
    val analysisResult: String = "",
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val isMockMode: Boolean = true
)

class MainScreenViewModel : ViewModel() {
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Emits AuthHuaweiId when Health Kit authorization consent is required (102 error)
    private val _healthKitAuthRequired = MutableSharedFlow<AuthHuaweiId>(extraBufferCapacity = 1)
    val healthKitAuthRequired: SharedFlow<AuthHuaweiId> = _healthKitAuthRequired.asSharedFlow()

    private val PREFS_NAME = "gemini_prefs"
    private val KEY_API_KEY = "api_key"

    fun init(context: Context) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val apiKey = sharedPrefs.getString(KEY_API_KEY, "") ?: ""
        
        val vaultUri = ObsidianStorageManager.getSavedVaultUri(context)
        val vaultUriStr = vaultUri?.toString() ?: ""

        _uiState.update { 
            it.copy(
                geminiApiKey = apiKey,
                obsidianFolderUri = vaultUriStr
            )
        }
    }

    fun saveApiKey(context: Context, key: String) {
        val sharedPrefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        sharedPrefs.edit().putString(KEY_API_KEY, key).apply()
        _uiState.update { it.copy(geminiApiKey = key) }
    }

    fun saveVaultUri(context: Context, uri: Uri) {
        if (ObsidianStorageManager.saveVaultUri(context, uri)) {
            _uiState.update { it.copy(obsidianFolderUri = uri.toString()) }
        }
    }

    fun toggleMockMode(isMock: Boolean) {
        _uiState.update { it.copy(isMockMode = isMock) }
    }

    fun fetchHealthData(context: Context, onSignInRequired: () -> Unit = {}) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val isMock = _uiState.value.isMockMode
        if (isMock) {
            HealthKitManager.fetchTodayHealthData(context, true) { data, errorMsg, _ ->
                _uiState.update {
                    if (data != null) {
                        it.copy(healthData = data, isLoading = false, successMessage = "ヘルスデータ（テスト）を取得しました")
                    } else {
                        it.copy(isLoading = false, errorMessage = errorMsg ?: "ヘルスデータの取得に失敗しました。")
                    }
                }
            }
        } else {
            HealthKitManager.requestHealthPermissions(context) { permitted ->
                if (permitted) {
                    HealthKitManager.fetchTodayHealthData(context, false) { data, errorMsg, authHuaweiId ->
                        if (data != null) {
                            _uiState.update {
                                it.copy(healthData = data, isLoading = false, successMessage = "ヘルスデータを取得しました")
                            }
                        } else if (authHuaweiId != null) {
                            // 102 error: silently launch the SettingController consent screen
                            _uiState.update { it.copy(isLoading = false) }
                            _healthKitAuthRequired.tryEmit(authHuaweiId)
                        } else {
                            _uiState.update {
                                it.copy(isLoading = false, errorMessage = errorMsg ?: "ヘルスデータの取得に失敗しました。HMS設定を確認してください。")
                            }
                        }
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false) }
                    onSignInRequired()
                }
            }
        }
    }

    fun onAuthResult(success: Boolean, context: Context) {
        if (success) {
            fetchHealthData(context)
        } else {
            _uiState.update { it.copy(errorMessage = "HUAWEI 認証がキャンセルされたか、失敗しました。") }
        }
    }

    fun runAiAnalysis(context: Context) {
        val apiKey = _uiState.value.geminiApiKey
        val healthData = _uiState.value.healthData

        if (apiKey.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "Gemini API キーを設定してください。") }
            return
        }
        if (healthData == null) {
            _uiState.update { it.copy(errorMessage = "先にヘルスデータを取得してください。") }
            return
        }

        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        val systemPrompt = """
            あなたは優秀なヘルス＆フィットネスコーチです。
            提供されたユーザーの1日のヘルスデータ（歩数、睡眠時間、心拍数、ストレスレベル、血中酸素、アクティビティ時間）を分析し、
            健康維持やパフォーマンス向上のためのアドバイス、および今日の総括をMarkdownフォーマットで出力してください。
            
            # 出力フォーマットの要件:
            - 見出しやリストを活用して読みやすく整理してください。
            - ユーザーを褒め、ポジティブで建設的なアドバイスを心がけてください。
            - Obsidianのデイリーノートにそのまま統合できるよう、マークダウンとして完結させてください。
        """.trimIndent()

        val prompt = """
            $systemPrompt
            
            ${healthData.toPromptString()}
        """.trimIndent()

        GeminiClient.generateContent(apiKey, prompt) { success, result ->
            _uiState.update {
                if (success) {
                    it.copy(analysisResult = result, isLoading = false, successMessage = "分析レポートが生成されました")
                } else {
                    it.copy(isLoading = false, errorMessage = "AI分析に失敗しました: $result")
                }
            }
        }
    }

    fun saveToObsidian(context: Context) {
        val result = _uiState.value.analysisResult
        if (result.isEmpty()) {
            _uiState.update { it.copy(errorMessage = "保存する分析レポートがありません。先に分析を実行してください。") }
            return
        }

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        val success = ObsidianStorageManager.saveDailyNote(context, todayStr, result)
        _uiState.update {
            if (success) {
                it.copy(isLoading = false, successMessage = "Obsidianに保存しました（ファイル名: $todayStr.md）")
            } else {
                it.copy(isLoading = false, errorMessage = "Obsidianへの保存に失敗しました。フォルダ設定を確認してください。")
            }
        }
    }

    fun saveRawDataToObsidian(context: Context) {
        val healthData = _uiState.value.healthData
        if (healthData == null) {
            _uiState.update { it.copy(errorMessage = "保存するヘルスデータがありません。先にデータを取り込んでください。") }
            return
        }

        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }

        // Format raw health data into Markdown
        val markdownContent = """
            
            ## 📊 ヘルスデータレポート (${healthData.date} 取得)
            
            ### 🏃 アクティビティ & 睡眠
            - **歩数**: ${healthData.steps} 歩
            - **アクティビティ時間**: ${healthData.activityDurationMinutes} 分
            - **移動距離**: ${if (healthData.distanceMeters > 0) String.format(Locale.US, "%.1f m", healthData.distanceMeters) else "0.0 m"}
            - **消費カロリー**: ${if (healthData.activeCaloriesBurned > 0) String.format(Locale.US, "%.1f kcal", healthData.activeCaloriesBurned) else "0.0 kcal"}
            - **中高強度運動時間**: ${healthData.moderateHighIntensityDurationMinutes} 分
            - **平均心拍数**: ${healthData.averageHeartRate} bpm
            - **平均血中酸素**: ${healthData.bloodOxygenAverage} %
            - **ストレス**: ${healthData.stressLevel} / 100
            - **総睡眠時間**: ${healthData.sleepDurationMinutes / 60}時間 ${healthData.sleepDurationMinutes % 60}分
            - **深い睡眠**: ${healthData.deepSleepMinutes} 分
            - **浅い睡眠**: ${healthData.lightSleepMinutes} 分
            - **レム睡眠**: ${healthData.remSleepMinutes} 分
            - **覚醒時間**: ${healthData.awakeMinutes} 分
            
            ### ⚖️ 体重 & 基本体組成
            - **体重**: ${if (healthData.weight > 0) String.format(Locale.US, "%.1f kg", healthData.weight) else "データ無し"}
            - **BMI**: ${if (healthData.bmi > 0) String.format(Locale.US, "%.1f", healthData.bmi) else "データ無し"}
            - **体脂肪率**: ${if (healthData.bodyFatRate > 0) String.format(Locale.US, "%.1f %%", healthData.bodyFatRate) else "データ無し"}
            - **体脂肪量**: ${if (healthData.bodyFat > 0) String.format(Locale.US, "%.1f kg", healthData.bodyFat) else "データ無し"}
            - **筋肉量**: ${if (healthData.muscleMass > 0) String.format(Locale.US, "%.1f kg", healthData.muscleMass) else "データ無し"}
            - **骨格筋量**: ${if (healthData.skeletalMuscleMass > 0) String.format(Locale.US, "%.1f kg", healthData.skeletalMuscleMass) else "データ無し"}
            - **骨量 (骨塩量)**: ${if (healthData.boneSalt > 0) String.format(Locale.US, "%.2f kg", healthData.boneSalt) else "データ無し"}
            - **タンパク質率**: ${if (healthData.proteinRate > 0) String.format(Locale.US, "%.1f %%", healthData.proteinRate) else "データ無し"}
            
            ### 🧬 水分 & その他測定値
            - **水分量**: ${if (healthData.moisture > 0) String.format(Locale.US, "%.1f L", healthData.moisture) else "データ無し"}
            - **水分率**: ${if (healthData.moistureRate > 0) String.format(Locale.US, "%.1f %%", healthData.moistureRate) else "データ無し"}
            - **基礎代謝量**: ${if (healthData.basalMetabolism > 0) String.format(Locale.US, "%.0f kcal", healthData.basalMetabolism) else "データ無し"}
            - **内臓脂肪レベル**: ${if (healthData.visceralFatLevel > 0) String.format(Locale.US, "%.1f", healthData.visceralFatLevel) else "データ無し"}
            - **体内年齢**: ${if (healthData.bodyAge > 0) "${healthData.bodyAge} 才" else "データ無し"}
            - **体組成スコア**: ${if (healthData.bodyScore > 0) String.format(Locale.US, "%.1f 点", healthData.bodyScore) else "データ無し"}
            - **VO2 Max**: ${if (healthData.vo2Max > 0) String.format(Locale.US, "%.1f ml/kg", healthData.vo2Max) else "データ無し"}
            - **皮膚温度**: ${if (healthData.skinTemperature > 0) String.format(Locale.US, "%.1f ℃", healthData.skinTemperature) else "データ無し"}
            - **インピーダンス**: ${if (healthData.impedance > 0) String.format(Locale.US, "%.1f Ω", healthData.impedance) else "データ無し"}
        """.trimIndent()

        val success = ObsidianStorageManager.saveDailyNote(context, todayStr, markdownContent)
        _uiState.update {
            if (success) {
                it.copy(isLoading = false, successMessage = "生データをObsidianに保存しました（ファイル名: $todayStr.md）")
            } else {
                it.copy(isLoading = false, errorMessage = "Obsidianへの保存に失敗しました。フォルダ設定を確認してください。")
            }
        }
    }

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
