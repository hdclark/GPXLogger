# GPXLogger

A GPS logger app for Android that exports GPS traces in GPX format.

## Features

- **Background GPS Logging**: Records GPS position in the background using a foreground service
- **Configurable Logging Interval**: Default 10 seconds, user-configurable via settings
- **Automatic GPX Export**: GPS data is continuously written to GPX files
- **Battery Optimization**: Data is cached for up to 15 minutes before writing to reduce battery usage
- **Battery Optimization Workarounds**: Uses WakeLock and prompts users to disable battery optimization for reliable background logging
- **Real-time Updates**: View current location and logging status in the app

## Requirements

- Android 8.0 (API 26) or higher
- Location permissions
- Notification permissions (Android 13+)
- Battery optimization exemption (recommended for reliable background logging)

## Usage

1. **Start Logging**: Tap the "Start Logging" button to begin recording GPS positions
2. **Stop Logging**: Tap the "Stop Logging" button to stop recording
3. **Configure Interval**: Access Settings to change the logging frequency (in seconds)
4. **Configure Storage Location**: Access Settings to change the folder name for GPX files (default: `GPXLogger`)
5. **GPX Files**: Files are automatically saved to the app's external files directory (`Android/data/com.hdclark.gpxlogger/files/Downloads/<folder>/`) with timestamp-based filenames (e.g., `20260131-123407.gpx`)
6. **Battery Optimization**: When prompted, disable battery optimization to ensure the service runs reliably in the background
7. **View Statistics**: While logging, the app displays duration, distance, location count, and time since the last location update

## GPX File Format

The app generates standard GPX 1.1 files with the following information:
- Latitude and longitude coordinates
- Elevation (altitude)
- Timestamp in UTC
- Track segments organized by logging session

## Building

### Local Build with Docker

For a reproducible build environment that matches CI, use the included build script:

```bash
./build.sh              # Build debug APK (default)
./build.sh assembleRelease  # Build release APK
./build.sh clean        # Clean build artifacts
```

**Requirements:**
- Docker installed and running
- Up-to-date Linux distribution

The build script:
- Checks for Docker availability
- Builds the Docker image from the included `Dockerfile`
- Runs the Gradle build inside the container
- Reports the APK output location

### Direct Gradle Build

If you have the Android SDK installed locally:

```bash
./gradlew assembleDebug
```

## Continuous Integration

The project includes a GitHub Actions workflow that builds the app using Docker:

- Builds are triggered on pushes and pull requests to main/master
- Uses a Docker container with Android SDK
- APK artifacts are available for download after successful builds

## Architecture

- **MainActivity**: Main UI with controls and status display
- **LocationService**: Foreground service that handles GPS tracking
- **GpxManager**: Manages GPX file creation and writing with caching
- **SettingsActivity**: User preferences for logging interval

## Permissions

The app requires the following permissions:
- `ACCESS_FINE_LOCATION`: For precise GPS coordinates
- `ACCESS_COARSE_LOCATION`: For approximate location
- `FOREGROUND_SERVICE`: To run background service
- `FOREGROUND_SERVICE_LOCATION`: For location-based foreground service
- `POST_NOTIFICATIONS`: To display service notification (Android 13+)
- `WRITE_EXTERNAL_STORAGE`: For writing GPX files (Android 8.0-9.0 (API 26-28) only)
- `WAKE_LOCK`: To keep CPU active during background logging
- `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`: To request battery optimization exemption

## License

See LICENSE file for details.
