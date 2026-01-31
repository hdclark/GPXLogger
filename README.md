# GPXLogger

A GPS logger app for Android that exports GPS traces in GPX format.

## Features

- **Background GPS Logging**: Records GPS position in the background using a foreground service
- **Configurable Logging Interval**: Default 10 seconds, user-configurable via settings
- **Automatic GPX Export**: GPS data is continuously written to GPX files
- **Battery Optimization**: Data is cached for up to 15 minutes before writing to reduce battery usage
- **Real-time Updates**: View current location and logging status in the app

## Requirements

- Android 8.0 (API 26) or higher
- Location permissions
- Notification permissions (Android 13+)

## Usage

1. **Start Logging**: Tap the "Start Logging" button to begin recording GPS positions
2. **Stop Logging**: Tap the "Stop Logging" button to stop recording
3. **Configure Interval**: Access Settings to change the logging frequency (in seconds)
4. **GPX Files**: Files are automatically saved to the app's external files directory

## GPX File Format

The app generates standard GPX 1.1 files with the following information:
- Latitude and longitude coordinates
- Elevation (altitude)
- Timestamp in UTC
- Track segments organized by logging session

## Building

The project uses Gradle for building:

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

## License

See LICENSE file for details.
