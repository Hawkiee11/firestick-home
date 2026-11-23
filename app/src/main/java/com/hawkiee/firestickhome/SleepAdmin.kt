package com.hawkiee.firestickhome

import android.app.admin.DeviceAdminReceiver

/** Lets the sleep timer switch the screen off. Its only policy is force-lock. */
class SleepAdmin : DeviceAdminReceiver()
