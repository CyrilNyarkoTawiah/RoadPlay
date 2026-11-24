package com.roadplay.player.auto

import com.google.android.apps.auto.sdk.CarActivity
import com.google.android.apps.auto.sdk.CarActivityService

/**
 * Registered under Android Auto's projection category so that, while the car is
 * parked, AA instantiates [CarPlayerActivity] on the head unit — RoadPlay's own
 * browse-and-watch UI on the car screen. While driving, the AA host refuses to
 * show this activity; the regular audio-only media session keeps working.
 */
class CarPlayerService : CarActivityService() {
    override fun getCarActivity(): Class<out CarActivity> = CarPlayerActivity::class.java
}
