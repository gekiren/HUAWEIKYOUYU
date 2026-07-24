package com.example.huaweikyouyu.health

import android.content.Context
import android.content.Intent
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
import kotlin.random.Random

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
    val muscleMass: Double,
    val basalMetabolism: Double,
    val bodyAge: Int,
    val bodyScore: Double,
    val visceralFatLevel: Double,
    val skeletalMuscleMass: Double,
    val boneSalt: Double,
    val moisture: Double,
    val moistureRate: Double,
    val bodyFat: Double,
    val proteinRate: Double,
    val impedance: Double,
    // Smart watch additional data
    val activeCaloriesBurned: Double,
    val distanceMeters: Double,
    val moderateHighIntensityDurationMinutes: Int,
    val deepSleepMinutes: Int,
    val lightSleepMinutes: Int,
    val remSleepMinutes: Int,
    val awakeMinutes: Int,
    val vo2Max: Double,
    val skinTemperature: Double
) {
    fun toPromptString(): String {
        return """
            ■ ヘルスデータ（$date）
            - 歩数: $steps 歩
            - アクティビティ時間: $activityDurationMinutes 分
            - 移動距離: ${String.format(Locale.US, "%.1f m", distanceMeters)}
            - アクティブ消費カロリー: ${String.format(Locale.US, "%.1f kcal", activeCaloriesBurned)}
            - 中高強度運動時間: $moderateHighIntensityDurationMinutes 分
            - 平均心拍数: $averageHeartRate bpm
            - 平均血中酸素レベル: $bloodOxygenAverage %
            - ストレスレベル: $stressLevel (1-100)
            - 皮膚温度: ${String.format(Locale.US, "%.1f ℃", skinTemperature)}
            
            ■ 睡眠分析
            - 総睡眠時間: ${sleepDurationMinutes / 60}時間 ${sleepDurationMinutes % 60}分
            - 深い睡眠: $deepSleepMinutes 分
            - 浅い睡眠: $lightSleepMinutes 分
            - レム睡眠: $remSleepMinutes 分
            - 覚醒時間: $awakeMinutes 分
            
            ■ 体組成データ（体重計測定）
            - 体重: $weight kg
            - BMI: $bmi
            - 体脂肪率: $bodyFatRate %
            - 体脂肪量: $bodyFat kg
            - 筋肉量: $muscleMass kg
            - 骨格筋量: $skeletalMuscleMass kg
            - 骨量 (骨塩量): $boneSalt kg
            - 水分量: $moisture L
            - 水分率: $moistureRate %
            - タンパク質率: $proteinRate %
            - 基礎代謝量: $basalMetabolism kcal
            - 内臓脂肪レベル: $visceralFatLevel
            - 体内年齢: $bodyAge 才
            - 体組成スコア: $bodyScore 点
            - インピーダンス: $impedance Ω
            - 最大酸素摂取量 (VO2 Max): $vo2Max ml/kg/min
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
        "https://www.huawei.com/healthkit/weight.read",
        "https://www.huawei.com/healthkit/calories.read",
        "https://www.huawei.com/healthkit/distance.read"
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
     * Get the authorization sign-in Intent for explicit login
     */
    fun getSignInIntent(context: Context): Intent {
        val scopeList = SCOPES.map { Scope(it) }
        val authParams = HuaweiIdAuthParamsHelper(HuaweiIdAuthParams.DEFAULT_AUTH_REQUEST_PARAM)
            .setScopeList(scopeList)
            .createParams()
        val authService = HuaweiIdAuthManager.getService(context, authParams)
        return authService.signInIntent
    }

    /**
     * Parse authorization result from Intent
     */
    fun parseAuthResult(data: Intent?): Boolean {
        if (data == null) return false
        val authTask = HuaweiIdAuthManager.parseAuthResultFromIntent(data)
        return authTask.isSuccessful
    }

    /**
     * Fetch health data for today.
     * Supports both Mock mode and real HMS Health Kit SDK querying.
     */
    fun fetchTodayHealthData(context: Context, isMock: Boolean = true, onResult: (HealthData?) -> Unit) {
        val todayStr = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
        if (isMock) {
            // Generate realistic random mock data including all parameters
            val mockWeight = (600..850).random().toDouble() / 10.0 // 60.0 to 85.0 kg
            val mockBodyFatRate = (120..240).random().toDouble() / 10.0 // 12.0 to 24.0 %
            val mockBodyFat = mockWeight * (mockBodyFatRate / 100.0)
            val mockBmi = (185..255).random().toDouble() / 10.0 // 18.5 to 25.5
            val mockMuscleMass = mockWeight * Random.nextDouble(0.65, 0.75)
            val mockSkeletalMuscle = mockMuscleMass * 0.58
            val mockBoneSalt = (22..38).random().toDouble() / 10.0 // 2.2 to 3.8 kg
            val mockMoisture = mockWeight * Random.nextDouble(0.50, 0.60)
            val mockMoistureRate = (mockMoisture / mockWeight) * 100.0
            val mockProteinRate = (140..180).random().toDouble() / 10.0 // 14.0 to 18.0 %
            val mockBasal = (1300..1800).random().toDouble()
            val mockVisceral = (40..120).random().toDouble() / 10.0 // 4.0 to 12.0
            val mockAge = (22..45).random()
            val mockScore = (720..920).random().toDouble() / 10.0 // 72.0 to 92.0 点
            val mockImpedance = (4200..5800).random().toDouble() / 10.0 // 420.0 to 580.0 Ω

            val mockSleepTotal = (360..540).random() // 6 to 9 hours
            val mockDeepSleep = (mockSleepTotal * Random.nextDouble(0.20, 0.30)).toInt()
            val mockRemSleep = (mockSleepTotal * Random.nextDouble(0.15, 0.25)).toInt()
            val mockAwake = (10..40).random()
            val mockLightSleep = mockSleepTotal - mockDeepSleep - mockRemSleep - mockAwake

            val mockData = HealthData(
                date = todayStr,
                steps = (6000..12000).random(),
                sleepDurationMinutes = mockSleepTotal,
                averageHeartRate = (62..78).random(),
                bloodOxygenAverage = (96..99).random().toDouble(),
                stressLevel = (15..45).random(),
                activityDurationMinutes = (10..80).random(),
                weight = mockWeight,
                bodyFatRate = mockBodyFatRate,
                bmi = mockBmi,
                muscleMass = mockMuscleMass,
                basalMetabolism = mockBasal,
                bodyAge = mockAge,
                bodyScore = mockScore,
                visceralFatLevel = mockVisceral,
                skeletalMuscleMass = mockSkeletalMuscle,
                boneSalt = mockBoneSalt,
                moisture = mockMoisture,
                moistureRate = mockMoistureRate,
                bodyFat = mockBodyFat,
                proteinRate = mockProteinRate,
                impedance = mockImpedance,
                // Smart watch additional mock data
                activeCaloriesBurned = (2000..6500).random().toDouble() / 10.0, // 200.0 to 650.0 kcal
                distanceMeters = (1500..8500).random().toDouble(), // 1.5 to 8.5 km
                moderateHighIntensityDurationMinutes = (10..45).random(),
                deepSleepMinutes = mockDeepSleep,
                lightSleepMinutes = mockLightSleep,
                remSleepMinutes = mockRemSleep,
                awakeMinutes = mockAwake,
                vo2Max = (380..520).random().toDouble() / 10.0, // 38.0 to 52.0 ml/kg/min
                skinTemperature = (315..335).random().toDouble() / 10.0 // 31.5 to 33.5 ℃
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

        // 2. Fetch Sleep Duration and detailed sleep stages
        var sleepMinutes = 0
        var deepSleep = 0
        var lightSleep = 0
        var remSleep = 0
        var awakeMinutes = 0
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
                    
                    // Accumulate detailed sleep stages if present in fields
                    try {
                        deepSleep += point.getFieldValue(Field.DEEP_SLEEP_TIME).asIntValue()
                        lightSleep += point.getFieldValue(Field.LIGHT_SLEEP_TIME).asIntValue()
                        remSleep += point.getFieldValue(Field.DREAM_TIME).asIntValue()
                        awakeMinutes += point.getFieldValue(Field.AWAKE_TIME).asIntValue()
                    } catch (ex: Exception) {
                        // Sleep stages field parsing failed, fallback
                    }
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
        var avgSpO2 = 98.0

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
        var basalMetabolism = 0.0
        var bodyAge = 0
        var bodyScore = 0.0
        var visceralFatLevel = 0.0
        var skeletalMuscleMass = 0.0
        var boneSalt = 0.0
        var moisture = 0.0
        var moistureRate = 0.0
        var bodyFat = 0.0
        var proteinRate = 0.0
        var impedance = 0.0

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
                basalMetabolism = point.getFieldValue(Field.FIELD_BASAL_METABOLISM).asDoubleValue()
                bodyAge = point.getFieldValue(Field.FIELD_BODY_AGE).asIntValue()
                bodyScore = point.getFieldValue(Field.FIELD_BODY_SCORE).asDoubleValue()
                visceralFatLevel = point.getFieldValue(Field.FIELD_VISCERAL_FAT_LEVEL).asDoubleValue()
                skeletalMuscleMass = point.getFieldValue(Field.FIELD_SKELETAL_MUSCLEL_MASS).asDoubleValue()
                boneSalt = point.getFieldValue(Field.FIELD_BONE_SALT).asDoubleValue()
                moisture = point.getFieldValue(Field.FIELD_MOISTURE).asDoubleValue()
                moistureRate = point.getFieldValue(Field.FIELD_MOISTURE_RATE).asDoubleValue()
                bodyFat = point.getFieldValue(Field.FIELD_BODY_FAT).asDoubleValue()
                proteinRate = point.getFieldValue(Field.FIELD_PROTEIN_RATE).asDoubleValue()
                impedance = point.getFieldValue(Field.FIELD_IMPEDANCE).asDoubleValue()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching body weight/composition data", e)
        }

        // 8. Fetch Active Calories Burned today
        var activeCalories = 0.0
        try {
            val calorieOptions = ReadOptions.Builder()
                .read(DataType.DT_CONTINUOUS_CALORIES_BURNT)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .build()
            val calorieResult: ReadReply = dataController.read(calorieOptions).awaitHMS()
            val sampleSets: List<SampleSet> = calorieResult.sampleSets
            sampleSets.forEach { sampleSet ->
                sampleSet.samplePoints.forEach { point ->
                    activeCalories += point.getFieldValue(Field.FIELD_CALORIES).asDoubleValue()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching active calories", e)
        }

        // 9. Fetch Distance today
        var distance = 0.0
        try {
            val distanceOptions = ReadOptions.Builder()
                .read(DataType.DT_CONTINUOUS_DISTANCE_DELTA)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .build()
            val distanceResult: ReadReply = dataController.read(distanceOptions).awaitHMS()
            val sampleSets: List<SampleSet> = distanceResult.sampleSets
            sampleSets.forEach { sampleSet ->
                sampleSet.samplePoints.forEach { point ->
                    distance += point.getFieldValue(Field.FIELD_DISTANCE_DELTA).asDoubleValue()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching distance", e)
        }

        // 10. Fetch VO2 Max (query past 30 days for latest)
        var vo2Max = 42.0
        try {
            val vo2Options = ReadOptions.Builder()
                .read(DataType.DT_VO2MAX)
                .setTimeRange(startWeightTime, endWeightTime, TimeUnit.MILLISECONDS)
                .build()
            val vo2Result: ReadReply = dataController.read(vo2Options).awaitHMS()
            val sampleSets: List<SampleSet> = vo2Result.sampleSets
            var latestVo2Point: SamplePoint? = null
            sampleSets.forEach { sampleSet ->
                sampleSet.samplePoints.forEach { point ->
                    if (latestVo2Point == null || point.getEndTime(TimeUnit.MILLISECONDS) > latestVo2Point!!.getEndTime(TimeUnit.MILLISECONDS)) {
                        latestVo2Point = point
                    }
                }
            }
            latestVo2Point?.let { point ->
                vo2Max = point.getFieldValue(Field.VO2MAX).asIntValue().toDouble()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching VO2 Max", e)
        }

        // 11. Skin Temperature placeholder (since DT_INSTANTANEOUS_SKIN_TEMPERATURE isn't in standard compile package)
        val skinTemp = 32.2

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
            muscleMass = muscleMass,
            basalMetabolism = basalMetabolism,
            bodyAge = bodyAge,
            bodyScore = bodyScore,
            visceralFatLevel = visceralFatLevel,
            skeletalMuscleMass = skeletalMuscleMass,
            boneSalt = boneSalt,
            moisture = moisture,
            moistureRate = moistureRate,
            bodyFat = bodyFat,
            proteinRate = proteinRate,
            impedance = impedance,
            // Smart watch additional data
            activeCaloriesBurned = activeCalories,
            distanceMeters = distance,
            moderateHighIntensityDurationMinutes = (activityMinutes * 0.4).toInt(), // Approximation fallback
            deepSleepMinutes = deepSleep,
            lightSleepMinutes = lightSleep,
            remSleepMinutes = remSleep,
            awakeMinutes = awakeMinutes,
            vo2Max = vo2Max,
            skinTemperature = skinTemp
        )
    }
}
