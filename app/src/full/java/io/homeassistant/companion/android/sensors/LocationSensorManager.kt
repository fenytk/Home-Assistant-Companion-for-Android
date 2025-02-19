package io.homeassistant.companion.android.sensors

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.Location
import android.os.Build
import android.os.PowerManager
import android.text.TextUtils
import android.util.Log
import androidx.core.content.ContextCompat.getSystemService
import dagger.hilt.android.AndroidEntryPoint
import io.homeassistant.companion.android.GCJ2WGS
import io.homeassistant.companion.android.common.data.integration.UpdateLocation
import io.homeassistant.companion.android.common.sensors.LocationSensorManagerBase
import io.homeassistant.companion.android.common.sensors.SensorManager
import io.homeassistant.companion.android.common.util.DisabledLocationHandler
import io.homeassistant.companion.android.database.AppDatabase
import io.homeassistant.companion.android.database.sensor.Attribute
import io.homeassistant.companion.android.database.sensor.SensorSetting
import io.homeassistant.companion.android.location.HighAccuracyLocationService
import io.homeassistant.companion.android.notifications.MessagingManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import io.homeassistant.companion.android.common.R as commonR
import com.amap.api.location.*
import io.homeassistant.companion.android.common.bluetooth.BluetoothUtils

@AndroidEntryPoint
class LocationSensorManager : LocationSensorManagerBase() {

    companion object {
        private const val SETTING_ACCURACY = "Minimum Accuracy"
        private const val SETTING_ACCURATE_UPDATE_TIME = "Minimum time between updates"
        private const val SETTING_INCLUDE_SENSOR_UPDATE = "Include in sensor update"
        private const val SETTING_HIGH_ACCURACY_MODE = "High accuracy mode (May drain battery fast)"
        private const val SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL =
            "High accuracy mode update interval (seconds)"
        private const val SETTING_HIGH_ACCURACY_MODE_BLUETOOTH_DEVICES =
            "High accuracy mode only when connected to BT devices"

        private const val DEFAULT_MINIMUM_ACCURACY = 200
        private const val DEFAULT_UPDATE_INTERVAL_HA_SECONDS = 5

        const val ACTION_REQUEST_LOCATION_UPDATES =
            "io.homeassistant.companion.android.background.REQUEST_UPDATES"
        const val ACTION_REQUEST_ACCURATE_LOCATION_UPDATE =
            "io.homeassistant.companion.android.background.REQUEST_ACCURATE_UPDATE"
        const val ACTION_PROCESS_LOCATION =
            "io.homeassistant.companion.android.background.PROCESS_UPDATES"
        const val ACTION_PROCESS_GEO =
            "io.homeassistant.companion.android.background.PROCESS_GEOFENCE"
        const val ACTION_FORCE_HIGH_ACCURACY =
            "io.homeassistant.companion.android.background.FORCE_HIGH_ACCURACY"

        val backgroundLocation = SensorManager.BasicSensor(
            "location_background",
            "",
            commonR.string.basic_sensor_name_location_background,
            commonR.string.sensor_description_location_background
        )
        val zoneLocation = SensorManager.BasicSensor(
            "zone_background",
            "",
            commonR.string.basic_sensor_name_location_zone,
            commonR.string.sensor_description_location_zone
        )
        val singleAccurateLocation = SensorManager.BasicSensor(
            "accurate_location",
            "",
            commonR.string.basic_sensor_name_location_accurate,
            commonR.string.sensor_description_location_accurate
        )

        val highAccuracyMode = SensorManager.BasicSensor(
            "high_accuracy_mode",
            "binary_sensor",
            commonR.string.basic_sensor_name_high_accuracy_mode,
            commonR.string.sensor_description_high_accuracy_mode,
            entityCategory = SensorManager.ENTITY_CATEGORY_DIAGNOSTIC
        )
        internal const val TAG = "LocBroadcastReceiver"

        private var isBackgroundLocationSetup = false
        private var isZoneLocationSetup = false

        private var lastLocationSend = 0L
        private var lastUpdateLocation = ""

        private var lastHighAccuracyMode = false
        private var lastHighAccuracyUpdateInterval = DEFAULT_MINIMUM_ACCURACY

        fun setHighAccuracyModeSetting(context: Context, enabled: Boolean) {
            val sensorDao = AppDatabase.getInstance(context).sensorDao()
            sensorDao.add(
                SensorSetting(
                    backgroundLocation.id,
                    SETTING_HIGH_ACCURACY_MODE,
                    enabled.toString(),
                    "toggle"
                )
            )
        }

        fun getHighAccuracyModeIntervalSetting(context: Context): Int {
            val sensorDao = AppDatabase.getInstance(context).sensorDao()
            val sensorSettings = sensorDao.getSettings(backgroundLocation.id)
            return sensorSettings.firstOrNull { it.name == SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL }?.value?.toIntOrNull()
                ?: DEFAULT_UPDATE_INTERVAL_HA_SECONDS
        }
    }

    private val ioScope: CoroutineScope = CoroutineScope(Dispatchers.IO)

    lateinit var latestContext: Context

    //声明AMapLocationClient类对象
    var mLocationClient: AMapLocationClient? = null

    var amapLocation: AMapLocation? = null


    //声明定位回调监听器
    private var mLocationListener = AMapLocationListener { location ->
        if (location.errorCode == 0) {

            amapLocation = location
            Log.d(TAG, "Amap Location -- ${location.latitude}")

            addressUpdata(latestContext)
            val sensorDao = AppDatabase.getInstance(latestContext).sensorDao()
            val sensorSettings = sensorDao.getSettings(backgroundLocation.id)
            val minAccuracy = sensorSettings
                .firstOrNull { it.name == SETTING_ACCURACY }?.value?.toIntOrNull()
                ?: DEFAULT_MINIMUM_ACCURACY
            sensorDao.add(
                SensorSetting(
                    backgroundLocation.id,
                    SETTING_ACCURACY,
                    minAccuracy.toString(),
                    "number"
                )
            )
            if (location.accuracy > minAccuracy) {
                Log.d(TAG, "Location accuracy didn't meet requirements, disregarding: $location")
            } else {
                val hm = GCJ2WGS.delta(location.latitude, location.longitude)
                location.latitude = hm["lat"]!!
                location.longitude = hm["lon"]!!
                sendLocationUpdate(location)
            }
            //可在其中解析amapLocation获取相应内容。
        } else {
            //定位失败时，可通过ErrCode（错误码）信息来确定失败的原因，errInfo是错误信息，详见错误码表。
            Log.e(
                TAG, "Location Error, ErrCode:"
                        + location.errorCode + ", errInfo:"
                        + location.errorInfo
            )
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        latestContext = context

        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            ACTION_REQUEST_LOCATION_UPDATES -> setupLocationTracking()
            ACTION_PROCESS_LOCATION -> handleLocationUpdate()
            ACTION_PROCESS_GEO -> handleLocationUpdate()
            ACTION_REQUEST_ACCURATE_LOCATION_UPDATE -> requestSingleAccurateLocation()
            ACTION_FORCE_HIGH_ACCURACY -> {
                val intentData = intent.extras?.get("command")?.toString()
                if (intentData == "turn_on")
                    handleLocationUpdate()
//                else if (intentData == "turn_off")
//                    handleLocationUpdate()
            }
            else -> Log.w(TAG, "Unknown intent action: ${intent.action}!")
        }
    }

    private fun setupLocationTracking() {
        Log.w(TAG, "setupLocationTracking")
        if (!checkPermission(latestContext, backgroundLocation.id)) {
            Log.w(TAG, "Not starting location reporting because of permissions.")
            return
        }

        val backgroundEnabled = isEnabled(latestContext, backgroundLocation.id)
        val zoneEnabled = isEnabled(latestContext, zoneLocation.id)

        ioScope.launch {
            try {
                if (!backgroundEnabled && !zoneEnabled) {
                    removeAllLocationUpdateRequests()
                    isBackgroundLocationSetup = false
                    isZoneLocationSetup = false
                }
                if (!zoneEnabled && isZoneLocationSetup) {
                    isZoneLocationSetup = false
                    Log.d(TAG, "Removing geofence update requests")
                }
                if (!backgroundEnabled && isBackgroundLocationSetup) {
                    removeBackgroundUpdateRequests()
                    isBackgroundLocationSetup = false
                    Log.d(TAG, "Removing background update requests")
                }
                if (backgroundEnabled) {

                    val updateIntervalHighAccuracySeconds = getHighAccuracyModeUpdateInterval()
                    val highAccuracyMode = getHighAccuracyMode()

                    lastHighAccuracyMode = highAccuracyMode
                    lastHighAccuracyUpdateInterval = updateIntervalHighAccuracySeconds

                    if (!isBackgroundLocationSetup) {
                        isBackgroundLocationSetup = true
                        requestLocationUpdates()
                    } else {
                        requestLocationUpdates()
                    }

                }
                if (zoneEnabled && !isZoneLocationSetup) {
                    isZoneLocationSetup = true
                }
            } catch (e: Exception) {
                Log.e(TAG, "Issue setting up location tracking", e)
            }
        }
    }

    private fun getHighAccuracyModeUpdateInterval(): Int {

        val updateIntervalHighAccuracySeconds = getSetting(
            latestContext,
            LocationSensorManager.backgroundLocation,
            SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL,
            "number",
            DEFAULT_UPDATE_INTERVAL_HA_SECONDS.toString()
        )

        var updateIntervalHighAccuracySecondsInt = updateIntervalHighAccuracySeconds.toInt()
        if (updateIntervalHighAccuracySecondsInt < 5) {
            updateIntervalHighAccuracySecondsInt = DEFAULT_UPDATE_INTERVAL_HA_SECONDS

            val sensorDao = AppDatabase.getInstance(latestContext).sensorDao()
            sensorDao.add(
                SensorSetting(
                    backgroundLocation.id,
                    SETTING_HIGH_ACCURACY_MODE_UPDATE_INTERVAL,
                    updateIntervalHighAccuracySecondsInt.toString(),
                    "number"
                )
            )
        }
        return updateIntervalHighAccuracySecondsInt
    }

    private fun getHighAccuracyMode(): Boolean {

        val highAccuracyModeBTDevices = getSetting(
            latestContext,
            LocationSensorManager.backgroundLocation,
            SETTING_HIGH_ACCURACY_MODE_BLUETOOTH_DEVICES,
            "list-bluetooth",
            ""
        )

        var highAccuracyMode = getSetting(
            latestContext,
            LocationSensorManager.backgroundLocation,
            SETTING_HIGH_ACCURACY_MODE,
            "toggle",
            "false"
        ).toBoolean()

        if (highAccuracyMode) {
            if (!highAccuracyModeBTDevices.isNullOrEmpty()) {
                val bluetoothDevices = BluetoothUtils.getBluetoothDevices(latestContext)
                val highAccuracyBtDevConnected =
                    bluetoothDevices.any { it.connected && highAccuracyModeBTDevices.contains(it.name) }

                highAccuracyMode = highAccuracyBtDevConnected
            }
        }
        return highAccuracyMode
    }

    private fun removeAllLocationUpdateRequests() {
        Log.d(TAG, "Removing all location requests.")
        removeBackgroundUpdateRequests()
    }

    private fun removeBackgroundUpdateRequests() {
        mLocationClient!!.stopLocation()
    }


    private fun requestLocationUpdates() {
        if (!checkPermission(latestContext, backgroundLocation.id)) {
            Log.w(TAG, "Not registering for location updates because of permissions.")
            return
        }

        AMapLocationClient.updatePrivacyShow(latestContext, true, true);
        AMapLocationClient.updatePrivacyAgree(latestContext, true);

        mLocationClient = AMapLocationClient(latestContext)

//        val sensorDao = AppDatabase.getInstance(latestContext).sensorDao()
//        val sensorSettings = sensorDao.getSettings(singleAccurateLocation.id)
//        val minTimeBetweenUpdates = sensorSettings
//            .firstOrNull { it.name == SETTING_ACCURATE_UPDATE_TIME }?.value?.toIntOrNull()
//            ?: 1000 * 60 * 5
//        Log.d(TAG, "Registering for location updates.")
        mLocationClient!!.setLocationListener(mLocationListener)
        val mLocationOption = AMapLocationClientOption()

        if (lastHighAccuracyMode) {
            mLocationOption.locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
        } else {
            mLocationOption.locationMode = AMapLocationClientOption.AMapLocationMode.Battery_Saving
        }
        if (lastHighAccuracyUpdateInterval > 999999) {
            mLocationOption.isOnceLocation = true
            mLocationOption.isOnceLocationLatest = true
        } else {
            if (lastHighAccuracyUpdateInterval < 10) lastHighAccuracyUpdateInterval = 10
            mLocationOption.interval = lastHighAccuracyUpdateInterval * 1000L
            mLocationOption.isOnceLocation = false

        }
        mLocationClient!!.setLocationOption(mLocationOption)
        mLocationClient!!.startLocation()
    }

    private fun handleLocationUpdate() {
        if (mLocationClient != null) {
            mLocationClient!!.startLocation()
        } else {
            requestLocationUpdates()
        }

    }

    private fun sendLocationUpdate(
        location: Location?,
        name: String = "",
        geofenceUpdate: Boolean = false
    ) {
        if (location == null) return

        Log.d(
            TAG, "Last Location: " +
                    "\nCoords:(${location.latitude}, ${location.longitude})" +
                    "\nAccuracy: ${location.accuracy}" +
                    "\nBearing: ${location.bearing}"
        )
        var accuracy = 0
        if (location.accuracy.toInt() >= 0) {
            accuracy = location.accuracy.toInt()
        }
        val updateLocation = UpdateLocation(
            // name,
            arrayOf(location.latitude, location.longitude),
            accuracy,
            location.speed.toInt(),
            location.altitude.toInt(),
            location.bearing.toInt(),
            if (Build.VERSION.SDK_INT >= 26) location.verticalAccuracyMeters.toInt() else 0
        )

        val now = System.currentTimeMillis()

        Log.d(TAG, "Begin evaluating if location update should be skipped")
        if (now + 5000 < location.time) {
            Log.d(
                TAG,
                "Skipping location update that came from the future. ${now + 5000} should always be greater than ${location.time}"
            )
            return
        }

        if (location.time < lastLocationSend) {
            Log.d(
                TAG,
                "Skipping old location update since time is before the last one we sent, received: ${location.time} last sent: $lastLocationSend"
            )
            return
        }

        if (now - location.time < 300000) {
            Log.d(
                TAG,
                "Received location that is ${now - location.time} milliseconds old, ${location.time} compared to $now with source ${location.provider}"
            )
            if (lastUpdateLocation == updateLocation.gps.contentToString()) {
                if (now < lastLocationSend + 900000) {
                    Log.d(TAG, "Duplicate location received, not sending to HA")
                    return
                }
            } else {
                if (now < lastLocationSend + 5000 && !geofenceUpdate) {
                    Log.d(
                        TAG,
                        "New location update not possible within 5 seconds, not sending to HA"
                    )
                    return
                }
            }
        } else {
            Log.d(
                TAG,
                "Skipping location update due to old timestamp ${location.time} compared to $now"
            )
            return
        }
        lastLocationSend = now
        lastUpdateLocation = updateLocation.gps.contentToString()

        ioScope.launch {
            try {
                integrationUseCase.updateLocation(updateLocation)
                Log.d(TAG, "Location update sent successfully")
            } catch (e: Exception) {
                Log.e(TAG, "Could not update location.", e)
            }
        }
    }

    private fun requestSingleAccurateLocation() {
        Log.d(TAG, "requestSingleAccurateLocation")
        if (!checkPermission(latestContext, singleAccurateLocation.id)) {
            Log.w(TAG, "Not getting single accurate location because of permissions.")
            return
        }
        if (!isEnabled(latestContext, singleAccurateLocation.id)) {
            Log.w(TAG, "Requested single accurate location but it is not enabled.")
            return
        }

        val now = System.currentTimeMillis()
        val sensorDao = AppDatabase.getInstance(latestContext).sensorDao()
        val fullSensor = sensorDao.getFull(singleAccurateLocation.id)
        val latestAccurateLocation =
            fullSensor?.attributes?.firstOrNull { it.name == "lastAccurateLocationRequest" }?.value?.toLongOrNull()
                ?: 0L

        val sensorSettings = sensorDao.getSettings(singleAccurateLocation.id)
        val minAccuracy = sensorSettings
            .firstOrNull { it.name == SETTING_ACCURACY }?.value?.toIntOrNull()
            ?: DEFAULT_MINIMUM_ACCURACY
        sensorDao.add(
            SensorSetting(
                singleAccurateLocation.id,
                SETTING_ACCURACY,
                minAccuracy.toString(),
                "number"
            )
        )
        val minTimeBetweenUpdates = sensorSettings
            .firstOrNull { it.name == SETTING_ACCURATE_UPDATE_TIME }?.value?.toIntOrNull()
            ?: 1000 * 60 * 5
        sensorDao.add(
            SensorSetting(
                singleAccurateLocation.id,
                SETTING_ACCURATE_UPDATE_TIME,
                minTimeBetweenUpdates.toString(),
                "number"
            )
        )

        // Only update accurate location at most once a minute
        if (now < latestAccurateLocation + minTimeBetweenUpdates) {
            Log.d(TAG, "Not requesting accurate location, last accurate location was too recent")
            return
        }
        sensorDao.add(
            Attribute(
                singleAccurateLocation.id,
                "lastAccurateLocationRequest",
                now.toString(),
                "string"
            )
        )

        val wakeLock: PowerManager.WakeLock? =
            getSystemService(latestContext, PowerManager::class.java)
                ?.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "HomeAssistant::AccurateLocation"
                )?.apply { acquire(10 * 60 * 1000L /*10 minutes*/) }

        runBlocking { sendLocationUpdate(amapLocation) }
        if (wakeLock?.isHeld == true) wakeLock.release()

        if (mLocationClient != null) {
            mLocationClient!!.startLocation()
        } else {
            requestLocationUpdates()
        }

    }

    override val enabledByDefault: Boolean
        get() = true

    override val name: Int
        get() = commonR.string.sensor_name_location

    val availableSensors: List<SensorManager.BasicSensor>
        get() = listOf(singleAccurateLocation, backgroundLocation, zoneLocation)

    override fun requiredPermissions(sensorId: String): Array<String> {
        return when {
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) -> {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_CONNECT
                )
            }
            (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) -> {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH
                )
            }
            else -> {
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.BLUETOOTH
                )
            }
        }
    }

    override fun requestSensorUpdate(
        context: Context
    ) {
        latestContext = context
        if (isEnabled(context, zoneLocation.id) || isEnabled(context, backgroundLocation.id))
            setupLocationTracking()
        val sensorDao = AppDatabase.getInstance(latestContext).sensorDao()
        val sensorSetting = sensorDao.getSettings(singleAccurateLocation.id)
        val includeSensorUpdate =
            sensorSetting.firstOrNull { it.name == SETTING_INCLUDE_SENSOR_UPDATE }?.value
                ?: "false"
        if (includeSensorUpdate == "true") {
            if (isEnabled(context, singleAccurateLocation.id)) {
                context.sendBroadcast(
                    Intent(context, this.javaClass).apply {
                        action = ACTION_REQUEST_ACCURATE_LOCATION_UPDATE
                    }
                )
            }
        } else
            sensorDao.add(
                SensorSetting(
                    singleAccurateLocation.id,
                    SETTING_INCLUDE_SENSOR_UPDATE,
                    "false",
                    "toggle"
                )
            )
    }

    override fun getAvailableSensors(context: Context): List<SensorManager.BasicSensor> {
        return if (DisabledLocationHandler.hasGPS(context)) {
            listOf(singleAccurateLocation, backgroundLocation, zoneLocation)
        } else {
            listOf(backgroundLocation, zoneLocation)
        }
    }

    private fun addressUpdata(context: Context) {
        //var address: Address? = null
//        try {
//            if (amapLocation == null) {
//                Log.e(TAG, "Somehow location is null even though it was successful")
//                return
//            }
//
//            val sensorDao = AppDatabase.getInstance(context).sensorDao()
//            val sensorSettings = sensorDao.getSettings(GeocodeSensorManager.geocodedLocation.id)
//            val minAccuracy = sensorSettings
//                .firstOrNull { it.name == GeocodeSensorManager.SETTING_ACCURACY }?.value?.toIntOrNull()
//                ?: GeocodeSensorManager.DEFAULT_MINIMUM_ACCURACY
//            sensorDao.add(
//                Setting(
//                    GeocodeSensorManager.geocodedLocation.id,
//                    GeocodeSensorManager.SETTING_ACCURACY,
//                    minAccuracy.toString(),
//                    "number"
//                )
//            )
//
//            if (amapLocation!!.accuracy <= minAccuracy)
//                address = Geocoder(context)
//                    .getFromLocation(amapLocation!!.latitude, amapLocation!!.longitude, 1)
//                    .firstOrNull()
//        } catch (e: java.lang.Exception) {
//            Log.e(TAG, "Failed to get geocoded location", e)
//        }
//        var attributes = address?.let {
//            mapOf(
//                "Administrative Area" to it.adminArea,
//                "Country" to it.countryName,
//                "ISO Country Code" to it.countryCode,
//                "Locality" to it.locality,
//                "Latitude" to it.latitude,
//                "Longitude" to it.longitude,
//                "Postal Code" to it.postalCode,
//                "Sub Administrative Area" to it.subAdminArea,
//                "Sub Locality" to it.subLocality,
//                "Sub Thoroughfare" to it.subThoroughfare,
//                "Thoroughfare" to it.thoroughfare
//            )
//        }.orEmpty()
        var addressStr = amapLocation!!.address
        // if (attributes.isEmpty()) {
        val attributes = amapLocation.let {
            mapOf(
                "Administrative Area" to it!!.district,
                "Country" to it.city,
                "accuracy" to it.accuracy,
                "altitude" to it.altitude,
                "bearing" to it.bearing,
                "provider" to it.provider,
                "time" to it.time,
                "Locality" to it.province,
                "Latitude" to it.latitude,
                "Longitude" to it.longitude,
                "Postal Code" to it.cityCode,
                "Thoroughfare" to it.street,
                //"ISO Country Code" to it.cityCode,
                "vertical_accuracy" to if (Build.VERSION.SDK_INT >= 26) it.verticalAccuracyMeters.toInt() else 0,
            )
        }
        // }
//        val zoneAttr = mapOf(
//            "accuracy" to geofencingEvent.triggeringLocation.accuracy,
//            "altitude" to geofencingEvent.triggeringLocation.altitude,
//            "bearing" to geofencingEvent.triggeringLocation.bearing,
//            "latitude" to geofencingEvent.triggeringLocation.latitude,
//            "longitude" to geofencingEvent.triggeringLocation.longitude,
//            "provider" to geofencingEvent.triggeringLocation.provider,
//            "time" to geofencingEvent.triggeringLocation.time,
//            "vertical_accuracy" to if (Build.VERSION.SDK_INT >= 26) geofencingEvent.triggeringLocation.verticalAccuracyMeters.toInt() else 0,
//            "zone" to zone
//        )
        if (TextUtils.isEmpty(addressStr)) {
            addressStr =
                amapLocation!!.city + amapLocation!!.district + amapLocation!!.street + amapLocation!!.aoiName + amapLocation!!.floor
        }
        if (TextUtils.isEmpty(addressStr)) {
            Log.d(TAG, "addressStr--" + amapLocation!!.locationDetail)
            return
        }
        onSensorUpdated(
            context,
            GeocodeSensorManager.geocodedLocation,
            addressStr,
            "mdi:map",
            attributes
        )
    }
}
