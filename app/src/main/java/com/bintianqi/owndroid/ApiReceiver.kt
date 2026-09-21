package com.bintianqi.owndroid

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import com.bintianqi.owndroid.utils.hash
import java.io.File

class ApiReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val requestKey = intent.getStringExtra("key")
        var log = "OwnDroid API request received. action: ${intent.action}"
        val myApp = context.applicationContext as MyApplication
        val key = myApp.container.settingsRepo.data.apiKeyHash
        if (key.isNotEmpty() && key == requestKey?.hash()) {
            val app = intent.getStringExtra("package")
            val permission = intent.getStringExtra("permission")
            val restriction = intent.getStringExtra("restriction")
            val activity = intent.getStringExtra("activity")
            if (!app.isNullOrEmpty()) log += "\npackage: $app"
            if (!permission.isNullOrEmpty()) log += "\npermission: $permission"
            if (!activity.isNullOrEmpty()) log += "\nactivity: $activity"
            try {
                myApp.container.privilegeHelper.safeDpmCall {
                    @SuppressWarnings("NewApi")
                    when (intent.action?.removePrefix("com.bintianqi.owndroid.action.")) {
                        "HIDE" -> dpm.setApplicationHidden(dar, app, true)
                        "UNHIDE" -> dpm.setApplicationHidden(dar, app, false)
                        "SUSPEND" -> dpm.setPackagesSuspended(dar, arrayOf(app), true)
                        "UNSUSPEND" -> dpm.setPackagesSuspended(dar, arrayOf(app), false)
                        "ADD_USER_RESTRICTION" -> {
                            dpm.addUserRestriction(dar, restriction)
                        }

                        "CLEAR_USER_RESTRICTION" -> {
                            dpm.clearUserRestriction(dar, restriction)
                        }

                        "SET_PERMISSION_DEFAULT" -> {
                            dpm.setPermissionGrantState(
                                dar, app!!, permission!!,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_DEFAULT
                            )
                        }

                        "SET_PERMISSION_GRANTED" -> {
                            dpm.setPermissionGrantState(
                                dar, app!!, permission!!,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
                            )
                        }

                        "SET_PERMISSION_DENIED" -> {
                            dpm.setPermissionGrantState(
                                dar, app!!, permission!!,
                                DevicePolicyManager.PERMISSION_GRANT_STATE_DENIED
                            )
                        }

                        "LOCK" -> {
                            dpm.lockNow()
                        }

                        "REBOOT" -> {
                            dpm.reboot(dar)
                        }

                        "SET_CAMERA_DISABLED" -> {
                            dpm.setCameraDisabled(dar, true)
                        }

                        "SET_CAMERA_ENABLED" -> {
                            dpm.setCameraDisabled(dar, false)
                        }

                        "SET_USB_DISABLED" -> {
                            dpm.isUsbDataSignalingEnabled = false
                        }

                        "SET_USB_ENABLED" -> {
                            dpm.isUsbDataSignalingEnabled = true
                        }

                        "SET_SCREEN_CAPTURE_DISABLED" -> {
                            dpm.setScreenCaptureDisabled(dar, true)
                        }

                        "SET_SCREEN_CAPTURE_ENABLED" -> {
                            dpm.setScreenCaptureDisabled(dar, false)
                        }

                        "INSTALL" -> {
                            val apkPath = intent.getStringExtra("apk_path")
                            if (apkPath.isNullOrEmpty()) {
                                log += "\nMissing apk_path extra"
                                return@safeDpmCall
                            }
                            val file = File(apkPath)
                            if (!file.exists()) {
                                log += "\nFile not found: $apkPath"
                                return@safeDpmCall
                            }
                            val packageInstaller = context.packageManager.packageInstaller
                            val params = PackageInstaller.SessionParams(
                                PackageInstaller.SessionParams.MODE_FULL_INSTALL
                            )
                            val sessionId = packageInstaller.createSession(params)
                            val session = packageInstaller.openSession(sessionId)
                            try {
                                file.inputStream().use { input ->
                                    session.openWrite("package", 0, file.length()).use { output ->
                                        input.copyTo(output)
                                        session.fsync(output)
                                    }
                                }
                                val pi = PendingIntent.getBroadcast(
                                    context, sessionId,
                                    Intent("com.bintianqi.owndroid.INSTALL_STATUS"),
                                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                                )
                                session.commit(pi.intentSender)
                                session.close()
                                log += "\nInstall session committed: $apkPath"
                            } catch (e: Exception) {
                                session.abandon()
                                throw e
                            }
                        }

                        "UNINSTALL" -> {
                            if (app.isNullOrEmpty()) {
                                log += "\nMissing package extra"
                                return@safeDpmCall
                            }
                            val packageInstaller = context.packageManager.packageInstaller
                            val pi = PendingIntent.getBroadcast(
                                context, app.hashCode(),
                                Intent("com.bintianqi.owndroid.UNINSTALL_STATUS").apply {
                                    putExtra("package", app)
                                },
                                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                            )
                            packageInstaller.uninstall(app, pi.intentSender)
                            log += "\nUninstall requested: $app"
                        }

                        "SET_PREFERRED_ACTIVITY" -> {
                            val activityClass = intent.getStringExtra("activity")
                            val mimeType = intent.getStringExtra("mime_type")
                            if (app.isNullOrEmpty() || activityClass.isNullOrEmpty() || mimeType.isNullOrEmpty()) {
                                log += "\nMissing package, activity or mime_type extra"
                                return@safeDpmCall
                            }
                            val filter = android.content.IntentFilter(Intent.ACTION_VIEW).apply {
                                addCategory(Intent.CATEGORY_DEFAULT)
                                addDataType(mimeType)
                            }
                            val target = android.content.ComponentName(app, activityClass)
                            dpm.addPersistentPreferredActivity(dar, filter, target)
                            log += "\nPreferred activity set: $app/$activityClass for $mimeType"
                        }

                        "CLEAR_PREFERRED_ACTIVITY" -> {
                            if (app.isNullOrEmpty()) {
                                log += "\nMissing package extra"
                                return@safeDpmCall
                            }
                            dpm.clearPackagePersistentPreferredActivities(dar, app)
                            log += "\nPreferred activities cleared for: $app"
                        }

                        else -> {
                            log += "\nInvalid action"
                        }
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
                val message = (e::class.qualifiedName ?: "Exception") + ": " + (e.message ?: "")
                log += "\n$message"
            }
        } else {
            log += "\nUnauthorized"
        }
        Log.d(TAG, log)
    }

    companion object {
        private const val TAG = "API"
    }
}

