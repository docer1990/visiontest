package com.example.visiontest

/**
 * Exception classes for the Android test automation framework.
 *
 * These classes represent various error conditions that can occur
 * during interaction with Android devices and applications.
 */

/**
 * Thrown when no Android device is available for testing.
 */
class NoDeviceAvailableException(message: String) : Exception(message)

/**
 * Thrown when a shell command execution fails.
 *
 * @property exitCode The exit code of the failed command
 */
class CommandExecutionException(message: String, val exitCode: Int = -1) : Exception(message)

/**
 * Thrown when ADB initialization fails.
 */
class AdbInitializationException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when there is an error listing applications on a device.
 */
class AppListException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when there is an error retrieving application information.
 */
class AppInfoException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when a specified package cannot be found on the device.
 */
class PackageNotFoundException(message: String) : Exception(message)

/**
 * Thrown when an iOS simulator operation fails.
 */
class IOSSimulatorException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Thrown when no iOS simulator is available for testing.
 */
class NoSimulatorAvailableException(message: String) : Exception(message)

/**
 * Thrown when a specified app cannot be found on the iOS device/simulator.
 */
class AppNotFoundException(message: String) : Exception(message)