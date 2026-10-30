package com.simple.carlauncher;

import android.Manifest;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;

/**
 * GPS odometer and live speedometer.
 */
public class Odometer extends Service implements LocationListener {

    private static final String TAG = "Odometer";
    static final int DEFAULT_INTERVAL_KM = 5000;
    static final int WARN_KM = 500;

    private static volatile int currentSpeedKmh = 0;
    private static volatile long lastSpeedTime = 0;

    private LocationManager lm;
    private Location last;
    private float pendingMeters;

    @Override
    public void onCreate() {
        super.onCreate();
        lm = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            try {
                // Actualización cada 1 segundo (1000ms) y 0 metros para velocímetro en tiempo real
                lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 1000, 0, this);
            } catch (Exception e) {
                Log.w(TAG, "no GPS", e);
            }
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_STICKY;
    }

    @Override
    public void onLocationChanged(Location l) {
        if (!l.hasAccuracy() || l.getAccuracy() > 25) return;

        float kmh = 0;
        if (l.hasSpeed()) {
            kmh = l.getSpeed() * 3.6f;
        } else if (last != null) {
            float d = last.distanceTo(l);
            float secs = Math.max(1, (l.getTime() - last.getTime()) / 1000f);
            kmh = d / secs * 3.6f;
        }

        // Filtro para evitar que marque 1 o 2 km/h estando quieto
        currentSpeedKmh = (kmh < 3f || kmh > 240f) ? 0 : Math.round(kmh);
        lastSpeedTime = SystemClock.elapsedRealtime();

        if (last != null) {
            float d = last.distanceTo(l);
            boolean moving = kmh > 4;
            // Count only real driving; drop parked drift and impossible jumps.
            if (moving && kmh < 200 && d >= 5) {
                pendingMeters += d;
                last = l;
            }
        } else {
            last = l;
        }

        if (pendingMeters >= 100) { // persist every 100 m
            SharedPreferences p = prefs(this);
            p.edit().putFloat("gps_km", p.getFloat("gps_km", 0) + pendingMeters / 1000f).apply();
            pendingMeters = 0;
        }
    }

    /** Devuelve la velocidad actual en km/h (vuelve a 0 si lleva más de 4s sin señal en movimiento). */
    static int currentSpeed() {
        if (SystemClock.elapsedRealtime() - lastSpeedTime > 4000) return 0;
        return currentSpeedKmh;
    }

    public void onStatusChanged(String p, int s, Bundle b) { }
    public void onProviderEnabled(String p) { }
    public void onProviderDisabled(String p) {
        currentSpeedKmh = 0;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        lm.removeUpdates(this);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---------- Shared state ----------

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("odometer", Context.MODE_PRIVATE);
    }

    static boolean isConfigured(Context c) {
        return prefs(c).contains("base_km");
    }

    /** Estimated dashboard reading: the value the user entered plus GPS distance since. */
    static int currentKm(Context c) {
        SharedPreferences p = prefs(c);
        return Math.round(p.getInt("base_km", 0) + p.getFloat("gps_km", 0));
    }

    static void setCurrentKm(Context c, int km) {
        prefs(c).edit().putInt("base_km", km).putFloat("gps_km", 0).apply();
    }

    static int lastChangeKm(Context c) {
        return prefs(c).getInt("last_change_km", currentKm(c));
    }

    static void setLastChangeKm(Context c, int km) {
        prefs(c).edit().putInt("last_change_km", km).apply();
    }

    static int intervalKm(Context c) {
        return prefs(c).getInt("interval_km", DEFAULT_INTERVAL_KM);
    }

    static void setIntervalKm(Context c, int km) {
        prefs(c).edit().putInt("interval_km", km).apply();
    }

    /** Km left until the next oil change (negative when overdue). */
    static int remainingKm(Context c) {
        return lastChangeKm(c) + intervalKm(c) - currentKm(c);
    }
}
