package com.example.huaweikyouyu.ui.main

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import com.example.huaweikyouyu.ai.GeminiClient
import com.example.huaweikyouyu.health.HealthData
import com.example.huaweikyouyu.health.HealthKitManager
import com.example.huaweikyouyu.storage.ObsidianStorageManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    fun fetchHealthData(context: Context) {
        _uiState.update { it.copy(isLoading = true, errorMessage = null) }
        val isMock = _uiState.value.isMockMode
        if (isMock) {
            HealthKitManager.fetchTodayHealthData(context, true) { data ->
                _uiState.update {
                    if (data != null) {
                        it.copy(healthData = data, isLoading = false, successMessage = "ヘルスデータ（テスト）を取得しました")
                    } else {
                        it.copy(isLoading = false, errorMessage = "ヘルスデータの取得に失敗しました。")
                    }
                }
            }
        } else {
            HealthKitManager.requestHealthPermissions(context) { permitted ->
                if (permitted) {
                    HealthKitManager.fetchTodayHealthData(context, false) { data ->
                        _uiState.update {
                            if (data != null) {
                                it.copy(healthData = data, isLoading = false, successMessage = "ヘルスデータを取得しました")
                            } else {
                                it.copy(isLoading = false, errorMessage = "ヘルスデータの取得に失敗しました。HMS設定を確認してください。")
                            }
                        }
                    }
                } else {
                    _uiState.update { it.copy(isLoading = false, errorMessage = "Health Kitへのアクセス権限がありません。") }
                }
            }
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

    fun clearMessages() {
        _uiState.update { it.copy(errorMessage = null, successMessage = null) }
    }
}
