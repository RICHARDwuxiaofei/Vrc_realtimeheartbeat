package best.nagikokoro.watch6heartrateprobe

import android.content.Context
import android.os.SystemClock
import androidx.health.services.client.HealthServices
import androidx.health.services.client.MeasureCallback
import androidx.health.services.client.MeasureClient
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.DataTypeAvailability
import androidx.health.services.client.data.DeltaDataType
import androidx.health.services.client.getCapabilities
import androidx.health.services.client.unregisterMeasureCallback
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class HeartRateMeasureManager(
    context: Context,
    private val logger: DiagnosticLogger,
    private val listener: Listener,
) {
    interface Listener {
        fun onHealthServicesAvailability(available: Boolean)
        fun onHeartRateSupport(supported: Boolean)
        fun onCallbackRegistrationChanged(registered: Boolean)
        fun onAvailabilityChanged(availability: DataTypeAvailability)
        fun onValidSample(rawBpm: Double, displayedBpm: Int, sampleTimeMillis: Long)
        fun onInvalidSamples(count: Int, reason: String)
        fun onMeasurementFailure(eventCode: String, message: String, throwable: Throwable)
    }

    private val appContext = context.applicationContext
    private val registered = AtomicBoolean(false)
    private var measureClient: MeasureClient? = null

    private val callback = object : MeasureCallback {
        override fun onAvailabilityChanged(
            dataType: DeltaDataType<*, *>,
            availability: Availability,
        ) {
            try {
                logger.info(
                    "AVAILABILITY_CHANGED",
                    "Measure availability changed",
                    mapOf(
                        "dataType" to dataType.name,
                        "availability" to availability.toString(),
                        "availabilityId" to availability.id,
                    ),
                )
                if (availability is DataTypeAvailability) {
                    listener.onAvailabilityChanged(availability)
                }
            } catch (callbackFailure: Throwable) {
                logger.error(
                    "AVAILABILITY_CALLBACK_FAILED",
                    "Availability callback processing failed",
                    callbackFailure,
                )
                listener.onMeasurementFailure(
                    "E_CALLBACK_AVAILABILITY",
                    "处理传感器可用性回调失败",
                    callbackFailure,
                )
            }
        }

        override fun onDataReceived(data: DataPointContainer) {
            try {
                val points = data.getData(DataType.HEART_RATE_BPM)
                if (points.isEmpty()) {
                    logger.warn(
                        "HR_DATA_EMPTY",
                        "Health Services callback contained no HEART_RATE_BPM points",
                        mapOf("containerTypes" to data.dataTypes.joinToString { it.name }),
                    )
                    listener.onInvalidSamples(1, "EMPTY_HEART_RATE_CONTAINER")
                    return
                }

                points.forEach { point ->
                    val rawValue = point.value
                    val sampleTimeMillis = bootEpochMillis() + point.timeDurationFromBoot.toMillis()
                    logger.debug(
                        "HR_DATA_POINT",
                        "Raw heart-rate data point received",
                        mapOf(
                            "rawDouble" to rawValue,
                            "timeDurationFromBootMs" to point.timeDurationFromBoot.toMillis(),
                            "sampleEpochMillis" to sampleTimeMillis,
                            "accuracy" to point.accuracy,
                        ),
                    )
                    if (!rawValue.isFinite() || rawValue !in MIN_VALID_BPM..MAX_VALID_BPM) {
                        logger.warn(
                            "HR_SAMPLE_INVALID",
                            "Heart-rate sample is outside the diagnostic validity range",
                            mapOf("rawDouble" to rawValue, "sampleEpochMillis" to sampleTimeMillis),
                        )
                        listener.onInvalidSamples(1, "INVALID_VALUE=$rawValue")
                    } else {
                        val displayed = rawValue.roundToInt()
                        logger.info(
                            "HR_SAMPLE_VALID",
                            "Valid heart-rate sample accepted",
                            mapOf(
                                "rawDouble" to rawValue,
                                "displayedBpm" to displayed,
                                "sampleEpochMillis" to sampleTimeMillis,
                            ),
                        )
                        listener.onValidSample(rawValue, displayed, sampleTimeMillis)
                    }
                }
            } catch (callbackFailure: Throwable) {
                logger.error(
                    "DATA_CALLBACK_FAILED",
                    "Heart-rate data callback processing failed",
                    callbackFailure,
                    mapOf("dataTypes" to runCatching { data.dataTypes.joinToString { it.name } }.getOrNull()),
                )
                listener.onMeasurementFailure(
                    "E_CALLBACK_DATA",
                    "处理心率数据回调失败",
                    callbackFailure,
                )
            }
        }
    }

    init {
        initializeClient()
    }

    fun isClientInitialized(): Boolean = measureClient != null

    fun isRegistered(): Boolean = registered.get()

    suspend fun queryHeartRateSupport(): Boolean {
        val client = measureClient ?: throw IllegalStateException("MeasureClient is unavailable")
        logger.info("CAPABILITIES_QUERY_START", "Querying MeasureClient capabilities")
        return try {
            val capabilities = client.getCapabilities()
            val supported = DataType.HEART_RATE_BPM in capabilities.supportedDataTypesMeasure
            logger.info(
                "CAPABILITIES_QUERY_RESULT",
                "MeasureClient capabilities query completed",
                mapOf(
                    "heartRateSupported" to supported,
                    "supportedTypes" to capabilities.supportedDataTypesMeasure.joinToString { it.name },
                ),
            )
            listener.onHealthServicesAvailability(true)
            listener.onHeartRateSupport(supported)
            supported
        } catch (failure: Throwable) {
            logger.error(
                "CAPABILITIES_QUERY_FAILED",
                "MeasureClient capabilities query failed",
                failure,
            )
            listener.onHealthServicesAvailability(false)
            throw failure
        }
    }

    fun registerCallback(reason: String): Boolean {
        val client = measureClient ?: throw IllegalStateException("MeasureClient is unavailable")
        if (!registered.compareAndSet(false, true)) {
            logger.warn(
                "DUPLICATE_REGISTER_BLOCKED",
                "Duplicate MeasureCallback registration was blocked",
                callbackFields(reason),
            )
            return false
        }

        logger.info(
            "CALLBACK_REGISTER_START",
            "Registering HEART_RATE_BPM MeasureCallback",
            callbackFields(reason),
        )
        return try {
            client.registerMeasureCallback(DataType.HEART_RATE_BPM, callback)
            listener.onCallbackRegistrationChanged(true)
            logger.info(
                "CALLBACK_REGISTERED",
                "HEART_RATE_BPM MeasureCallback registered",
                callbackFields(reason),
            )
            true
        } catch (failure: Throwable) {
            registered.set(false)
            logger.error(
                "CALLBACK_REGISTER_FAILED",
                "HEART_RATE_BPM MeasureCallback registration failed",
                failure,
                callbackFields(reason),
            )
            listener.onCallbackRegistrationChanged(false)
            throw failure
        }
    }

    suspend fun unregisterCallback(reason: String): Boolean {
        val client = measureClient
        if (!registered.compareAndSet(true, false)) {
            logger.warn(
                "DUPLICATE_UNREGISTER_BLOCKED",
                "Duplicate MeasureCallback unregistration was blocked",
                callbackFields(reason),
            )
            return false
        }
        if (client == null) {
            listener.onCallbackRegistrationChanged(false)
            logger.warn(
                "CALLBACK_UNREGISTER_NO_CLIENT",
                "Callback flag cleared without a MeasureClient",
                callbackFields(reason),
            )
            return true
        }

        logger.info(
            "CALLBACK_UNREGISTER_START",
            "Unregistering HEART_RATE_BPM MeasureCallback",
            callbackFields(reason),
        )
        return try {
            client.unregisterMeasureCallback(DataType.HEART_RATE_BPM, callback)
            listener.onCallbackRegistrationChanged(false)
            logger.info(
                "CALLBACK_UNREGISTERED",
                "HEART_RATE_BPM MeasureCallback unregistered",
                callbackFields(reason),
            )
            true
        } catch (failure: Throwable) {
            registered.set(true)
            logger.error(
                "CALLBACK_UNREGISTER_FAILED",
                "HEART_RATE_BPM MeasureCallback unregistration failed",
                failure,
                callbackFields(reason),
            )
            listener.onCallbackRegistrationChanged(true)
            throw failure
        }
    }

    fun releaseBestEffort(reason: String, onComplete: () -> Unit = {}) {
        val client = measureClient
        if (client == null || !registered.compareAndSet(true, false)) {
            logger.info(
                "CALLBACK_RELEASE_NOT_REQUIRED",
                "No registered callback required release",
                callbackFields(reason),
            )
            onComplete()
            return
        }
        logger.info(
            "CALLBACK_RELEASE_ASYNC_START",
            "Starting best-effort callback release",
            callbackFields(reason),
        )
        try {
            val future = client.unregisterMeasureCallbackAsync(DataType.HEART_RATE_BPM, callback)
            future.addListener(
                {
                    try {
                        future.get()
                        listener.onCallbackRegistrationChanged(false)
                        logger.info(
                            "CALLBACK_RELEASE_ASYNC_SUCCESS",
                            "Best-effort callback release completed",
                            callbackFields(reason),
                        )
                    } catch (failure: Throwable) {
                        logger.error(
                            "CALLBACK_RELEASE_ASYNC_FAILED",
                            "Best-effort callback release failed",
                            failure,
                            callbackFields(reason),
                        )
                    } finally {
                        onComplete()
                    }
                },
                DIRECT_EXECUTOR,
            )
        } catch (failure: Throwable) {
            logger.error(
                "CALLBACK_RELEASE_REQUEST_FAILED",
                "Could not request best-effort callback release",
                failure,
                callbackFields(reason),
            )
            onComplete()
        }
    }

    private fun initializeClient() {
        logger.info("HEALTH_SERVICES_INIT_START", "Initializing Health Services MeasureClient")
        try {
            measureClient = HealthServices.getClient(appContext).measureClient
            listener.onHealthServicesAvailability(true)
            logger.info("HEALTH_SERVICES_INIT_SUCCESS", "Health Services MeasureClient initialized")
        } catch (failure: Throwable) {
            measureClient = null
            listener.onHealthServicesAvailability(false)
            logger.error(
                "HEALTH_SERVICES_INIT_FAILED",
                "Health Services MeasureClient initialization failed",
                failure,
            )
        }
    }

    private fun bootEpochMillis(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun callbackFields(reason: String): Map<String, Any?> =
        runtimeDiagnosticFields(appContext, registered.get()) + mapOf("reason" to reason)

    companion object {
        private const val MIN_VALID_BPM = 20.0
        private const val MAX_VALID_BPM = 300.0
        private val DIRECT_EXECUTOR = Executor { runnable -> runnable.run() }
    }
}
