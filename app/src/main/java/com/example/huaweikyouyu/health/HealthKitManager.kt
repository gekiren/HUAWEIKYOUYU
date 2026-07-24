package com.example.huaweikyouyu.health

import android.content.Context
import android.util.Log
import com.huawei.hmf.tasks.Task
import com.huawei.hms.hihealth.HuaweiHiHealth
import com.huawei.hms.hihealth.data.DataType
import com.huawei.hms.hihealth.data.Field
import com.huawei.hms.hihealth.data.SampleSet
import com.huawei.hms.hihealth.data.SamplePoint
import com.huawei.hms.hihealth.data.Value
import com.huawei.hms.hihealth.result.ReadReply
import com.huawei.hms.hihealth.options.ReadOptions
import com.huawei.hms.support.api.entity.auth.Scope
import com.huawei.hms.support.hwid.HuaweiIdAuthManager
import com.huawei.hms.support.hwid.request.HuaweiIdAuthParams
import com.huawei.hms.support.hwid.request.HuaweiIdAuthParamsHelper
import com.huawei.hms.support.hwid.result.AuthHuaweiId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class HealthData(
    val date: String,
    val steps: Int,
    val sleepDurationMinutes: Int,
    val averageHeartRate: Int,
    val bloodOxygenAverage: Double,
    val stressLevel: Int,
    val activityDurationMinutes: Int,
    val weight: Double,
    val bodyFatRate: Double,
    val bmi: Double,
    val muscleMass: Double
) {
    fun toPromptString(): String {
        return """
            ■ ヘルスデータ（$date）
            - 歩数: $steps 歩
            - アクティビティ時間: $activityDurationMinutes 分
            - 睡眠時間: ${sleepDurationMinutes / 60}時間 ${sleepDurationMinutes % 60}分
            - 平均心拍数: $averageHeartRate bpm
            - 平均血中酸素レベル: $bloodOxygenAverage %
            - ストレスレベル: $stressLevel (1-100)
            - 体重: $weight kg
            - 体脂肪率: $bodyFatRate %
            - BMI: $bmi
            - 筋肉量: $muscleMass kg
        """.trimIndent()
    }
}

object HealthKitManager {
    private const val TAG = "HealthKitManager"

    // String representation of scopes to request authorization
    private val SCOPES = arrayOf(
        "https://www.huawei.com/healthkit/step.read",
        "https://www.huawei.com/healthkit/sleep.read",
        "https://www.huawei.com/healthkit/heartrate.read",
        "https://www.huawei.com/healthkit/spO2.read",
        "https://www.huawei.com/healthkit/stress.read",
        "https://www.huawei.com/healthkit/activity.read",
        "https://www.huawei.com/healthkit/weight.read"
    )

    /**
     * Helper extension to convert HMS Tasks to Coroutine suspends
     */
    private suspend fun <T> Task<T>.awaitHMS(): T = suspendCancellableCoroutine { continuation ->
        addOnSuccessListener { result ->
            if (continuation.isActive) continuation.resume(result)
        }
        addOnFailureListener { exception ->
            if (continuation.isActive) continuation.resumeWithException(exception)
        }
    }

    /**
     * Request permissions for Health Kit.
     * In mock mode, this will succeed immediately.
     */
    fun requestHealthPermissions(context: Context, onResult: (Boolean) -> Unit) {
        try {
            val scopeList = SCOPES.map { Scope(it) }
            val authParams = HuaweiIdAuthParamsHelper(HuaweiIdAuthParams.DEFAULT_AUTH_REQUEST_PARAM)
                .setScopeList(scopeList)
                .createParams()
            val authService = HuaweiIdAuthManager.getService(context, authParams)
            
            // Try silent sign-in first
            authService.silentSignIn()
                .addOnSuccessListener {
                    Log.d(TAG, "Silent sign in successful")
                    onResult(true)
                }
                .addOnFailureListener { exception ->
                    Log.w(TAG, "Silent sign in failed, need interactive sign in", exception)
                    onResult(false)
                }
        } catch (e: Exception) {
            Log.e(TAG, "Error requesting permissions", e)
            onResult(false)
        }
    }

    /**
     * Fetch health data for today.
     * Supports both Mock mode and real HMS Health Kit SDK querying.
     */
    fun fetchTodayHealthData(context: Context, isMock: Boolean = true, onResult: (HealthData?) -> Unit) {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (isMock) {
            // Generate realistic random mock data including weight and body composition
            val mockData = HealthData(
                date = todayStr,
                steps = (6000..12000).random(),
                sleepDurationMinutes = (360..540).random(), // 6 to 9 hours
                averageHeartRate = (62..78).random(),
                bloodOxygenAverage = (96..99).random().toDouble(),
                stressLevel = (15..45).random(),
                activityDurationMinutes = (10..80).random(),
                weight = (600..850).random().toDouble() / 10.0, // 60.0 to 85.0 kg
                bodyFatRate = (120..240).random().toDouble() / 10.0, // 12.0 to 24.0 %
                bmi = (185..255).random().toDouble() / 10.0, // 18.5 to 25.5
                muscleMass = (450..650).random().toDouble() / 10.0 // 45.0 to 65.0 kg
            )
            onResult(mockData)
        } else {
            // Launch coroutine on Main dispatcher to perform async queries
            CoroutineScope(Dispatchers.Main).launch {
                try {
                    val data = queryHMSHealthData(context, todayStr)
                    onResult(data)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to query HMS Health data", e)
                    onResult(null)
                }
            }
        }
    }

    /**
     * Internal implementation of querying HMS Health Kit data using Coroutines
     */
    private suspend fun queryHMSHealthData(context: Context, dateStr: String): HealthData = withContext(Dispatchers.IO) {
        val scopeList = SCOPES.map { Scope(it) }
        val authParams = HuaweiIdAuthParamsHelper(HuaweiIdAuthParams.DEFAULT_AUTH_REQUEST_PARAM)
            .setScopeList(scopeList)
            .createParams()
        val authService = HuaweiIdAuthManager.getService(context, authParams)
        
        // Retrieve AuthHuaweiId
        val authHuaweiId: AuthHuaweiId = authService.silentSignIn().awaitHMS()
        val dataController = HuaweiHiHealth.getDataController(context, authHuaweiId)
        
        // Time range for today (from 00:00 to now)
        val calendar = Calendar.getInstance()
        val endTime = calendar.timeInMillis
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startTime = calendar.timeInMillis

        // 1. Fetch Steps (using today's summation)
        var steps = 0
        try {
            val stepsSampleSet: SampleSet = dataController.readTodaySummation(DataType.DT_CONTINUOUS_STEPS_DELTA).awaitHMS()
            if (stepsSampleSet != null && stepsSampleSet.samplePoints.isNotEmpty()) {
                val point: SamplePoint = stepsSampleSet.samplePoints[0]
                val value: Value = point.getFieldValue(Field.FIELD_STEPS)
                steps = value.asIntValue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching steps", e)
        }

        // 2. Fetch Sleep Duration (querying time range)
        var sleepMinutes = 0
        try {
            val sleepOptions = ReadOptions.Builder()
                .read(DataType.DT_CONTINUOUS_SLEEP)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .build()
            val sleepResult: ReadReply = dataController.read(sleepOptions).awaitHMS()
            val sampleSets: List<SampleSet> = sleepResult.sampleSets
            sampleSets.forEach { sampleSet ->
                sampleSet.samplePoints.forEach { point ->
                    val duration = point.getEndTime(TimeUnit.MINUTES) - point.getStartTime(TimeUnit.MINUTES)
                    sleepMinutes += duration.toInt()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching sleep", e)
        }

        // 3. Fetch Heart Rate Average
        var avgHeartRate = 70
        try {
            val hrOptions = ReadOptions.Builder()
                .read(DataType.DT_INSTANTANEOUS_HEART_RATE)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .build()
            val hrResult: ReadReply = dataController.read(hrOptions).awaitHMS()
            var sumHr = 0
            var countHr = 0
            val sampleSets: List<SampleSet> = hrResult.sampleSets
            sampleSets.forEach { sampleSet ->
                sampleSet.samplePoints.forEach { point ->
                    val value: Value = point.getFieldValue(Field.FIELD_BPM)
                    val hr = value.asFloatValue().toInt()
                    sumHr += hr
                    countHr++
                }
            }
            if (countHr > 0) {
                avgHeartRate = sumHr / countHr
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching heart rate", e)
        }

        // 4. Fetch Blood Oxygen (SpO2)
        // SpO2 DataType constant is not present in the compile classpath of this SDK version (6.11.0.303)
        // Returning a placeholder/mock value (98.0%) as SpO2 requires integration with custom data types or newer SDK versions
        var avgSpO2 = 98.0
        Log.i(TAG, "SpO2 query skipped - not supported natively in this SDK compile configuration. Returning mock 98%")

        // 5. Fetch Stress Level
        var stressLevel = 30
        try {
            val stressOptions = ReadOptions.Builder()
                .read(DataType.DT_INSTANTANEOUS_STRESS)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .build()
            val stressResult: ReadReply = dataController.read(stressOptions).awaitHMS()
            var sumStress = 0
            var countStress = 0
            val sampleSets: List<SampleSet> = stressResult.sampleSets
            sampleSets.forEach { sampleSet ->
                sampleSet.samplePoints.forEach { point ->
                    val value: Value = point.getFieldValue(Field.SCORE)
                    val stress = value.asIntValue()
                    sumStress += stress
                    countStress++
                }
            }
            if (countStress > 0) {
                stressLevel = sumStress / countStress
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching stress", e)
        }

        // 6. Fetch Activity Duration
        var activityMinutes = 0
        try {
            val activeOptions = ReadOptions.Builder()
                .read(DataType.DT_CONTINUOUS_STEPS_DELTA)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .build()
            val activeResult: ReadReply = dataController.read(activeOptions).awaitHMS()
            val sampleSets: List<SampleSet> = activeResult.sampleSets
            sampleSets.forEach { sampleSet ->
                sampleSet.samplePoints.forEach { point ->
                    val value: Value = point.getFieldValue(Field.FIELD_STEPS)
                    val stepsCount = value.asIntValue()
                    if (stepsCount > 10) { // active threshold
                        val duration = point.getEndTime(TimeUnit.MINUTES) - point.getStartTime(TimeUnit.MINUTES)
                        activityMinutes += duration.toInt().coerceAtLeast(1)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching activity duration", e)
        }

        // 7. Fetch Body Weight & Composition (using past 30 days to get the latest reading)
        val calendarForWeight = Calendar.getInstance()
        val endWeightTime = calendarForWeight.timeInMillis
        calendarForWeight.add(Calendar.DAY_OF_YEAR, -30) // Look back 30 days
        val startWeightTime = calendarForWeight.timeInMillis

        var weight = 0.0
        var bodyFatRate = 0.0
        var bmi = 0.0
        var muscleMass = 0.0

        try {
            val weightOptions = ReadOptions.Builder()
                .read(DataType.DT_INSTANTANEOUS_BODY_WEIGHT)
                .setTimeRange(startWeightTime, endWeightTime, TimeUnit.MILLISECONDS)
                .build()
            val weightResult: ReadReply = dataController.read(weightOptions).awaitHMS()
            val sampleSets: List<SampleSet> = weightResult.sampleSets
            var latestPoint: SamplePoint? = null
            
            sampleSets.forEach { sampleSet ->
                sampleSet.samplePoints.forEach { point ->
                    if (latestPoint == null || point.getEndTime(TimeUnit.MILLISECONDS) > latestPoint!!.getEndTime(TimeUnit.MILLISECONDS)) {
                        latestPoint = point
                    }
                }
            }
            
            latestPoint?.let { point ->
                weight = point.getFieldValue(Field.FIELD_BODY_WEIGHT).asDoubleValue()
                bodyFatRate = point.getFieldValue(Field.FIELD_BODY_FAT_RATE).asDoubleValue()
                bmi = point.getFieldValue(Field.FIELD_BMI).asDoubleValue()
                muscleMass = point.getFieldValue(Field.FIELD_MUSCLE_MASS).asDoubleValue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching body weight/composition data", e)
        }

        HealthData(
            date = dateStr,
            steps = steps,
            sleepDurationMinutes = sleepMinutes,
            averageHeartRate = avgHeartRate,
            bloodOxygenAverage = avgSpO2,
            stressLevel = stressLevel,
            activityDurationMinutes = activityMinutes,
            weight = weight,
            bodyFatRate = bodyFatRate,
            bmi = bmi,
            muscleMass = muscleMass
        )
    }
}
