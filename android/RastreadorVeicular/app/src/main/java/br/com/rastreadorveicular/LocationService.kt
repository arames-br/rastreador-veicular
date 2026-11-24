package br.com.rastreadorveicular

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import java.util.concurrent.atomic.AtomicBoolean

class LocationService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("rastreador", Context.MODE_PRIVATE) }
    private val firebaseAuth by lazy { FirebaseAuth.getInstance() }
    private val firestore by lazy { FirebaseFirestore.getInstance() }
    private val uploading = AtomicBoolean(false)
    private var callback: LocationCallback? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var lastUploadedAtMs: Long = 0

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForegroundCompat(buildNotification("Aguardando posição GPS e conexão"))
        if (!authorized()) { stopSelf(); return }
        prefs.edit().putBoolean("active", true).apply()
        lastUploadedAtMs = prefs.getLong("last_uploaded_at_ms", 0)
        registerNetworkListener()
        beginLocationUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            prefs.edit().putBoolean("active", false).apply()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START && !authorized()) {
            prefs.edit().putBoolean("active", false).apply()
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    private fun authorized(): Boolean {
        val user = firebaseAuth.currentUser
        if (user == null || !user.isEmailVerified || !user.email.equals(OWNER_EMAIL, true)) {
            Log.e(TAG, "Rastreamento recusado: sessão ausente, não verificada ou não autorizada")
            return false
        }
        return true
    }

    private fun beginLocationUpdates() {
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 15_000L)
            .setMinUpdateIntervalMillis(10_000L)
            .setMinUpdateDistanceMeters(10f)
            .build()
        callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.locations.forEach { location ->
                    persistLatestLocally(location)
                    refreshNotification()
                    maybeUpload(location, false)
                }
            }
        }
        try {
            LocationServices.getFusedLocationProviderClient(this)
                .requestLocationUpdates(request, callback!!, mainLooper)
        } catch (e: SecurityException) {
            Log.e(TAG, "Permissão de localização ausente", e)
            prefs.edit().putBoolean("active", false).apply()
            stopSelf()
        }
    }

    // Keep only the newest fix on-device while the network is unavailable; do not queue an unbounded backlog.
    private fun persistLatestLocally(location: Location) {
        prefs.edit()
            .putLong("pending_time_ms", location.time)
            .putString("pending_lat", location.latitude.toString())
            .putString("pending_lng", location.longitude.toString())
            .putString("pending_accuracy", location.accuracy.toString())
            .putString("pending_speed", location.speed.toString())
            .apply()
    }

    private fun registerNetworkListener() {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val listener = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { handler.post { flushPending() } }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) handler.post { flushPending() }
            }
        }
        networkCallback = listener
        try { manager.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), listener) }
        catch (e: Exception) { Log.w(TAG, "Não foi possível acompanhar a conectividade", e) }
    }

    private fun hasValidatedInternet(): Boolean {
        val manager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun maybeUpload(location: Location, reconnect: Boolean) {
        if (!hasValidatedInternet()) return
        val previousLat = prefs.getString("uploaded_lat", null)?.toDoubleOrNull()
        val previousLng = prefs.getString("uploaded_lng", null)?.toDoubleOrNull()
        val movedMeters = if (previousLat != null && previousLng != null) {
            val old = Location("uploaded").apply { latitude = previousLat; longitude = previousLng }
            location.distanceTo(old)
        } else Float.MAX_VALUE
        val timeDue = System.currentTimeMillis() - lastUploadedAtMs >= UPLOAD_INTERVAL_MS
        if (reconnect || timeDue || movedMeters >= MIN_UPLOAD_DISTANCE_M) upload(location)
    }

    private fun flushPending() {
        val time = prefs.getLong("pending_time_ms", 0)
        val lat = prefs.getString("pending_lat", null)?.toDoubleOrNull()
        val lng = prefs.getString("pending_lng", null)?.toDoubleOrNull()
        if (time > 0 && lat != null && lng != null && hasValidatedInternet()) {
            val location = Location("cached").apply {
                latitude = lat; longitude = lng
                accuracy = prefs.getString("pending_accuracy", "0")?.toFloatOrNull() ?: 0f
                speed = prefs.getString("pending_speed", "0")?.toFloatOrNull() ?: 0f
                this.time = time
            }
            maybeUpload(location, true)
        }
    }

    private fun upload(location: Location) {
        if (!authorized() || !uploading.compareAndSet(false, true)) return
        val timeMs = location.time
        val data = hashMapOf<String, Any>(
            "latitude" to location.latitude,
            "longitude" to location.longitude,
            "accuracyMeters" to location.accuracy.toDouble(),
            "speedMetersPerSecond" to location.speed.toDouble(),
            "capturedAtClientMs" to timeMs,
            "updatedAt" to FieldValue.serverTimestamp(),
            "deviceStatus" to "tracking"
        )
        firestore.collection("vehicles").document(VEHICLE_ID)
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                lastUploadedAtMs = System.currentTimeMillis()
                prefs.edit().putLong("last_uploaded_at_ms", lastUploadedAtMs)
                    .putString("uploaded_lat", location.latitude.toString())
                    .putString("uploaded_lng", location.longitude.toString())
                    .apply()
                // Clear only if no newer fix replaced the one just sent.
                if (prefs.getLong("pending_time_ms", 0) == timeMs) {
                    prefs.edit().remove("pending_time_ms").remove("pending_lat").remove("pending_lng")
                        .remove("pending_accuracy").remove("pending_speed").apply()
                }
                refreshNotification()
                uploading.set(false)
            }
            .addOnFailureListener { error ->
                Log.w(TAG, "Envio pendente; a última posição continua guardada no aparelho", error)
                uploading.set(false)
                refreshNotification()
            }
    }

    private fun refreshNotification() {
        val message = when {
            !hasValidatedInternet() -> "Sem internet — guardando a posição mais recente neste celular"
            prefs.getLong("pending_time_ms", 0) > 0 -> "Conectado — sincronizando a posição mais recente"
            else -> "Rastreamento ativo — localização sincronizada quando há conexão"
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, buildNotification(message))
    }

    private fun buildNotification(message: String): Notification {
        val openApp = PendingIntent.getActivity(this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stopIntent = PendingIntent.getService(this, 2,
            Intent(this, LocationService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder.setContentTitle("Rastreador veicular ativo")
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setOngoing(true)
            .setContentIntent(openApp)
            .addAction(Notification.Action.Builder(null, "PARAR", stopIntent).build())
            .build()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        else startForeground(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Rastreamento do veículo", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        callback?.let { LocationServices.getFusedLocationProviderClient(this).removeLocationUpdates(it) }
        networkCallback?.let {
            try { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(it) }
            catch (_: Exception) { }
        }
        if (!prefs.getBoolean("active", false)) {
            prefs.edit().putString("last_state", "stopped").apply()
        }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_START = "br.com.rastreadorveicular.START"
        const val ACTION_STOP = "br.com.rastreadorveicular.STOP"
        const val VEHICLE_ID = "vehicle-1"
        private const val OWNER_EMAIL = "aramesbrasil40@gmail.com"
        private const val TAG = "GPS_VEICULAR"
        private const val CHANNEL_ID = "vehicle_tracking"
        private const val NOTIFICATION_ID = 7
        private const val UPLOAD_INTERVAL_MS = 60_000L
        private const val MIN_UPLOAD_DISTANCE_M = 100f
    }
}
