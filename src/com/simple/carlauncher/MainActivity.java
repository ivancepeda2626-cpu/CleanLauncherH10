package com.simple.carlauncher;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextClock;
import android.widget.TextView;

import java.util.Calendar;

public class MainActivity extends Activity implements NowPlaying.Listener {

    private static final String MAPS = "com.google.android.apps.maps/com.google.android.maps.MapsActivity";
    private static final String EQ = "com.nwd.audioset/.home_horizontalActivity";
    private static final String SETTINGS = "com.android.car.setting/.home_horizontalActivity";

    private static final long WEATHER_EVERY_MS = 30 * 60 * 1000;

    // Variables estáticas para recibir datos en vivo de Google Maps / Waze desde MediaListener
    public static volatile String navHeadline = "";
    public static volatile String navSubtext = "";
    public static volatile long navLastUpdate = 0;

    private final Handler handler = new Handler();
    private NowPlaying nowPlaying;

    private TextView greeting, weatherTemp, weatherDesc, weatherCity, weatherExtra;
    private ImageView weatherIcon;
    private GradientDrawable weatherBg;
    private ImageView car, carLights;
    private boolean hasLightsImage;
    private TextView oilInfo;
    private boolean createdDark;
    private String createdProfile;

    private String profileStamp() {
        return Profile.name(this) + "|" + Profile.carPhotoFile(this).lastModified();
    }
    private GradientDrawable oilBg;
    private long lastWeatherFetch;

    private TextView speedValue, speedOdo;
    private ImageView art, playBtn;
    private TextView songSource, songTitle, songArtist;
    private TextView navStatus, navMainText, navSubText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(14), dp(16), dp(4));
        root.setBackground(Ui.background(this));

        LinearLayout cards = new LinearLayout(this);
        cards.addView(buildWeatherCard(), weight(1.22f, 0));

        // Columna central: Reloj más largo (0.92f) + Velocímetro (1.08f)
        LinearLayout middle = new LinearLayout(this);
        middle.setOrientation(LinearLayout.VERTICAL);
        middle.addView(buildClockCard(), vweight(0.92f, 0));
        middle.addView(buildSpeedCard(), vweight(1.08f, dp(12)));
        cards.addView(middle, weight(0.98f, dp(12)));

        // Columna derecha: Música a la mitad (1f) + Navegación funcional abajo (1f)
        LinearLayout rightCol = new LinearLayout(this);
        rightCol.setOrientation(LinearLayout.VERTICAL);
        rightCol.addView(buildMusicCard(), vweight(1f, 0));
        rightCol.addView(buildNavCard(), vweight(1f, dp(12)));
        cards.addView(rightCol, weight(1.05f, dp(12)));

        root.addView(cards, new LinearLayout.LayoutParams(-1, 0, 1f));

        root.addView(buildDock(), new LinearLayout.LayoutParams(-1, dp(84)));
        setContentView(root);

        nowPlaying = NowPlaying.get(this);
        createdDark = Ui.isDark(this);
        createdProfile = profileStamp();
        if (!Profile.isSetupDone(this) && Profile.bundledCar(this, false) == 0) {
            startActivity(new Intent(this, SetupActivity.class));
        }
        if (Profile.isNwd(this)) startService(new Intent(this, VolumeOverlay.class));
        startService(new Intent(this, GreeterService.class));
        startService(new Intent(this, Odometer.class));
    }

    // ---------- Greeting + weather (Con carro más grande) ----------

    private View buildWeatherCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(24), dp(22), dp(24), dp(20));
        weatherBg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                Ui.pick(this, new int[]{0x993FA9FF, 0x667B4DFF}, new int[]{0xFF1E1E28, 0xFF121218}));
        weatherBg.setCornerRadius(dp(18));
        weatherBg.setStroke(dp(1), 0x447FD8FF);
        card.setBackground(weatherBg);

        TextView hello = left(Ui.text(this, Profile.hello(this), 32, Color.WHITE));
        hello.setTypeface(Typeface.DEFAULT_BOLD);
        card.addView(hello);

        greeting = left(Ui.text(this, "", 16, 0xCCFFFFFF));
        greeting.setPadding(0, dp(2), 0, 0);
        card.addView(greeting);

        // Carro más grande: sin márgenes laterales y con zoom del 18%
        FrameLayout carBox = new FrameLayout(this);
        carBox.setClipChildren(false);
        card.setClipChildren(false);
        car = new ImageView(this);
        car.setScaleType(ImageView.ScaleType.FIT_CENTER);
        car.setScaleX(1.18f);
        car.setScaleY(1.18f);
        carLights = new ImageView(this);
        carLights.setScaleType(ImageView.ScaleType.FIT_CENTER);
        carLights.setScaleX(1.18f);
        carLights.setScaleY(1.18f);
        carLights.setAlpha(0f);

        android.graphics.Bitmap own = Profile.carPhoto(this);
        int bundled = Profile.bundledCar(this, false), bundledLights = Profile.bundledCar(this, true);
        if (own != null) {
            car.setImageBitmap(own);
        } else if (bundled != 0) {
            car.setImageResource(bundled);
            if (bundledLights != 0) {
                carLights.setImageResource(bundledLights);
                hasLightsImage = true;
            }
        } else {
            car.setImageResource(R.drawable.ic_car);
            car.setColorFilter(0x40FFFFFF);
        }
        carBox.addView(car, new FrameLayout.LayoutParams(-1, -1));
        carBox.addView(carLights, new FrameLayout.LayoutParams(-1, -1));
        carBox.setOnClickListener(v -> startActivity(new Intent(this, SetupActivity.class)));
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(-1, 0, 1f);
        clp.setMargins(0, dp(2), 0, dp(2));
        card.addView(carBox, clp);

        LinearLayout now = new LinearLayout(this);
        now.setGravity(Gravity.CENTER_VERTICAL);
        weatherIcon = new ImageView(this);
        weatherIcon.setImageResource(R.drawable.ic_w_cloud);
        weatherIcon.setColorFilter(0xFFFFE07A);
        now.addView(weatherIcon, new LinearLayout.LayoutParams(dp(50), dp(50)));
        weatherTemp = left(Ui.text(this, "--°", 46, Color.WHITE));
        weatherTemp.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
        weatherTemp.setIncludeFontPadding(false);
        weatherTemp.setPadding(dp(10), 0, dp(12), 0);
        now.addView(weatherTemp);

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        weatherDesc = left(Ui.text(this, "Cargando el clima…", 15, Color.WHITE));
        weatherDesc.setSingleLine(false);
        weatherDesc.setMaxLines(2);
        texts.addView(weatherDesc);
        weatherCity = left(Ui.text(this, "", 13, 0xB3FFFFFF));
        weatherCity.setPadding(0, dp(2), 0, 0);
        texts.addView(weatherCity);
        now.addView(texts, new LinearLayout.LayoutParams(0, -2, 1f));
        card.addView(now);

        weatherExtra = left(Ui.text(this, "", 13, 0xE6FFFFFF));
        weatherExtra.setPadding(dp(14), dp(6), dp(14), dp(6));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x26FFFFFF);
        chip.setCornerRadius(dp(20));
        weatherExtra.setBackground(chip);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
        lp.topMargin = dp(8);
        card.addView(weatherExtra, lp);

        Ui.pressable(card, 0.98f);
        card.setOnClickListener(v -> fetchWeather(true));
        return card;
    }

    private void updateGreeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        greeting.setText((h < 12 ? "Buenos días" : h < 19 ? "Buenas tardes" : "Buenas noches")
                + " · Bienvenido");
    }

    private void showWeather(Weather w) {
        if (w == null) return;
        weatherIcon.setImageResource(w.icon());
        weatherIcon.setColorFilter(w.icon() == R.drawable.ic_w_sun ? 0xFFFFD25A
                : w.icon() == R.drawable.ic_w_moon ? 0xFFDDE4FF : Color.WHITE);
        weatherTemp.setText(w.temp + "°");
        weatherDesc.setText(w.description());
        weatherCity.setText(w.city.isEmpty() ? "Sensación " + w.feelsLike + "°"
                : w.city + " · Sensación " + w.feelsLike + "°");
        weatherExtra.setText("↑ " + w.max + "°   ↓ " + w.min + "°   💧 " + w.humidity + "%   " + w.wind + " km/h");
        weatherBg.setColors(Ui.isDark(this) ? new int[]{0xFF1E1E28, 0xFF121218}
                : w.isDay ? new int[]{0x993FA9FF, 0x667B4DFF} : new int[]{0x8C2A3A9E, 0x80261A6E});
    }

    private void fetchWeather(boolean force) {
        long now = SystemClock.elapsedRealtime();
        if (!force && lastWeatherFetch != 0 && now - lastWeatherFetch < WEATHER_EVERY_MS) return;
        lastWeatherFetch = now;
        Weather.fetch(this, this::showWeather);
    }

    private final Runnable periodic = new Runnable() {
        public void run() {
            if (Ui.isDark(MainActivity.this) != createdDark) recreate();
            updateGreeting();
            fetchWeather(false);
            handler.postDelayed(this, 60 * 1000);
        }
    };

    // ---------- Car lights (ILL wire) ----------

    private final android.content.BroadcastReceiver illReceiver = new android.content.BroadcastReceiver() {
        @Override
        public void onReceive(android.content.Context c, Intent i) {
            showCarLights(i.getByteExtra("extra_ill_state", (byte) 0) != 0);
            handler.postDelayed(() -> { if (Ui.isDark(MainActivity.this) != createdDark) recreate(); }, 1500);
        }
    };

    private boolean carLightsOn() {
        int state = Settings.System.getInt(getContentResolver(), "mcu_no_source_device_state", 0);
        return (state & 2) != 0;
    }

    private void showCarLights(boolean on) {
        if (!hasLightsImage) return;
        carLights.animate().alpha(on ? 1f : 0f).setDuration(600).start();
        car.animate().alpha(on ? 0f : 1f).setDuration(600).start();
    }

    private void updateOil() {
        if (!Odometer.isConfigured(this)) {
            oilInfo.setText("Aceite: configurar");
            oilBg.setColor(0x26FFFFFF);
            return;
        }
        int left = Odometer.remainingKm(this);
        oilInfo.setText(left < 0 ? "Aceite: ¡ya toca el cambio!" : "Aceite: faltan " + OilActivity.fmt(left) + " km");
        oilBg.setColor(left < 0 ? 0xCCE53935 : left <= Odometer.WARN_KM ? 0xCCFF9800 : 0x26FFFFFF);
    }

    // ---------- Clock (Más alto y espacioso) ----------

    private View buildClockCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(12), dp(14), dp(12), dp(14));
        card.setBackground(Ui.card(this));

        TextClock time = new TextClock(this);
        time.setFormat24Hour("HH:mm");
        time.setFormat12Hour("hh:mm");
        time.setTextSize(66);
        time.setTextColor(Color.WHITE);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setIncludeFontPadding(false);
        time.setGravity(Gravity.CENTER);
        card.addView(time);

        TextClock date = new TextClock(this);
        date.setFormat24Hour("EEEE, d 'de' MMMM");
        date.setFormat12Hour("EEEE, d 'de' MMMM");
        date.setTextSize(15);
        date.setTextColor(0xCCFFFFFF);
        date.setPadding(0, dp(6), 0, 0);
        date.setGravity(Gravity.CENTER);
        card.addView(date);

        oilInfo = Ui.text(this, "", 13, Color.WHITE);
        oilInfo.setPadding(dp(14), dp(6), dp(14), dp(6));
        oilBg = new GradientDrawable();
        oilBg.setCornerRadius(dp(14));
        oilInfo.setBackground(oilBg);
        Ui.pressable(oilInfo, 0.92f);
        oilInfo.setOnClickListener(v -> startActivity(new Intent(this, OilActivity.class)));
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(-2, -2);
        olp.topMargin = dp(10);
        card.addView(oilInfo, olp);

        Ui.pressable(card, 0.97f);
        card.setOnClickListener(v -> openSettings());
        return card;
    }

    // ---------- Velocímetro GPS ----------

    private View buildSpeedCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setBackground(Ui.card(this));

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER);
        ImageView speedIcon = new ImageView(this);
        speedIcon.setImageResource(R.drawable.ic_compass);
        speedIcon.setColorFilter(0xFF4FC3F7);
        head.addView(speedIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));
        TextView title = Ui.text(this, "VELOCÍMETRO", 13, 0xCCFFFFFF);
        title.setLetterSpacing(0.08f);
        title.setPadding(dp(6), 0, 0, 0);
        head.addView(title);
        card.addView(head);

        speedValue = Ui.text(this, "0", 66, Color.WHITE);
        speedValue.setTypeface(Typeface.DEFAULT_BOLD);
        speedValue.setIncludeFontPadding(false);
        speedValue.setPadding(0, dp(2), 0, 0);
        card.addView(speedValue);

        TextView unit = Ui.text(this, "km/h", 15, 0x99FFFFFF);
        card.addView(unit);

        speedOdo = Ui.text(this, "", 12, 0xB3FFFFFF);
        speedOdo.setPadding(0, dp(6), 0, 0);
        card.addView(speedOdo);

        Ui.pressable(card, 0.97f);
        card.setOnClickListener(v -> startActivity(new Intent(this, OilActivity.class)));
        return card;
    }

    private void updateSpeedometer() {
        int spd = Odometer.currentSpeed();
        speedValue.setText(String.valueOf(spd));
        if (Odometer.isConfigured(this)) {
            speedOdo.setText("Odómetro: " + OilActivity.fmt(Odometer.currentKm(this)) + " km");
        } else {
            speedOdo.setText("GPS activo");
        }
    }

    private final Runnable livePoll = new Runnable() {
        public void run() {
            updateSpeedometer();
            updateNavWidget();
            handler.postDelayed(this, 500);
        }
    };

    // ---------- Music (Reducido a la mitad + Verificador de permisos) ----------

    private View buildMusicCard() {
        FrameLayout card = new FrameLayout(this);
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                Ui.pick(this, new int[]{0xFF6A3BFF, 0xFFC04DE8, 0xFF2B1A7A},
                        new int[]{0xFF2E1A55, 0xFF1A1030, 0xFF0A0A0E}));
        bg.setCornerRadius(dp(18));
        card.setBackground(bg);
        card.setClipToOutline(true);

        art = new ImageView(this);
        art.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(art, new FrameLayout.LayoutParams(-1, -1));

        View shade = new View(this);
        shade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x44000000, 0x22000000, 0xDD100A30}));
        card.addView(shade, new FrameLayout.LayoutParams(-1, -1));

        songSource = Ui.text(this, "MÚSICA", 11, Color.WHITE);
        songSource.setLetterSpacing(0.1f);
        songSource.setPadding(dp(10), dp(4), dp(10), dp(4));
        GradientDrawable chip = new GradientDrawable();
        chip.setColor(0x45000000);
        chip.setCornerRadius(dp(12));
        songSource.setBackground(chip);
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START);
        slp.setMargins(dp(12), dp(10), 0, 0);
        card.addView(songSource, slp);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.VERTICAL);
        bottom.setGravity(Gravity.CENTER_HORIZONTAL);
        bottom.setPadding(dp(12), 0, dp(12), dp(10));

        songTitle = Ui.text(this, "", 17, Color.WHITE);
        songTitle.setTypeface(Typeface.DEFAULT_BOLD);
        songTitle.setSingleLine(true);
        bottom.addView(songTitle);

        songArtist = Ui.text(this, "", 13, 0xCCFFFFFF);
        songArtist.setSingleLine(true);
        songArtist.setPadding(0, dp(2), 0, dp(8));
        bottom.addView(songArtist);

        LinearLayout controls = new LinearLayout(this);
        controls.setGravity(Gravity.CENTER);
        controls.addView(Ui.iconButton(this, R.drawable.ic_prev, 42, false, () -> nowPlaying.skip(false)),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        playBtn = Ui.iconButton(this, R.drawable.ic_play, 52, true, () -> nowPlaying.playPause());
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(dp(52), dp(52));
        plp.setMargins(dp(14), 0, dp(14), 0);
        controls.addView(playBtn, plp);
        controls.addView(Ui.iconButton(this, R.drawable.ic_next, 42, false, () -> nowPlaying.skip(true)),
                new LinearLayout.LayoutParams(dp(42), dp(42)));
        bottom.addView(controls);

        card.addView(bottom, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));

        Ui.pressable(card, 0.98f);
        card.setOnClickListener(v -> {
            if (!hasNotificationAccess()) {
                toast("Activa el permiso para CleanLauncher");
                safeStart(new Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"));
            } else {
                nowPlaying.open(this);
            }
        });
        return card;
    }

    private boolean hasNotificationAccess() {
        String flat = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return flat != null && flat.contains(getPackageName());
    }

    @Override
    public void onNowPlayingChanged() {
        boolean permOk = hasNotificationAccess();
        NowPlaying.Source s = nowPlaying.current();
        NowPlaying.Track t = s == null ? null : nowPlaying.track(s);
        boolean empty = t == null || t.title.isEmpty();

        songSource.setText(s == null ? "MÚSICA" : nowPlaying.label(s).toUpperCase());
        if (!permOk && empty) {
            songTitle.setText("Activar permiso");
            songArtist.setText("Toca aquí para leer música y GPS");
        } else {
            songTitle.setText(empty ? "Nada sonando" : t.title);
            songArtist.setText(empty ? "Toca para abrir reproductor" : t.artist);
        }
        playBtn.setImageResource(t != null && t.playing ? R.drawable.ic_pause : R.drawable.ic_play);

        if (t != null && t.art != null) {
            art.setPadding(0, 0, 0, 0);
            art.setScaleType(ImageView.ScaleType.CENTER_CROP);
            art.clearColorFilter();
            art.setAlpha(1f);
            art.setImageBitmap(t.art);
        } else {
            art.setPadding(dp(60), dp(14), dp(60), dp(74));
            art.setScaleType(ImageView.ScaleType.FIT_CENTER);
            art.setImageResource(s == NowPlaying.Source.BT ? R.drawable.ic_bluetooth : R.drawable.ic_music);
            art.setColorFilter(Color.WHITE);
            art.setAlpha(0.22f);
        }
    }

    // ---------- Widget de Navegación Funcional (Debajo de Música) ----------

    private View buildNavCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(12), dp(14), dp(12));
        GradientDrawable bg = new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                Ui.pick(this, new int[]{0xFF0F3D3E, 0xFF1B5E20, 0xFF0A2229},
                        new int[]{0xFF102820, 0xFF0D1B1E, 0xFF091014}));
        bg.setCornerRadius(dp(18));
        bg.setStroke(dp(1), 0x444CAF50);
        card.setBackground(bg);

        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView navIcon = new ImageView(this);
        navIcon.setImageResource(R.drawable.ic_compass);
        navIcon.setColorFilter(0xFF69F0AE);
        head.addView(navIcon, new LinearLayout.LayoutParams(dp(18), dp(18)));

        navStatus = Ui.text(this, "NAVEGACIÓN", 11, 0xFF69F0AE);
        navStatus.setTypeface(Typeface.DEFAULT_BOLD);
        navStatus.setLetterSpacing(0.08f);
        navStatus.setPadding(dp(6), 0, 0, 0);
        head.addView(navStatus);
        card.addView(head);

        LinearLayout centerBox = new LinearLayout(this);
        centerBox.setOrientation(LinearLayout.VERTICAL);
        centerBox.setGravity(Gravity.CENTER);

        navMainText = Ui.text(this, "Listo para navegar", 17, Color.WHITE);
        navMainText.setTypeface(Typeface.DEFAULT_BOLD);
        navMainText.setSingleLine(false);
        navMainText.setMaxLines(2);
        centerBox.addView(navMainText);

        navSubText = Ui.text(this, "Elige Maps, Waze o busca un destino", 12, 0xCCFFFFFF);
        navSubText.setSingleLine(true);
        navSubText.setPadding(0, dp(3), 0, 0);
        centerBox.addView(navSubText);

        card.addView(centerBox, new LinearLayout.LayoutParams(-1, 0, 1f));

        // Botones rápidos: Maps | Waze | Buscar destino
        LinearLayout actions = new LinearLayout(this);
        actions.setGravity(Gravity.CENTER);
        actions.addView(navPill("Maps", this::openMaps), weight(1f, 0));
        actions.addView(navPill("Waze", this::openWaze), weight(1f, dp(8)));
        actions.addView(navPill("Buscar", this::searchDestination), weight(1f, dp(8)));
        card.addView(actions, new LinearLayout.LayoutParams(-1, dp(34)));

        Ui.pressable(card, 0.98f);
        card.setOnClickListener(v -> openMaps());
        return card;
    }

    private View navPill(String label, Runnable action) {
        TextView btn = Ui.text(this, label, 12, Color.WHITE);
        btn.setTypeface(Typeface.DEFAULT_BOLD);
        btn.setGravity(Gravity.CENTER);
        GradientDrawable pill = new GradientDrawable();
        pill.setColor(0x33FFFFFF);
        pill.setCornerRadius(dp(12));
        btn.setBackground(pill);
        Ui.pressable(btn, 0.92f);
        btn.setOnClickListener(v -> action.run());
        return btn;
    }

    private void updateNavWidget() {
        boolean active = (SystemClock.elapsedRealtime() - navLastUpdate < 45000) && !navHeadline.isEmpty();
        if (active) {
            navStatus.setText("RUTA EN CURSO");
            navMainText.setText(navHeadline);
            navSubText.setText(navSubtext.isEmpty() ? "Toca para volver al mapa" : navSubtext);
        } else {
            int spd = Odometer.currentSpeed();
            navStatus.setText(spd > 3 ? "EN MOVIMIENTO · " + spd + " KM/H" : "NAVEGACIÓN GPS");
            navMainText.setText("¿A dónde vamos?");
            navSubText.setText("Inicia una ruta en Maps o Waze");
        }
    }

    private void openWaze() {
        Intent waze = getPackageManager().getLaunchIntentForPackage("com.waze");
        if (waze != null && safeStart(waze)) return;
        if (!launchByKeyword("waze", "maps", "naveg", "gps")) openMaps();
    }

    private void searchDestination() {
        Intent search = new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q="));
        search.setPackage("com.google.android.apps.maps");
        if (!safeStart(search)) {
            safeStart(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=")));
        }
    }

    // ---------- Dock ----------

    private View buildDock() {
        LinearLayout dock = new LinearLayout(this);
        dock.setGravity(Gravity.CENTER_VERTICAL);
        dock.addView(dockIcon(R.drawable.ic_compass, this::openMaps), weight(1f, 0));
        dock.addView(dockIcon(R.drawable.ic_bluetooth, this::openBluetooth), weight(1f, 0));
        dock.addView(dockIcon(R.drawable.ic_radio, this::openRadio), weight(1f, 0));

        FrameLayout center = new FrameLayout(this);
        RingView ring = new RingView(this);
        center.addView(ring, new FrameLayout.LayoutParams(dp(64), dp(64), Gravity.CENTER));
        Ui.pressable(ring, 0.88f);
        ring.setOnClickListener(v -> startActivity(new Intent(this, AppsActivity.class)));
        dock.addView(center, weight(1f, 0));

        dock.addView(dockIcon(R.drawable.ic_w_moon,
                () -> startActivity(new Intent(this, ScreenOffActivity.class))), weight(1f, 0));
        dock.addView(dockIcon(R.drawable.ic_settings, this::openEqualizer), weight(1f, 0));
        dock.addView(dockIcon(R.drawable.ic_gear, this::openSettings), weight(1f, 0));
        return dock;
    }

    // ---------- Shortcuts ----------

    private void openRadio() {
        if (Profile.isNwd(this)) startActivity(new Intent(this, RadioActivity.class));
        else if (!launchByKeyword("radio", "fm", "tuner")) toast("No se encontró una app de radio");
    }

    private void openBluetooth() {
        if (Profile.isNwd(this)) startActivity(new Intent(this, BtActivity.class));
        else if (!launchByKeyword("bluetooth", "bt music", "btmusic", "phone link"))
            safeStart(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
    }

    private void openEqualizer() {
        if (Profile.isNwd(this)) {
            startActivity(new Intent(this, EqActivity.class));
            return;
        }
        Intent eq = new Intent(android.media.audiofx.AudioEffect.ACTION_DISPLAY_AUDIO_EFFECT_CONTROL_PANEL);
        if (!safeStart(eq) && !launchByKeyword("ecualiz", "equaliz", "audio", "sonido", "dsp"))
            toast("No se encontró un ecualizador");
    }

    private void openSettings() {
        if (Profile.isNwd(this)) Ui.launch(this, SETTINGS);
        else safeStart(new Intent(Settings.ACTION_SETTINGS));
    }

    private void openMaps() {
        Intent maps = getPackageManager().getLaunchIntentForPackage("com.google.android.apps.maps");
        if (maps == null || !safeStart(maps)) {
            safeStart(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse("geo:0,0?q=")));
        }
    }

    private boolean launchByKeyword(String... keywords) {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(main, 0)) {
            if (ri.activityInfo.packageName.equals(getPackageName())) continue;
            String label = (ri.loadLabel(pm) + " " + ri.activityInfo.packageName).toLowerCase();
            for (String k : keywords) {
                if (label.contains(k)) {
                    Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                            .setClassName(ri.activityInfo.packageName, ri.activityInfo.name);
                    return safeStart(i);
                }
            }
        }
        return false;
    }

    private boolean safeStart(Intent i) {
        try {
            startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void toast(String s) {
        android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show();
    }

    private View dockIcon(int icon, Runnable action) {
        FrameLayout f = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setImageResource(icon);
        iv.setColorFilter(0xE6FFFFFF);
        iv.setPadding(dp(12), dp(12), dp(12), dp(12));
        f.addView(iv, new FrameLayout.LayoutParams(dp(56), dp(56), Gravity.CENTER));
        Ui.pressable(iv, 0.85f);
        iv.setOnClickListener(v -> action.run());
        return f;
    }

    // ---------- Lifecycle & helpers ----------

    @Override
    protected void onResume() {
        super.onResume();
        if (Ui.isDark(this) != createdDark || !profileStamp().equals(createdProfile)) {
            recreate();
            return;
        }
        updateGreeting();
        updateOil();
        updateSpeedometer();
        updateNavWidget();
        showWeather(Weather.cached(this));
        registerReceiver(illReceiver, new android.content.IntentFilter("com.nwd.ACTION_ILL_STATE_CHANGE"));
        boolean lights = hasLightsImage && carLightsOn();
        carLights.setAlpha(lights ? 1f : 0f);
        car.setAlpha(lights ? 0f : 1f);
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION}, 1);
        }
        handler.post(periodic);
        handler.post(livePoll);
        nowPlaying.addListener(this);
        onNowPlayingChanged();
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(illReceiver);
        handler.removeCallbacks(periodic);
        handler.removeCallbacks(livePoll);
        nowPlaying.removeListener(this);
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            fetchWeather(true);
            startService(new Intent(this, Odometer.class));
        }
    }

    private TextView left(TextView tv) {
        tv.setGravity(Gravity.START);
        return tv;
    }

    private LinearLayout.LayoutParams weight(float w, int left) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, w);
        lp.leftMargin = left;
        return lp;
    }

    private LinearLayout.LayoutParams vweight(float w, int top) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, 0, w);
        lp.topMargin = top;
        return lp;
    }

    private int dp(int v) {
        return Ui.dp(this, v);
    }

    static void pressable(View v) {
        Ui.pressable(v, 0.94f);
    }

    @Override
    public void onBackPressed() {
        // Home screen: nothing to go back to.
    }
}
