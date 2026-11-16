package com.msp1974.vacompanion.odysseus.arbitration

/**
 * Odysseus fork: what a device reports of how loud it heard the wake word: the wake word's level and
 * the noise floor around it ([WakeLevelMeter]), both with this device's calibration offset added so
 * they compare across devices. Either is null while the microphone has given nothing yet.
 */
class WakeLevelReading(val levelDb: Float?, val floorDb: Float?) {
    companion object {
        fun of(meter: WakeLevelMeter, offsetDb: Float) = WakeLevelReading(
            meter.wakeWordDbfs()?.plus(offsetDb),
            meter.noiseFloorDbfs()?.plus(offsetDb),
        )
    }
}
