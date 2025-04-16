package com.example.driveguard.activities;

import static com.example.driveguard.GsonUtilities.JsonToTrip;
import static com.example.driveguard.activities.HomeScreen.CHANNEL_ID;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

import android.Manifest;
import android.app.PendingIntent;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.os.Bundle;
import android.os.Handler;
import android.os.StrictMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ToggleButton;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.example.driveguard.ButtonDeck;
import com.example.driveguard.DataClassifier;
import com.example.driveguard.NetworkManager;
import com.example.driveguard.R;
import com.example.driveguard.Utilities;
import com.example.driveguard.objects.Road;
import com.example.driveguard.objects.Trip;

import java.io.IOException;
import java.util.Date;

import lombok.Getter;
import lombok.Setter;
import okhttp3.Response;
import com.example.driveguard.DataCollector;
import com.example.driveguard.objects.Weather;
import com.google.gson.Gson;

@Getter
@Setter
public class TripScreen extends AppCompatActivity {

    private DataCollector dataCollector;
    private NetworkManager networkManager;
    private DataClassifier dataClassifier;
    private Trip currentTrip;
    private final int START_TRIP_SUCCESS = 201;
    private final int STOP_TRIP_SUCCESS = 200;
    private static final int MILLISECONDS_IN_A_MINUTE = 1000;
    private static final int SECONDS_IN_A_MINUTE = 60;
    private static final int EVENT_INTERVAL_SECONDS = 15;
    private static final int WEATHER_CHECK_INTERVAL_MINUTES = 30;
    private static final String TURN_EVENT = "turn";
    private static final String SPEEDING_EVENT = "speed";
    private static final String BRAKE_EVENT = "brake";
    private static final String ACCELERATION_EVENT = "accelerate";
    private TextView limitText;
    private TextView speedText;
    private float speed;
    private float limit;

    private Map<String, Boolean> eventHasBeenDetected = new HashMap<>();

    private Date timeLastChecked30Min = new Date();

    private Date timeLastChecked15Sec = new Date();

    private Weather currentWeather;

    private int postedSpeedLimit;

    private ToggleButton startButton;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.screen_trip);

        limitText = findViewById(R.id.limit);
        speedText = findViewById(R.id.speed);

        //allows the UI thread to perform network calls. We could make them async if this causes issues
        StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder().permitAll().build();
        StrictMode.setThreadPolicy(policy);

        Utilities.ResetTripId(this);

        dataCollector = new DataCollector(getApplicationContext());

        NetworkManager networkManager = new NetworkManager(getApplicationContext());

        //defines the toolbar used in the activity
        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        //toggle button that is used to stop and start trips
        startButton = findViewById(R.id.startButton);

        ButtonDeck.SetUpButtons(this);
        ButtonDeck.TintButton(this);

        if (!Utilities.CheckLoggedIn(this) || !Utilities.CheckDataCollection(this)){
            startButton.setEnabled(false);
        }
        if (Utilities.checkConnection(this)) {
            if (!Utilities.checkLocationPermission(this)) {
                enterDisabledState();
                return;
            }
            Location startingLocation = dataCollector.getStartingLocation();

            if (startingLocation == null){
                enterDisabledState();
                return;
            }
            else {
                RequestWeather(dataCollector.getStartingLocation());
                RequestRoad(dataCollector.getStartingLocation());
            }
        } else {
            enterDisabledState();
            return;
        }
        // Create a handler and runnable for the async loop
        final Handler handler = new Handler();
        Runnable runnable = new Runnable() {
            @Override
            public void run() {
                // Check for events periodically
                checkForEvents();

                // Schedule the next execution after 25 milliseconds
                handler.postDelayed(this, 25);
            }
        };

        startButton.setOnClickListener(new View.OnClickListener() {
            //@SneakyThrows
            @Override
            public void onClick(View v) {
                ButtonDeck.ToggleButtons(TripScreen.this);

                NetworkManager networkManager = new NetworkManager(getApplicationContext());

                //start trip here
                if (startButton.isChecked()) {//for starting trip
                    // 201 means a trip was started successfully
                    Response response = networkManager.startTrip(dataCollector.getStartingLocation());
                    if (response != null && response.code() == START_TRIP_SUCCESS) {

                        Toast.makeText(TripScreen.this, "Trip Successfully started!", Toast.LENGTH_LONG).show();

                        dataCollector.startDataCollection(getApplicationContext());

                        try {
                            assert response.body() != null;
                            currentTrip = JsonToTrip(response.body().string());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }

                        Utilities.SaveTripID(getApplicationContext(), currentTrip.getId());

                        // Start the async loop
                        handler.postDelayed(runnable, 25);

                    } else {
                        assert response != null;
                        if (response.code() == 409) {//code 409 means a trip is already underway if this is the case call to end it
                            Response tripResponse = networkManager.getCurrentTrip();

                            if (tripResponse.isSuccessful()) {
                                assert tripResponse.body() != null;
                                Trip current = null;
                                try {
                                    current = JsonToTrip(tripResponse.body().string());
                                } catch (IOException e) {
                                    throw new RuntimeException(e);
                                }
                                Utilities.SaveTripID(getApplicationContext(), current.getId());
                            }

                            Response endResponse = networkManager.endTrip(dataCollector.getStartingLocation());

                            if (endResponse.isSuccessful()) {
                                Toast.makeText(TripScreen.this, "ERROR: cancelling previous trip", Toast.LENGTH_LONG).show();
                            }
                            startButton.setChecked(false);
                            ButtonDeck.ToggleButtons(TripScreen.this);
                        } else { // Set button back to unchecked
                            Toast.makeText(TripScreen.this, "ERROR: " + response.code(), Toast.LENGTH_LONG).show();
                            startButton.setChecked(false);
                        }
                    }
                } else if (!startButton.isChecked()) { // For ending trip
                    Response response = networkManager.endTrip(dataCollector.getStartingLocation());
                    if (response != null && response.code() == STOP_TRIP_SUCCESS) {

                        Toast.makeText(TripScreen.this, "Trip successfully ended!", Toast.LENGTH_LONG).show();

                        // Stop the async loop
                        handler.removeCallbacks(runnable);

                        dataCollector.stopDataCollection();

                        // Score screen ic called - Stephan
                        assert response.body() != null;
                        try {
                            currentTrip = JsonToTrip(response.body().string());
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                        ScoreDialog scoreDialog = new ScoreDialog();
                        scoreDialog.setTrip(currentTrip);
                        scoreDialog.show(getSupportFragmentManager(), "ScoreScreen");
                        Utilities.ResetTripId(TripScreen.this);
                    }
                    }
                }
            });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        int tripId = Utilities.LoadTripID(this);
        if (tripId != -1){

            if (Utilities.CheckNotifications(this)) {
                // Create an explicit intent for an Activity in your app.
                Intent intent = new Intent(this, TripScreen.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE);

                NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                        .setSmallIcon(R.drawable.logo_drive_guard)
                        .setContentTitle("Trip Cancelled")
                        .setContentText("DriveGuard was closed and the current trip has been cancelled. Please safely pull over and start a new trip if you wish to continue tracking your driving")
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText("DriveGuard was closed and the current trip has been cancelled. Please safely pull over and start a new trip if you wish to continue tracking your driving."))
                        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                        // Set the intent that fires when the user taps the notification.
                        .setContentIntent(pendingIntent)
                        .setAutoCancel(true);

                NotificationManagerCompat manager = NotificationManagerCompat.from(this);
                if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    return;
                }
                manager.notify(1, builder.build());
            }
        networkManager = new NetworkManager(getApplicationContext());
        Response response = networkManager.endTrip(dataCollector.getStartingLocation());
        if (response.isSuccessful()) {
            Toast.makeText(this, "Leaving Activity, Trip Ended", Toast.LENGTH_SHORT).show();
        }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu,menu);
        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item){
        int id = item.getItemId();
        if (id == R.id.settings){
            Intent intent = new Intent(this, SettingsScreen.class);
            startActivity(intent);
            finish();
        }
        else if (id == R.id.profile){
            Intent intent = new Intent(this, ProfileScreen.class);
            startActivity(intent);
            finish();
        }
        else if (id == R.id.notifications){
            Intent intent = new Intent(this, SuggestionScreen.class);
            startActivity(intent);
        }
        return true;
    }

    private void enterDisabledState() {
        Toast.makeText(this, "Unable to Access Network Connection or Location", Toast.LENGTH_SHORT).show();
        Toast.makeText(this, "Trip Functionality Disabled", Toast.LENGTH_SHORT).show();
        startButton.setEnabled(false);
    }

    // Method to check for driving events
    private void checkForEvents() {
        // Get current data from the DataCollector
        float speed = dataCollector.getSpeed();// Current speed
        float gForce = dataCollector.getAcceleration();      // Current g-force
        float turningRate = dataCollector.getTurningRate(); // Current turning rate
        float brakingRate = dataCollector.getDecelerationRate();
        android.location.Location location = dataCollector.getStartingLocation(); // Current location

        String timestamp = "";
        Instant currentTime = Instant.now();
        timestamp = DateTimeFormatter.ISO_INSTANT.format(currentTime);
        // String timestamp = String.valueOf(System.currentTimeMillis()); // Current timestamp

        String s = "Your Speed: " + String.valueOf(Math.round(speed));
        speedText.setText(s);

        String l = "Speed Limit: " + String.valueOf(getPostedSpeedLimit());
        limitText.setText(l);

        if (this.getTimeLastChecked15Sec().getTime() + EVENT_INTERVAL_SECONDS * MILLISECONDS_IN_A_MINUTE <= new Date().getTime()) {
            this.getEventHasBeenDetected().put(SPEEDING_EVENT, false);
            this.getEventHasBeenDetected().put(ACCELERATION_EVENT, false);
            this.getEventHasBeenDetected().put(BRAKE_EVENT, false);
            this.getEventHasBeenDetected().put(TURN_EVENT, false);
            this.setTimeLastChecked15Sec(new Date());
        }

        // Use NetworkManager for additional data retrieval
        // NetworkManager networkManager = new NetworkManager(getApplicationContext());
        if (this.getTimeLastChecked30Min().getTime() + WEATHER_CHECK_INTERVAL_MINUTES * SECONDS_IN_A_MINUTE * MILLISECONDS_IN_A_MINUTE <= new Date().getTime()) {
            RequestWeather(location);
            RequestRoad(location);
            this.setTimeLastChecked30Min(new Date());
        }

        dataClassifier = new DataClassifier(this.getPostedSpeedLimit());
        // Classify the data for events

        if (!this.getEventHasBeenDetected().containsKey(SPEEDING_EVENT))
        {
            this.getEventHasBeenDetected().put(SPEEDING_EVENT, false);
        }
        if (!this.getEventHasBeenDetected().containsKey(ACCELERATION_EVENT))
        {
            this.getEventHasBeenDetected().put(ACCELERATION_EVENT, false);
        }
        if (!this.getEventHasBeenDetected().containsKey(BRAKE_EVENT))
        {
            this.getEventHasBeenDetected().put(BRAKE_EVENT, false);
        }
        if (!this.getEventHasBeenDetected().containsKey(TURN_EVENT))
        {
            this.getEventHasBeenDetected().put(TURN_EVENT, false);
        }

        // Classify the data for events
        this.setEventHasBeenDetected(dataClassifier.classifyData(speed, gForce, turningRate, timestamp, networkManager, location, this.getCurrentWeather(), this.getPostedSpeedLimit(), this.getEventHasBeenDetected(), brakingRate));
    }
    public void RequestWeather(Location location){
    networkManager = new NetworkManager(getApplicationContext());
    try {
        // Get the weather data as a Response object
        Response weatherResponse = networkManager.getWeatherFromLocation(location);

        // Check if the response is successful
        if (!weatherResponse.isSuccessful()) {
            throw new IOException("Failed to fetch weather data. HTTP Code: " + weatherResponse.code());
        }

        // Deserialize the response body into a Weather object
        String weatherResponseBody = null;
        if (weatherResponse.body() != null) {
            weatherResponseBody = weatherResponse.body().string();
        }
        Gson gson = new Gson();
        this.setCurrentWeather(gson.fromJson(weatherResponseBody, Weather.class));

    } catch (IOException e) {
        System.err.println("Error retrieving or processing weather data: " + e.getMessage());
        throw new RuntimeException(e);
    }
}
    public void RequestRoad(Location location){
    try {
        // Make the API call to fetch road data
        Response response = networkManager.getRoadFromLocation(location);

        // Check if the response is successful
        if (response.isSuccessful() && response.body() != null) {
            // Parse the response body into a Road object
            String responseBody = response.body().string();
            Gson gson = new Gson();
            Road road = gson.fromJson(responseBody, Road.class);

            // Return the speed limit
            this.setPostedSpeedLimit(road.getSpeedLimit());
        } else {
            // Log error or handle unsuccessful response
            System.err.println("Failed to fetch road data. HTTP Code: " + response.code());
        }
    } catch (IOException e) {
        // Handle exceptions
        System.err.println("Error fetching road data: " + e.getMessage());
    }
    this.setTimeLastChecked30Min(new Date());
}

}

