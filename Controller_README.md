# Continuous Authentication – Controller Application

The **Controller Application** is the client-side component in the Continuous Authentication project. It acts as a simple UI for communicating with the **Service Application**, which runs the continuous authentication processes.

This app demonstrates **Inter-Process Communication (IPC)** using **Android Messenger** to:
- Trigger the **Service Application** to start real-time recording and verification.
- Request the latest multimodal confidence scores (face, audio, gait, combined).
- Display these scores to the user.

---

## Features

- **Start Real-Time Recording:**  
  Sends a command to the Service Application to activate its sensors and begin recording.

- **Retrieve Verification Scores:**  
  Requests the latest computed **multimodal confidence score** (face, audio, gait, combined).

- **Dynamic UI Feedback:**
    - Displays **loading states** while waiting for the Service Application to respond.
    - Shows the latest confidence score in percentage form (e.g., `80/100`).
    - Changes button color dynamically based on confidence score thresholds.

- **Resilient Error Handling:**  
  Handles cases where the Service Application is unavailable or disconnected gracefully.

---

## Usage Guide

### **1. Prerequisites**
- Install **both the Service Application and the Controller Application** on the same Android device.
- Ensure the **Service Application** is running or bound in the background.

### **2. Steps**
1. Launch the **Controller Application**.
2. Tap the **Button** (floating action button).  
   This will:
    - Trigger the Service Application to start recording (`MSG_START_RECORDING`).
    - Request the status of sensors (`MSG_GET_SENSOR`).
    - Request the latest confidence scores (`MSG_GET_VERIFICATION_SCORES`).
3. Wait for the **loading spinner** to disappear.
4. The **confidence score** will be displayed as a percentage.

---

## Technical Implementation

The **Controller Application** relies on a single activity only: `ControllerActivity`. Below is a detailed explanation of its structure.

---

### **1. IPC Communication with Service**

The app communicates with the Service Application using **Messenger IPC**.  
Key message codes are defined in the `companion object`:

```kotlin
companion object {
    val MSG_GET_SENSOR = 1
    val MSG_SENSOR_RESPONSE = 2
    val MSG_START_RECORDING = 3
    val MSG_GET_VERIFICATION_SCORES = 4
    val MSG_VERIFICATION_SCORES_RESPONSE = 5
}
```

1. Request Messages
- MSG_START_RECORDING: Instructs the service to start recording.
- MSG_GET_SENSOR: Requests sensor activation status.
- MSG_GET_VERIFICATION_SCORES: Requests the latest confidence scores.

2. Response Messages
- MSG_SENSOR_RESPONSE: Indicates sensor readiness.
- MSG_VERIFICATION_SCORES_RESPONSE: Contains confidence score data.

### **2. Binding to the Service**

The **ControllerActivity** binds to the SmartService of the Service Application using:

```kotlin
private fun bindToService() {
    val intent = Intent().apply {
        setClassName(
            "com.example.serviceapp",
            "com.example.serviceapp.service.SmartService"
        )
    }
    val bound = bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    if (bound) Log.d("ControllerActivity", "Service binding initiated")
}
```
The ServiceConnection maintains the Messenger reference:
```kotlin
private val serviceConnection = object : ServiceConnection {
    override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
        serviceMessenger = Messenger(binder)
        Log.d("ControllerActivity", "Service connected")
    }

    override fun onServiceDisconnected(name: ComponentName?) {
        serviceMessenger = null
        Log.d("ControllerActivity", "Service disconnected")
        if (isLoading) hideLoadingState()
    }
}
```

### **3. Sending Commands**

Start Recording

```kotlin
private fun sendStartRecordingCommand() {
    serviceMessenger?.let { messenger : Messenger ->
        val message = Message.obtain(null, MSG_START_RECORDING).apply {
            replyTo = clientMessenger
        }
        messenger.send(message)
        Log.d("ControllerActivity", "Start recording command sent")
    }
}
```

Request Verification Scores

```kotlin
private fun requestVerificationScores() {
    serviceMessenger?.let { messenger : Messenger ->
        val message = Message.obtain(null, MSG_GET_VERIFICATION_SCORES).apply {
            replyTo = clientMessenger
        }
        messenger.send(message)
        Log.d("ControllerActivity", "Verification scores request sent")
    }
}
```

### **4. Handling Responses**
The **clientMessenger** is responsible for receiving responses from the Service Application:
```kotlin
private val clientMessenger = Messenger(Handler(Looper.getMainLooper()) { message : Messenger ->
    handleIncomingMessage(message)
})
```

**Sensor Response Handling**
```kotlin
private fun handleSensorResponse() {
    showLoadingState()
    Handler(Looper.getMainLooper()).postDelayed({
        @Suppress("VARIABLE_EXPECTED")
        binding.tvCheckInstruction.text = "Sensors are turned on"
        hideLoadingState()
    }, LOADING_DELAY_MS)
}
```

**Score Response Handling**
```kotlin
private fun handleVerificationScoresResponse(message: Message) {
    val bundle = message.data
    val faceScore = bundle.getFloat("face_score", DEFAULT_SCORE_VALUE)
    val audioScore = bundle.getFloat("audio_score", DEFAULT_SCORE_VALUE)
    val gaitScore = bundle.getFloat("gait_score", DEFAULT_SCORE_VALUE)
    val combinedScore = bundle.getFloat("combined_score", DEFAULT_SCORE_VALUE)

    updateUIWithScores(faceScore, audioScore, gaitScore, combinedScore)
    hideLoadingState()
}
```