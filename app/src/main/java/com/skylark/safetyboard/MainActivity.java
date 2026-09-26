package com.skylark.safetyboard;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.RouteInfo;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.Log;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.IOException;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import pl.droidsonroids.gif.GifImageView;

public class MainActivity extends AppCompatActivity {
    private static final String DATE_TIME_PREFS = "SAFETY_DATE_TIME_PREFS";
    private static final String KEY_DATE_TIME_MODE = "date_time_mode";
    private static final String MODE_AUTO = "AUTO";
    private static final String MODE_MANUAL = "MANUAL";

    private Handler dateTimeHandler;
    private Runnable autoDateTimeRunnable;
    private TextWatcher dateTextWatcher;
    private TextWatcher timeTextWatcher;
    private final String esp32Ip = "192.168.4.1";
    private final String prefsKey = "ESP32_DATA";
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build();
    GifImageView gifImageView;
    private TextView textViewStatus;
    private final BroadcastReceiver wifiReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            updateWifiStatus();
        }
    };
    private List<EditText> editTexts;

    @SuppressLint("SetTextI18n")
    private void updateWifiStatus() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(CONNECTIVITY_SERVICE);
        if (cm == null) return;

        Network network = cm.getActiveNetwork();
        if (network == null) {
            runOnUiThread(() -> textViewStatus.setText("Status: Device not connected"));
            return;
        }

        LinkProperties props = cm.getLinkProperties(network);
        boolean connected = false;

        if (props != null) {
            for (RouteInfo route : props.getRoutes()) {
                InetAddress gateway = route.getGateway();
                if (gateway != null && esp32Ip.equals(gateway.getHostAddress())) {
                    connected = true;
                    break;
                }
            }
        }

        final boolean status = connected;
        runOnUiThread(() -> textViewStatus.setText(status ? "Status: Connected to Device" : "Status: Device not connected"));
    }

    @SuppressWarnings("deprecation")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Thread.setDefaultUncaughtExceptionHandler((t, e) ->
                Log.e("CRASH", "Uncaught exception in thread " + t.getName(), e));

        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        registerReceiver(wifiReceiver, new IntentFilter(ConnectivityManager.CONNECTIVITY_ACTION));

        Button buttonSend = findViewById(R.id.buttonSend);
        Button buttonClear = findViewById(R.id.buttonClear);
        Button buttonWifi = findViewById(R.id.buttonWifi);
        Button buttonSettings = findViewById(R.id.buttonSettings);
        Button buttonDateTimeOptions = findViewById(R.id.buttonDateTimeOptions);
        Button buttonReset = findViewById(R.id.buttonReset);
        textViewStatus = findViewById(R.id.textViewStatus);
        gifImageView = findViewById(R.id.gifImageView);

        editTexts = new ArrayList<>();
        for (int id : new int[]{
                R.id.editText1, R.id.editText2, R.id.editText3, R.id.editText4,R.id.editText5
        }) {
            editTexts.add(findViewById(id));
        }

        setupEditTexts();
        loadSavedData();
        setupSaveButtonWatcher();

        buttonWifi.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_WIFI_SETTINGS)));
        buttonSettings.setOnClickListener(v -> showSettingsDialog());
        buttonDateTimeOptions.setOnClickListener(v -> showDateTimeOptionsDialog());
        buttonReset.setOnClickListener(v -> ResetData());
        buttonSend.setOnClickListener(v -> sendData());
        buttonClear.setOnClickListener(v -> clearData());
    }

    private void setupEditTexts() {
        createDateTextWatcher();
        createDateLastAccidentTextWatcher();
        createTimeTextWatcher();

        SharedPreferences dtPrefs = getSharedPreferences(DATE_TIME_PREFS, MODE_PRIVATE);
        String savedMode = dtPrefs.getString(KEY_DATE_TIME_MODE, MODE_AUTO);
        applyDateTimeMode(savedMode);
    }

    private void createDateTextWatcher() {

        EditText editTextDate = editTexts.get(0);

        dateTextWatcher = new TextWatcher() {

            private boolean isFormatting = false;

            @Override
            public void afterTextChanged(Editable s) {

                if (isFormatting || s == null) return;

                String clean = s.toString().replace(".", "");

                // Maximum 6 digits: DDMMYY
                if (clean.length() > 6) {
                    clean = clean.substring(0, 6);
                }

                isFormatting = true;

                StringBuilder builder = new StringBuilder();

                for (int i = 0; i < clean.length(); i++) {

                    builder.append(clean.charAt(i));

                    // Add dots after DD and MM
                    if ((i == 1 || i == 3) &&
                            i != clean.length() - 1) {
                        builder.append('.');
                    }
                }

                editTextDate.setText(builder.toString());
                editTextDate.setSelection(builder.length());

                // Validate day and month
                if (clean.length() >= 4) {

                    try {

                        int day = Integer.parseInt(
                                clean.substring(0, 2)
                        );

                        int month = Integer.parseInt(
                                clean.substring(2, 4)
                        );

                        if (day < 1 || day > 31 ||
                                month < 1 || month > 12) {

                            Toast.makeText(
                                    MainActivity.this,
                                    "Please enter a valid Date",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }

                    } catch (NumberFormatException ignored) {
                    }
                }

                isFormatting = false;
            }

            @Override
            public void beforeTextChanged(
                    CharSequence s,
                    int start,
                    int count,
                    int after) {
            }

            @Override
            public void onTextChanged(
                    CharSequence s,
                    int start,
                    int before,
                    int count) {
            }
        };

        // Numeric keyboard
        editTextDate.setInputType(
                InputType.TYPE_CLASS_NUMBER
        );

        // Maximum displayed length = 8
        // DD.MM.YY
        editTextDate.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(8)
        });

        editTextDate.setFocusable(true);
        editTextDate.setFocusableInTouchMode(true);

        // Attach watcher
        editTextDate.addTextChangedListener(dateTextWatcher);
    }
    private void createDateLastAccidentTextWatcher() {

        EditText editTextLA = editTexts.get(2);

        TextWatcher last_accident_TextWatcher = new TextWatcher() {

            private boolean isFormatting = false;

            @Override
            public void afterTextChanged(Editable s) {

                if (isFormatting || s == null) return;

                String clean = s.toString().replace(".", "");

                // Maximum 6 digits: DDMMYY
                if (clean.length() > 6) {
                    clean = clean.substring(0, 6);
                }

                isFormatting = true;

                StringBuilder builder = new StringBuilder();

                for (int i = 0; i < clean.length(); i++) {

                    builder.append(clean.charAt(i));

                    if ((i == 1 || i == 3) && i != clean.length() - 1) {
                        builder.append('.');
                    }
                }

                editTextLA.setText(builder.toString());
                editTextLA.setSelection(builder.length());

                // Validate day and month
                if (clean.length() >= 4) {

                    try {

                        int day = Integer.parseInt(clean.substring(0, 2));
                        int month = Integer.parseInt(clean.substring(2, 4));

                        if (day < 1 || day > 31 ||
                                month < 1 || month > 12) {

                            Toast.makeText(
                                    MainActivity.this,
                                    "Please enter a valid Date",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }

                    } catch (NumberFormatException ignored) {
                    }
                }

                isFormatting = false;
            }

            @Override
            public void beforeTextChanged(
                    CharSequence s,
                    int start,
                    int count,
                    int after) {
            }

            @Override
            public void onTextChanged(
                    CharSequence s,
                    int start,
                    int before,
                    int count) {
            }
        };

        editTextLA.setInputType(InputType.TYPE_CLASS_NUMBER);

        editTextLA.setFilters(new InputFilter[]{
                new InputFilter.LengthFilter(8)
        });

        editTextLA.setFocusable(true);
        editTextLA.setFocusableInTouchMode(true);

        editTextLA.addTextChangedListener(last_accident_TextWatcher);
    }
    private void createTimeTextWatcher() {
        EditText editText1 = editTexts.get(1);
        timeTextWatcher = new TextWatcher() {
            private boolean isFormatting = false;

            @Override
            public void afterTextChanged(Editable s) {
                if (isFormatting || s == null) return;

                String clean = s.toString().replace(":", "");
                if (clean.length() > 6) return;

                isFormatting = true;

                StringBuilder builder = new StringBuilder();
                for (int i = 0; i < clean.length(); i++) {
                    builder.append(clean.charAt(i));
                    if ((i == 1 || i == 3) && i != clean.length() - 1) {
                        builder.append(':');
                    }
                }

                editText1.setText(builder.toString());
                editText1.setSelection(builder.length());

                if (clean.length() >= 4) {
                    try {
                        int hour = Integer.parseInt(clean.substring(0, 2));
                        int minute = Integer.parseInt(clean.substring(2, 4));
                        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
                            Toast.makeText(MainActivity.this, "Please enter a valid Time", Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException ignored) {}
                }

                if (clean.length() == 6) {
                    try {
                        int second = Integer.parseInt(clean.substring(4, 6));
                        if (second < 0 || second > 59) {
                            Toast.makeText(MainActivity.this, "Please enter a valid Time", Toast.LENGTH_SHORT).show();
                        }
                    } catch (NumberFormatException ignored) {}
                }
                isFormatting = false;
            }

            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
        };
    }

    private void applyDateTimeMode(String mode) {
        EditText dateEdit = editTexts.get(0);
        EditText timeEdit = editTexts.get(1);

        stopAutoDateTimeUpdater();
        dateEdit.removeTextChangedListener(dateTextWatcher);
        timeEdit.removeTextChangedListener(timeTextWatcher);

        if (MODE_AUTO.equals(mode)) {
            dateEdit.setInputType(InputType.TYPE_NULL);
            timeEdit.setInputType(InputType.TYPE_NULL);
            dateEdit.setFocusable(false);
            timeEdit.setFocusable(false);
            startAutoDateTimeUpdater();
        } else {
            dateEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            timeEdit.setInputType(InputType.TYPE_CLASS_NUMBER);
            dateEdit.setFocusable(true);
            dateEdit.setFocusableInTouchMode(true);
            timeEdit.setFocusable(true);
            timeEdit.setFocusableInTouchMode(true);
            dateEdit.addTextChangedListener(dateTextWatcher);
            timeEdit.addTextChangedListener(timeTextWatcher);
        }
    }

    @SuppressWarnings("deprecation")
    private void startAutoDateTimeUpdater() {
        if (dateTimeHandler == null) dateTimeHandler = new Handler();

        autoDateTimeRunnable = new Runnable() {
            @Override
            public void run() {
                Calendar calendar = Calendar.getInstance();

                int day = calendar.get(Calendar.DAY_OF_MONTH);
                int month = calendar.get(Calendar.MONTH) + 1;
                int year = calendar.get(Calendar.YEAR) % 100;
                editTexts.get(0).setText(String.format(Locale.getDefault(), "%02d.%02d.%02d", day, month, year));

                int hour = calendar.get(Calendar.HOUR_OF_DAY);
                int minute = calendar.get(Calendar.MINUTE);
                int second = calendar.get(Calendar.SECOND);
                editTexts.get(1).setText(String.format(Locale.getDefault(), "%02d:%02d:%02d", hour, minute, second));

                dateTimeHandler.postDelayed(this, 1000);
            }
        };
        dateTimeHandler.post(autoDateTimeRunnable);
    }

    private void stopAutoDateTimeUpdater() {
        if (dateTimeHandler != null && autoDateTimeRunnable != null) {
            dateTimeHandler.removeCallbacks(autoDateTimeRunnable);
        }
    }

    private void showDateTimeOptionsDialog() {
        SharedPreferences dtPrefs = getSharedPreferences(DATE_TIME_PREFS, MODE_PRIVATE);
        String savedMode = dtPrefs.getString(KEY_DATE_TIME_MODE, MODE_MANUAL);

        View dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_date_time_options, null);
        RadioGroup radioGroup = dialogView.findViewById(R.id.radioGroupDateTimeMode);
        RadioButton radioAutomatic = dialogView.findViewById(R.id.radioAutomatic);
        RadioButton radioManual = dialogView.findViewById(R.id.radioManual);

        if (MODE_AUTO.equals(savedMode)) {
            radioAutomatic.setChecked(true);
        } else {
            radioManual.setChecked(true);
        }

        new AlertDialog.Builder(this)
                .setTitle("Date/Time Options")
                .setView(dialogView)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newMode = (radioGroup.getCheckedRadioButtonId() == R.id.radioAutomatic)
                            ? MODE_AUTO
                            : MODE_MANUAL;

                    dtPrefs.edit().putString(KEY_DATE_TIME_MODE, newMode).apply();
                    applyDateTimeMode(newMode);
                    Toast.makeText(this, "Date/Time mode updated", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
    // === Wi-Fi settings dialog ===
    private void showSettingsDialog() {
        EditText ssid = new EditText(this), pass = new EditText(this);
        ssid.setHint("Enter New SSID");
        pass.setHint("Enter New Password");

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(40, 30, 40, 10);
        layout.addView(ssid);
        layout.addView(pass);

        new AlertDialog.Builder(this)
                .setTitle("New Wi-Fi Credentials")
                .setView(layout)
                .setMessage("Please enter new SSID and Password. Click OK to continue.")
                .setPositiveButton("OK", (d, w) -> {
                    String s = ssid.getText().toString().trim();
                    String p = pass.getText().toString().trim();
                    if (!s.isEmpty() && !p.isEmpty()) {
                        showConfirmPopup(s, p);
                    } else {
                        Toast.makeText(this, "Please enter both SSID and Password", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void showConfirmPopup(String ssid, String password) {
        String message = "Do you want to update the following Wi-Fi credentials?\n\n"
                + "SSID: " + ssid + "\n"
                + "Password: " + password;

        new AlertDialog.Builder(this)
                .setTitle("Confirm Wi-Fi Update")
                .setMessage(message)
                .setPositiveButton("Confirm", (d, w) -> confirmWifiCredentials(ssid, password))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void confirmWifiCredentials(String ssid, String password) {
        String url = "http://" + esp32Ip + "/setwifi?ssid=" + ssid + "&pass=" + password;

        client.newCall(new Request.Builder().url(url).build()).enqueue(new BaseCallback() {
            @Override
            public void onSuccess(Response r) {
                runOnUiThread(() -> new AlertDialog.Builder(MainActivity.this)
                        .setTitle("Success")
                        .setMessage("Wi-Fi credentials updated successfully.\n\nSSID: " + ssid + "\nPassword: " + password)
                        .setPositiveButton("OK", null)
                        .show());
            }
            @Override
            public void onHttpError(Response r) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Error: " + r.code(), Toast.LENGTH_SHORT).show());
            }
            @Override
            public void onFailureHandled(IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed: " + e.getMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }


    private void ResetData() {
        editTexts.get(3).setText("0");

        String url = "http://" + esp32Ip + "/reset";
        client.newCall(new Request.Builder().url(url).build()).enqueue(new BaseCallback() {
            @Override
            public void onSuccess(Response r) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Accident free days reset!", Toast.LENGTH_SHORT).show();
                    saveData();
                });
            }

            @Override
            public void onHttpError(Response r) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "HTTP Error: " + r.code(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onFailureHandled(IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to reset: " + e.getLocalizedMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }
    private void sendData() {
        List<String> dataList = new ArrayList<>();

        String date = editTexts.get(0).getText().toString().trim();
        String time = editTexts.get(1).getText().toString().trim();

        String pre_date = editTexts.get(2).getText().toString().trim();
        String set_days = editTexts.get(3).getText().toString().trim();
        String pre_days = editTexts.get(4).getText().toString().trim();

        if (!date.isEmpty()) dataList.add("date=" + date);
        if (!time.isEmpty()) dataList.add("time=" + time);

        if (!pre_date.isEmpty()) dataList.add("pre_date:" + pre_date);
        if (!set_days.isEmpty()) dataList.add("set_days:" + set_days);
        if (!pre_days.isEmpty()) dataList.add("pre_days:" + pre_days);


        if (dataList.isEmpty()) {
            Toast.makeText(this, "Enter at least one value", Toast.LENGTH_SHORT).show();
            return;
        }

        String url = "http://" + esp32Ip + "/update?" + String.join("&", dataList);
        client.newCall(new Request.Builder().url(url).build()).enqueue(new BaseCallback() {
            @Override
            public void onSuccess(Response r) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Data sent and saved!", Toast.LENGTH_SHORT).show();
                    saveData();
                });
            }

            @Override
            public void onHttpError(Response r) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "HTTP Error: " + r.code(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onFailureHandled(IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Failed to send: " + e.getLocalizedMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // === Clear display ===
    private void clearData() {
        for (EditText e : editTexts) e.setText("");

        String url = "http://" + esp32Ip + "/clear";
        client.newCall(new Request.Builder().url(url).build()).enqueue(new BaseCallback() {
            @Override
            public void onSuccess(Response r) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this, "Display cleared, loading saved data...", Toast.LENGTH_LONG).show();
                    loadSavedData();
                });
            }

            @Override
            public void onHttpError(Response r) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Clear HTTP Error: " + r.code(), Toast.LENGTH_SHORT).show());
            }

            @Override
            public void onFailureHandled(IOException e) {
                runOnUiThread(() -> Toast.makeText(MainActivity.this, "Clear failed: " + e.getLocalizedMessage(), Toast.LENGTH_SHORT).show());
            }
        });
    }

    // === Persistence ===
    private void setupSaveButtonWatcher() {
        TextWatcher watcher = new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        };
        for (EditText e : editTexts) e.addTextChangedListener(watcher);
    }

    private void saveData() {
        SharedPreferences.Editor editor = getSharedPreferences(prefsKey, MODE_PRIVATE).edit();
        editor.clear();
        for (int i = 0; i < editTexts.size(); i++) {
            String value = editTexts.get(i).getText().toString().trim();
            if (!value.isEmpty()) editor.putString("v" + (i + 1), value);
        }
        editor.apply();
    }

    private void loadSavedData() {
        SharedPreferences prefs = getSharedPreferences(prefsKey, MODE_PRIVATE);
        for (int i = 0; i < editTexts.size(); i++) {
            editTexts.get(i).setText(prefs.getString("v" + (i + 1), ""));
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(wifiReceiver);
    }

    public abstract static class BaseCallback implements Callback {
        @Override
        public void onFailure(@NonNull Call call, @NonNull IOException e) {
            Log.e("HTTP", "Request failed: " + e.getLocalizedMessage(), e);
            onFailureHandled(e);
        }

        @Override
        public void onResponse(@NonNull Call call, Response response) {
            if (!response.isSuccessful()) onHttpError(response);
            else onSuccess(response);
        }

        public abstract void onSuccess(Response response);

        public void onFailureHandled(IOException e) {
        }

        public void onHttpError(Response response) {
        }
    }
}

