package com.fit_up.health.capacitor

import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.activity.result.ActivityResultCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContract
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.aggregate.AggregateMetric
import androidx.health.connect.client.aggregate.AggregationResult
import androidx.health.connect.client.aggregate.AggregationResultGroupedByPeriod
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.getcapacitor.annotation.Permission
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDateTime
import java.time.Period
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Optional
import java.util.concurrent.atomic.AtomicReference
import kotlin.jvm.optionals.getOrDefault
import androidx.health.connect.client.records.SleepSessionRecord

enum class CapHealthPermission {
    READ_STEPS, READ_WORKOUTS, READ_HEART_RATE, READ_ROUTE, READ_ACTIVE_CALORIES, READ_TOTAL_CALORIES, READ_DISTANCE, READ_SLEEP;

    companion object {
        fun from(s: String): CapHealthPermission? {
            return try {
                CapHealthPermission.valueOf(s)
            } catch (e: Exception) {
                null
            }
        }
    }
}


@CapacitorPlugin(
    name = "HealthPlugin",
    permissions = [
        Permission(
            alias = "READ_STEPS",
            strings = ["android.permission.health.READ_STEPS"]
        ),
        Permission(
            alias = "READ_WORKOUTS",
            strings = ["android.permission.health.READ_EXERCISE"]
        ),
        Permission(
            alias = "READ_DISTANCE",
            strings = ["android.permission.health.READ_DISTANCE"]
        ),
        Permission(
            alias = "READ_ACTIVE_CALORIES",
            strings = ["android.permission.health.READ_ACTIVE_CALORIES_BURNED"]
        ),
        Permission(
            alias = "READ_TOTAL_CALORIES",
            strings = ["android.permission.health.READ_TOTAL_CALORIES_BURNED"]
        ),
        Permission(
            alias = "READ_HEART_RATE",
            strings = ["android.permission.health.READ_HEART_RATE"]
        ),
        Permission(
            alias = "READ_ROUTE",
            strings = ["android.permission.health.READ_EXERCISE_ROUTE"]
        ),
        Permission(
            alias = "READ_SLEEP",
            strings = ["android.permission.health.READ_SLEEP"]
        )
    ]
)
class HealthPlugin : Plugin() {


    private val tag = "CapHealth"

    private lateinit var healthConnectClient: HealthConnectClient
    private var available: Boolean = false

    private lateinit var permissionsLauncher: ActivityResultLauncher<Set<String>>
    override fun load() {
        super.load()

        val contract: ActivityResultContract<Set<String>, Set<String>> =
            PermissionController.createRequestPermissionResultContract()

        val callback: ActivityResultCallback<Set<String>> = ActivityResultCallback { grantedPermissions ->
            val context = requestPermissionContext.get()
            if (context != null) {
                val result = grantedPermissionResult(context.requestedPermissions, grantedPermissions)
                context.pluginCal.resolve(result)
            }
        }
        permissionsLauncher = activity.registerForActivityResult(contract, callback)
    }

    // Check if Google Health Connect is available. Must be called before anything else
    @PluginMethod
    fun isHealthAvailable(call: PluginCall) {

        if (!available) {
            try {
                healthConnectClient = HealthConnectClient.getOrCreate(context)
                available = true
            } catch (e: Exception) {
                Log.e("CAP-HEALTH", "error health connect client", e)
                available = false
            }
        }


        val result = JSObject()
        result.put("available", available)
        call.resolve(result)
    }


    private val permissionMapping = mapOf(
        Pair(CapHealthPermission.READ_WORKOUTS, "android.permission.health.READ_EXERCISE"),
        Pair(CapHealthPermission.READ_ROUTE, "android.permission.health.READ_EXERCISE_ROUTE"),
        Pair(CapHealthPermission.READ_HEART_RATE, "android.permission.health.READ_HEART_RATE"),
        Pair(CapHealthPermission.READ_ACTIVE_CALORIES, "android.permission.health.READ_ACTIVE_CALORIES_BURNED"),
        Pair(CapHealthPermission.READ_TOTAL_CALORIES, "android.permission.health.READ_TOTAL_CALORIES_BURNED"),
        Pair(CapHealthPermission.READ_DISTANCE, "android.permission.health.READ_DISTANCE"),
        Pair(CapHealthPermission.READ_STEPS, "android.permission.health.READ_STEPS"),
        Pair(CapHealthPermission.READ_SLEEP, "android.permission.health.READ_SLEEP")
    )

    // Check if a set of permissions are granted
    @PluginMethod
    fun checkHealthPermissions(call: PluginCall) {
        val permissionsToCheck = call.getArray("permissions")
        if (permissionsToCheck == null) {
            call.reject("Must provide permissions to check")
            return
        }


        val permissions =
            permissionsToCheck.toList<String>().mapNotNull { CapHealthPermission.from(it) }.toSet()


        CoroutineScope(Dispatchers.IO).launch {
            try {

                val grantedPermissions = healthConnectClient.permissionController.getGrantedPermissions()
                val result = grantedPermissionResult(permissions, grantedPermissions)

                call.resolve(result)
            } catch (e: Exception) {
                call.reject("Checking permissions failed: ${e.message}")
            }
        }
    }

    private fun grantedPermissionResult(requestPermissions: Set<CapHealthPermission>, grantedPermissions: Set<String>): JSObject {
        val readPermissions = JSObject()
        val grantedPermissionsWithoutPrefix = grantedPermissions.map { it.substringAfterLast('.') }
        for (permission in requestPermissions) {

            readPermissions.put(
                permission.name,
                grantedPermissionsWithoutPrefix.contains(permissionMapping[permission]?.substringAfterLast('.'))
            )
        }

        val result = JSObject()
        result.put("permissions", readPermissions)
        return result

    }

    data class RequestPermissionContext(val requestedPermissions: Set<CapHealthPermission>, val pluginCal: PluginCall)

    private val requestPermissionContext = AtomicReference<RequestPermissionContext>()

    // Request a set of permissions from the user
    @PluginMethod
    fun requestHealthPermissions(call: PluginCall) {
        val permissionsToRequest = call.getArray("permissions")
        if (permissionsToRequest == null) {
            call.reject("Must provide permissions to request")
            return
        }

        val permissions = permissionsToRequest.toList<String>().mapNotNull { CapHealthPermission.from(it) }.toSet()
        val healthConnectPermissions = permissions.mapNotNull { permissionMapping[it] }.toSet()


        CoroutineScope(Dispatchers.IO).launch {
            try {
                requestPermissionContext.set(RequestPermissionContext(permissions, call))
                permissionsLauncher.launch(healthConnectPermissions)
            } catch (e: Exception) {
                call.reject("Permission request failed: ${e.message}")
                requestPermissionContext.set(null)
            }
        }
    }

    // Open Google Health Connect app settings
    @PluginMethod
    fun openHealthConnectSettings(call: PluginCall) {
        try {
            val intent = Intent().apply {
                action = HealthConnectClient.ACTION_HEALTH_CONNECT_SETTINGS
            }
            context.startActivity(intent)
            call.resolve()
        } catch(e: Exception) {
            call.reject(e.message)
        }
    }

    // Open the Google Play Store to install Health Connect
    @PluginMethod
    fun showHealthConnectInPlayStore(call: PluginCall) {
        val uri =
            Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata")
        val intent = Intent(Intent.ACTION_VIEW, uri)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        call.resolve()
    }

    private fun getMetricAndMapper(dataType: String): MetricAndMapper {
        return when (dataType) {
            "steps" -> metricAndMapper("steps", CapHealthPermission.READ_STEPS, StepsRecord.COUNT_TOTAL) { it?.toDouble() }
            "active-calories" -> metricAndMapper(
                "calories",
                CapHealthPermission.READ_ACTIVE_CALORIES,
                ActiveCaloriesBurnedRecord.ACTIVE_CALORIES_TOTAL
            ) { it?.inKilocalories }
            "total-calories" -> metricAndMapper(
                "calories",
                CapHealthPermission.READ_TOTAL_CALORIES,
                TotalCaloriesBurnedRecord.ENERGY_TOTAL
            ) { it?.inKilocalories }
            "distance" -> metricAndMapper("distance", CapHealthPermission.READ_DISTANCE, DistanceRecord.DISTANCE_TOTAL) { it?.inMeters }
            else -> throw RuntimeException("Unsupported dataType: $dataType")
        }
    }

    @PluginMethod
    fun queryAggregated(call: PluginCall) {
        try {
            val startDate = call.getString("startDate")
            val endDate = call.getString("endDate")
            val dataType = call.getString("dataType")
            val bucket = call.getString("bucket")

            if (startDate == null || endDate == null || dataType == null || bucket == null) {
                call.reject("Missing required parameters: startDate, endDate, dataType, or bucket")
                return
            }

            val startDateTime = Instant.parse(startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
            val endDateTime = Instant.parse(endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()

            val metricAndMapper = getMetricAndMapper(dataType)

            val period = when (bucket) {
                "day" -> Period.ofDays(1)
                else -> throw RuntimeException("Unsupported bucket: $bucket")
            }


            CoroutineScope(Dispatchers.IO).launch {
                try {

                    val r = queryAggregatedMetric(metricAndMapper, TimeRangeFilter.between(startDateTime, endDateTime), period)

                    val aggregatedList = JSArray()
                    r.forEach { aggregatedList.put(it.toJs()) }

                    val finalResult = JSObject()
                    finalResult.put("aggregatedData", aggregatedList)
                    call.resolve(finalResult)

                } catch (e: Exception) {
                    call.reject("Error querying aggregated data: ${e.message}")
                }
            }
        } catch (e: Exception) {
            call.reject(e.message)
            return
        }
    }


    private fun <M : Any> metricAndMapper(
        name: String,
        permission: CapHealthPermission,
        metric: AggregateMetric<M>,
        mapper: (M?) -> Double?
    ): MetricAndMapper {
        @Suppress("UNCHECKED_CAST")
        return MetricAndMapper(name, permission, metric, mapper as (Any?) -> Double?)
    }

    data class MetricAndMapper(
        val name: String,
        val permission: CapHealthPermission,
        val metric: AggregateMetric<Any>,
        val mapper: (Any?) -> Double?
    ) {
        fun getValue(a: AggregationResult): Double? {
            return mapper(a[metric])
        }
    }

    data class AggregatedSample(val startDate: LocalDateTime, val endDate: LocalDateTime, val value: Double?) {
        fun toJs(): JSObject {
            val o = JSObject()
            o.put("startDate", startDate)
            o.put("endDate", endDate)
            o.put("value", value)
            return o

        }
    }

    private suspend fun queryAggregatedMetric(
        metricAndMapper: MetricAndMapper, timeRange: TimeRangeFilter, period: Period,
    ): List<AggregatedSample> {
        if (!hasPermission(metricAndMapper.permission)) {
            return emptyList()
        }

        val response: List<AggregationResultGroupedByPeriod> = healthConnectClient.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(metricAndMapper.metric),
                timeRangeFilter = timeRange,
                timeRangeSlicer = period
            )
        )

        return response.map {
            val mappedValue = metricAndMapper.getValue(it.result)
            AggregatedSample(it.startTime, it.endTime, mappedValue)
        }

    }

    private suspend fun hasPermission(p: CapHealthPermission): Boolean {
        return healthConnectClient.permissionController.getGrantedPermissions().map { it.substringAfterLast('.') }.toSet()
            .contains(permissionMapping[p]?.substringAfterLast('.'))
    }


    @PluginMethod
    fun queryWorkouts(call: PluginCall) {
        val startDate = call.getString("startDate")
        val endDate = call.getString("endDate")
        val includeHeartRate: Boolean = call.getBoolean("includeHeartRate", false) == true
        val includeRoute: Boolean = call.getBoolean("includeRoute", false) == true
        val includeSteps: Boolean = call.getBoolean("includeSteps", false) == true
        if (startDate == null || endDate == null) {
            call.reject("Missing required parameters: startDate or endDate")
            return
        }

        val startDateTime = Instant.parse(startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val endDateTime = Instant.parse(endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()

        val timeRange = TimeRangeFilter.between(startDateTime, endDateTime)
        val request =
            ReadRecordsRequest(ExerciseSessionRecord::class, timeRange, emptySet(), true, 1000)

        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Query workouts (exercise sessions)
                val response = healthConnectClient.readRecords(request)

                val workoutsArray = JSArray()

                for (workout in response.records) {
                    val workoutObject = JSObject()
                    workoutObject.put("id", workout.metadata.id)
                    workoutObject.put(
                        "sourceName",
                        Optional.ofNullable(workout.metadata.device?.model).orElse("")
                    )
                    workoutObject.put("sourceBundleId", workout.metadata.dataOrigin.packageName)
                    workoutObject.put("deviceManufacturer", workout.metadata.device?.manufacturer ?: "")
                    workoutObject.put("startDate", workout.startTime.toString())
                    workoutObject.put("endDate", workout.endTime.toString())
                    workoutObject.put("workoutType", exerciseTypeMapping.getOrDefault(workout.exerciseType, "OTHER"))
                    workoutObject.put("title", workout.title)
                    val duration = if (workout.segments.isEmpty()) {
                        workout.endTime.epochSecond - workout.startTime.epochSecond
                    } else {
                        workout.segments.map { it.endTime.epochSecond - it.startTime.epochSecond }
                            .stream().mapToLong { it }.sum()
                    }
                    workoutObject.put("duration", duration)

                    if (includeSteps) {
                        addWorkoutMetric(workout, workoutObject, getMetricAndMapper("steps"))
                    }

                    val readTotalCaloriesResult = addWorkoutMetric(workout, workoutObject, getMetricAndMapper("total-calories"))
                    if(!readTotalCaloriesResult) {
                        addWorkoutMetric(workout, workoutObject, getMetricAndMapper("active-calories"))
                    }

                    addWorkoutMetric(workout, workoutObject, getMetricAndMapper("distance"))

                    if (includeHeartRate && hasPermission(CapHealthPermission.READ_HEART_RATE)) {
                        // Query and add heart rate data if requested
                        val heartRates =
                            queryHeartRateForWorkout(workout.startTime, workout.endTime)
                        workoutObject.put("heartRate", heartRates)
                    }

                    if (includeRoute && workout.exerciseRouteResult is ExerciseRouteResult.Data) {
                        val route =
                            queryRouteForWorkout(workout.exerciseRouteResult as ExerciseRouteResult.Data)
                        workoutObject.put("route", route)
                    }

                    workoutsArray.put(workoutObject)
                }

                val result = JSObject()
                result.put("workouts", workoutsArray)
                call.resolve(result)

            } catch (e: Exception) {
                call.reject("Error querying workouts: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun queryHeartRate(call: PluginCall) {
    val startDate = call.getString("startDate")
    val endDate   = call.getString("endDate")
    if (startDate == null || endDate == null) {
        call.reject("Missing required parameters: startDate or endDate")
        return
    }

    val startDateTime = Instant.parse(startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
    val endDateTime   =  Instant.parse(endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()

    CoroutineScope(Dispatchers.IO).launch {
        try {
            if (!hasPermission(CapHealthPermission.READ_HEART_RATE)) {
                call.reject("Heart rate permission not granted")
                return@launch
            }

            val req = ReadRecordsRequest(
                HeartRateRecord::class,
                TimeRangeFilter.between(startDateTime, endDateTime)
            )
            val result = healthConnectClient.readRecords(req)
            val samples = JSArray()

            for (record in result.records) {
                val metaId  = record.metadata.id
                val source  = record.metadata.dataOrigin.packageName
                val model   = record.metadata.device?.model ?: ""
                val maker   = record.metadata.device?.manufacturer ?: ""

                record.samples.forEachIndexed { idx, sample ->
                    val obj = JSObject()
                    obj.put("id", "${metaId}_$idx")           
                    obj.put("startTime", sample.time.toString())
                    obj.put("endTime",   sample.time.toString()) 
                    obj.put("bpm", sample.beatsPerMinute)

                    obj.put("sourceBundleId", source)
                    obj.put("sourceName",     model)
                    obj.put("deviceManufacturer", maker)

                    samples.put(obj)
                }
            }

            call.resolve(JSObject().apply {
                put("heartRateRecords", samples)
            })

        } catch (e: Exception) {
            call.reject("Error querying heart-rate data: ${e.message}")
        }
    }
}


    @PluginMethod
    fun querySleep(call: PluginCall) {
        val startDate = call.getString("startDate")
        val endDate = call.getString("endDate")
        
        if (startDate == null || endDate == null) {
            call.reject("Missing required parameters: startDate or endDate")
            return
        }

        val startDateTime = Instant.parse(startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val endDateTime = Instant.parse(endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!hasPermission(CapHealthPermission.READ_SLEEP)) {
                    call.reject("Sleep permission not granted")
                    return@launch
                }

                val request = ReadRecordsRequest(
                    SleepSessionRecord::class, 
                    TimeRangeFilter.between(startDateTime, endDateTime)
                )
                val sleepRecords = healthConnectClient.readRecords(request)
                val sleepArray = JSArray()
                
                for ((sessionIndex, record) in sleepRecords.records.withIndex()) {
                    val sessionId = record.metadata.id
                    record.stages.forEachIndexed { stageIndex, stage ->

                        val segObj = JSObject()
                        segObj.put("id", "${sessionId}_${stageIndex}")
                        segObj.put("sessionId", sessionId)

                        segObj.put("startDate", stage.startTime.toString())
                        segObj.put("endDate",   stage.endTime.toString())

                        val durationMin = Math.ceil((stage.endTime.epochSecond - stage.startTime.epochSecond) / 60.0).toInt()
                        segObj.put("duration", durationMin)

                        val mapStage = when (stage.stage) {
                            SleepSessionRecord.STAGE_TYPE_AWAKE -> "AWAKE"
                            SleepSessionRecord.STAGE_TYPE_AWAKE_IN_BED -> "IN_BED"
                            SleepSessionRecord.STAGE_TYPE_DEEP -> "DEEP"
                            SleepSessionRecord.STAGE_TYPE_LIGHT -> "LIGHT"
                            SleepSessionRecord.STAGE_TYPE_OUT_OF_BED -> "OUT_OF_BED"
                            SleepSessionRecord.STAGE_TYPE_SLEEPING -> "SLEEPING"
                            else -> "UNKNOWN"
                        }

                        segObj.put("sleepStage", mapStage)
                        segObj.put("sourceBundleId", record.metadata.dataOrigin.packageName)
                        segObj.put("sourceName",     record.metadata.device?.model ?: "")
                        segObj.put("deviceManufacturer", record.metadata.device?.manufacturer ?: "")
                        sleepArray.put(segObj)
                    }
                }


                val result = JSObject()
                result.put("sleep", sleepArray)
                call.resolve(result)

            } catch (e: Exception) {
                call.reject("Error querying sleeps: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun querySteps(call: PluginCall) {
        val startDate = call.getString("startDate")
        val endDate = call.getString("endDate")
        val bucket = call.getString("bucket")
        
        if (startDate == null || endDate == null) {
            call.reject("Missing required parameters: startDate or endDate")
            return
        }

        val startDateTime = Instant.parse(startDate).atZone(ZoneId.systemDefault()).toLocalDateTime()
        val endDateTime = Instant.parse(endDate).atZone(ZoneId.systemDefault()).toLocalDateTime()

        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!hasPermission(CapHealthPermission.READ_STEPS)) {
                    call.reject("Steps permission not granted")
                    return@launch
                }

                val result = JSObject()
                
                if (bucket != null) {
                    val period = when (bucket) {
                        "day" -> Period.ofDays(1)
                        else -> throw RuntimeException("Unsupported bucket: $bucket")
                    }
                    
                    val aggregatedList = JSArray()
                    
                    val stepsMetric = getMetricAndMapper("steps")
                    val stepsAggregated = queryAggregatedMetric(stepsMetric, TimeRangeFilter.between(startDateTime, endDateTime), period)
   
                    val request = ReadRecordsRequest(
                        StepsRecord::class, 
                        TimeRangeFilter.between(startDateTime, endDateTime)
                    )
                    val stepRecords = healthConnectClient.readRecords(request)
    
                    val metadataMap = mutableMapOf<String, MutableList<StepMetadata>>()
                    
                    for (record in stepRecords.records) {
                        val recordStart = record.startTime.atZone(ZoneId.systemDefault()).toLocalDateTime()
                        val recordEnd = record.endTime.atZone(ZoneId.systemDefault()).toLocalDateTime()
                        
                        for (aggregated in stepsAggregated) {
                            if (recordStart < aggregated.endDate && recordEnd > aggregated.startDate) {
                                val key = "${aggregated.startDate}_${aggregated.endDate}"
                                if (!metadataMap.containsKey(key)) {
                                    metadataMap[key] = mutableListOf()
                                }
                                metadataMap[key]?.add(StepMetadata(
                                    record.metadata.device?.model ?: "",
                                    record.metadata.dataOrigin.packageName,
                                    record.metadata.device?.manufacturer ?: ""
                                ))
                            }
                        }
                    }
                    
                    for (aggregated in stepsAggregated) {
                        val key = "${aggregated.startDate}_${aggregated.endDate}"
                        val metadata = metadataMap[key]?.firstOrNull() ?: StepMetadata("", "", "")
                        
                        val aggregatedObject = JSObject()
                        aggregatedObject.put("startDate", aggregated.startDate)
                        aggregatedObject.put("endDate", aggregated.endDate)
                        aggregatedObject.put("value", aggregated.value)
                        val durationMinutes = (aggregated.endDate.toInstant(ZoneOffset.UTC).epochSecond - 
                                              aggregated.startDate.toInstant(ZoneOffset.UTC).epochSecond) / 60.0
                        aggregatedObject.put("duration", Math.ceil(durationMinutes).toInt())
                        aggregatedObject.put("sourceName", metadata.sourceName)
                        aggregatedObject.put("sourceBundleId", metadata.sourceBundleId)
                        aggregatedObject.put("deviceManufacturer", metadata.deviceManufacturer)
                        aggregatedList.put(aggregatedObject)
                    }
                    
                    result.put("aggregatedData", aggregatedList)
                } 
                
                call.resolve(result)
            } catch (e: Exception) {
                call.reject("Error querying step data: ${e.message}")
            }
        }
    }

    data class StepMetadata(
        val sourceName: String,
        val sourceBundleId: String,
        val deviceManufacturer: String
    )

    private suspend fun queryHeartRateForWorkout(startTime: Instant, endTime: Instant): JSArray {
        val request =
            ReadRecordsRequest(HeartRateRecord::class, TimeRangeFilter.between(startTime, endTime))
        val heartRateRecords = healthConnectClient.readRecords(request)

        val heartRateArray = JSArray()
        val samples = heartRateRecords.records.flatMap { it.samples }
        for (sample in samples) {
            val heartRateObject = JSObject()
            heartRateObject.put("timestamp", sample.time.toString())
            heartRateObject.put("bpm", sample.beatsPerMinute)
            heartRateArray.put(heartRateObject)
        }
        return heartRateArray
    }

    private fun queryRouteForWorkout(routeResult: ExerciseRouteResult.Data): JSArray {

        val routeArray = JSArray()
        for (record in routeResult.exerciseRoute.route) {
            val routeObject = JSObject()
            routeObject.put("timestamp", record.time.toString())
            routeObject.put("lat", record.latitude)
            routeObject.put("lng", record.longitude)
            routeObject.put("alt", record.altitude)
            routeArray.put(routeObject)
        }
        return routeArray
    }


    private val exerciseTypeMapping = mapOf(
        0 to "OTHER",
        2 to "BADMINTON",
        4 to "BASEBALL",
        5 to "BASKETBALL",
        8 to "BIKING",
        9 to "BIKING_STATIONARY",
        10 to "BOOT_CAMP",
        11 to "BOXING",
        13 to "CALISTHENICS",
        14 to "CRICKET",
        16 to "DANCING",
        25 to "ELLIPTICAL",
        26 to "EXERCISE_CLASS",
        27 to "FENCING",
        28 to "FOOTBALL_AMERICAN",
        29 to "FOOTBALL_AUSTRALIAN",
        31 to "FRISBEE_DISC",
        32 to "GOLF",
        33 to "GUIDED_BREATHING",
        34 to "GYMNASTICS",
        35 to "HANDBALL",
        36 to "HIGH_INTENSITY_INTERVAL_TRAINING",
        37 to "HIKING",
        38 to "ICE_HOCKEY",
        39 to "ICE_SKATING",
        44 to "MARTIAL_ARTS",
        46 to "PADDLING",
        47 to "PARAGLIDING",
        48 to "PILATES",
        50 to "RACQUETBALL",
        51 to "ROCK_CLIMBING",
        52 to "ROLLER_HOCKEY",
        53 to "ROWING",
        54 to "ROWING_MACHINE",
        55 to "RUGBY",
        56 to "RUNNING",
        57 to "RUNNING_TREADMILL",
        58 to "SAILING",
        59 to "SCUBA_DIVING",
        60 to "SKATING",
        61 to "SKIING",
        62 to "SNOWBOARDING",
        63 to "SNOWSHOEING",
        64 to "SOCCER",
        65 to "SOFTBALL",
        66 to "SQUASH",
        68 to "STAIR_CLIMBING",
        69 to "STAIR_CLIMBING_MACHINE",
        70 to "STRENGTH_TRAINING",
        71 to "STRETCHING",
        72 to "SURFING",
        73 to "SWIMMING_OPEN_WATER",
        74 to "SWIMMING_POOL",
        75 to "TABLE_TENNIS",
        76 to "TENNIS",
        78 to "VOLLEYBALL",
        79 to "WALKING",
        80 to "WATER_POLO",
        81 to "WEIGHTLIFTING",
        82 to "WHEELCHAIR",
        83 to "YOGA"
    )

    private val sleepTypeMapping = mapOf(
        0 to "UNKNOWN",
        1 to "AWAKE",
        2 to "LIGHT",
        3 to "DEEP",
        4 to "REM"
    )

    private suspend fun addWorkoutMetric(
        workout: ExerciseSessionRecord,
        jsWorkout: JSObject,
        metricAndMapper: MetricAndMapper,
    ): Boolean {

        if (hasPermission(metricAndMapper.permission)) {
            try {
                val request = AggregateRequest(
                    setOf(metricAndMapper.metric),
                    TimeRangeFilter.Companion.between(workout.startTime, workout.endTime),
                    emptySet()
                )
                val aggregation = healthConnectClient.aggregate(request)
                val value = metricAndMapper.getValue(aggregation)
                if(value != null) {
                    jsWorkout.put(metricAndMapper.name, value)
                    return true
                }
            } catch (e: Exception) {
                Log.e(tag, "Error", e)
            }
        }
        return false;
    }

}

