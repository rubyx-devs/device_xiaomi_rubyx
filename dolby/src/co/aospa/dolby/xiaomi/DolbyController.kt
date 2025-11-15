/*
 * Copyright (C) 2023-24 Paranoid Android
 *
 * SPDX-License-Identifier: Apache-2.0
 */

package co.aospa.dolby.xiaomi

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.AudioManager.AudioPlaybackCallback
import android.media.AudioPlaybackConfiguration
import android.os.Handler
import android.util.Log
import androidx.preference.PreferenceManager
import co.aospa.dolby.xiaomi.DolbyConstants.Companion.dlog
import co.aospa.dolby.xiaomi.DolbyConstants.DsParam
import co.aospa.dolby.xiaomi.R
import kotlin.math.abs

internal class DolbyController private constructor(
    private val context: Context
) {
    private var dolbyEffect = DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(context.mainLooper)
    private val stereoWideningSupported = context.getResources().getBoolean(R.bool.dolby_stereo_widening_supported)
    private val volumeLevelerMin = context.resources.getInteger(R.integer.volume_leveler_min)
    private val volumeLevelerMax = context.resources.getInteger(R.integer.volume_leveler_max)
        .coerceAtLeast(volumeLevelerMin)
    private val stereoWideningSteps = context.resources.getIntArray(R.array.dolby_stereo_steps)
    private val dialogueAmountSteps = context.resources.getIntArray(R.array.dolby_dialogue_steps)
    private val dialogueActiveDefault = dialogueAmountSteps.firstOrNull { it > 0 } ?: 0
    private val volumeLevelerDefault = context.resources.getInteger(R.integer.volume_leveler_default)
    private val paramSupportCache = mutableMapOf<DsParam, Boolean>()

    // Restore current profile on every media session
    private val playbackCallback = object : AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: List<AudioPlaybackConfiguration>) {
            val isPlaying = configs.any {
                it.playerState == AudioPlaybackConfiguration.PLAYER_STATE_STARTED
            }
            dlog(TAG, "onPlaybackConfigChanged: isPlaying=$isPlaying")
            if (isPlaying)
                setCurrentProfile()
        }
    }

    // Restore current profile on audio device change
    private val audioDeviceCallback = object : AudioDeviceCallback() {
        override fun onAudioDevicesAdded(addedDevices: Array<AudioDeviceInfo>) {
            dlog(TAG, "onAudioDevicesAdded")
            setCurrentProfile()
        }

        override fun onAudioDevicesRemoved(removedDevices: Array<AudioDeviceInfo>) {
            dlog(TAG, "onAudioDevicesRemoved")
            setCurrentProfile()
        }
    }

    private var registerCallbacks = false
        set(value) {
            if (field == value) return
            field = value
            dlog(TAG, "setRegisterCallbacks($value)")
            if (value) {
                audioManager!!.registerAudioPlaybackCallback(playbackCallback, handler)
                audioManager.registerAudioDeviceCallback(audioDeviceCallback, handler)
            } else {
                audioManager!!.unregisterAudioPlaybackCallback(playbackCallback)
                audioManager.unregisterAudioDeviceCallback(audioDeviceCallback)
            }
        }

    var dsOn: Boolean
        get() =
            dolbyEffect.dsOn.also {
                dlog(TAG, "getDsOn: $it")
            }
        set(value) {
            dlog(TAG, "setDsOn: $value")
            checkEffect()
            dolbyEffect.dsOn = value
            registerCallbacks = value
            if (value)
                setCurrentProfile()
        }

    var profile: Int
        get() =
            dolbyEffect.profile.also {
                dlog(TAG, "getProfile: $it")
            }
        set(value) {
            dlog(TAG, "setProfile: $value")
            checkEffect()
            dolbyEffect.profile = value
        }

    init {
        dlog(TAG, "initialized")
    }

    fun onBootCompleted() {
        dlog(TAG, "onBootCompleted")

        // Restore our main settings
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val shouldEnable = prefs.getBoolean(DolbyConstants.PREF_ENABLE, true)
        
        dlog(TAG, "Dolby should be enabled: $shouldEnable")

        // First, ensure the effect is enabled if needed
        if (shouldEnable) {
            dolbyEffect.enabled = true
            dlog(TAG, "AudioEffect enabled")
        }

        context.resources.getStringArray(R.array.dolby_profile_values)
            .map { it.toInt() }
            .forEach { profile ->
                // Reset dolby first to prevent it from loading bad settings
                dolbyEffect.resetProfileSpecificSettings(profile)
                // Now restore our profile-specific settings
                restoreSettings(profile)
            }

        // Finally restore the current profile and enable state
        setCurrentProfile()
        dsOn = shouldEnable
        
        dlog(TAG, "Boot completed initialization finished. dsOn=$dsOn, profile=$profile")
    }

    private fun restoreSettings(profile: Int) {
        dlog(TAG, "restoreSettings(profile=$profile)")
        val prefs = profilePrefs(profile)
        setPreset(
            prefs.getString(DolbyConstants.PREF_PRESET, getPreset(profile))!!,
            profile
        )
        setIeqPreset(
            prefs.getString(
                DolbyConstants.PREF_IEQ,
                getIeqPreset(profile).toString()
            )!!.toInt(),
            profile
        )
        setHeadphoneVirtEnabled(
            prefs.getBoolean(DolbyConstants.PREF_HP_VIRTUALIZER, getHeadphoneVirtEnabled(profile)),
            profile
        )
        setSpeakerVirtEnabled(
            prefs.getBoolean(DolbyConstants.PREF_SPK_VIRTUALIZER, getSpeakerVirtEnabled(profile)),
            profile
        )
        val storedStereo = prefs.getInt(
            DolbyConstants.PREF_STEREO_WIDENING,
            getStereoWideningAmount(profile)
        )
        setStereoWideningAmount(storedStereo, profile)
        val dialogueEnabled = prefs.getBoolean(
            DolbyConstants.PREF_DIALOGUE,
            getDialogueEnhancerEnabled(profile)
        )
        setDialogueEnhancerEnabled(dialogueEnabled, profile)
        val storedDialogueAmount = prefs.getInt(
            DolbyConstants.PREF_DIALOGUE_AMOUNT,
            defaultDialogueAmount(profile)
        )
        if (dialogueEnabled) {
            setDialogueEnhancerAmount(storedDialogueAmount, profile)
        } else {
            dlog(TAG, "Dialogue enhancer disabled for profile=$profile, keeping stored amount=${clampDialogueAmount(storedDialogueAmount)}")
        }
        setBassEnhancerEnabled(
            prefs.getBoolean(DolbyConstants.PREF_BASS, getBassEnhancerEnabled(profile)),
            profile
        )
        val volumeEnabled = prefs.getBoolean(
            DolbyConstants.PREF_VOLUME,
            getVolumeLevelerEnabled(profile)
        )
        setVolumeLevelerEnabled(volumeEnabled, profile)
        val storedVolumeAmount = prefs.getInt(
            DolbyConstants.PREF_VOLUME_AMOUNT,
            volumeLevelerDefault
        )
        if (volumeEnabled) {
            setVolumeLevelerAmount(storedVolumeAmount, profile)
        } else {
            dlog(TAG, "Volume leveler disabled for profile=$profile, keeping stored amount=${clampVolumeLevelerAmount(storedVolumeAmount)}")
        }
        if (isVolumeModelerSupported()) {
            setVolumeModelerEnabled(
                prefs.getBoolean(
                    DolbyConstants.PREF_VOLUME_MODELER,
                    getVolumeModelerEnabled(profile)
                ),
                profile
            )
        } else {
            dlog(TAG, "Volume modeler unsupported, skipping restore")
        }
        if (isAudioOptimizerSupported()) {
            setAudioOptimizerEnabled(
                prefs.getBoolean(
                    DolbyConstants.PREF_AUDIO_OPTIMIZER,
                    getAudioOptimizerEnabled(profile)
                ),
                profile
            )
        } else {
            dlog(TAG, "Audio optimizer unsupported, skipping restore")
        }
    }

    private fun checkEffect() {
        if (!dolbyEffect.hasControl()) {
            Log.w(TAG, "lost control, recreating effect")
            val wasEnabled = dolbyEffect.enabled
            val currentDsOn = try { dolbyEffect.dsOn } catch (e: Exception) { false }
            val currentProfile = try { dolbyEffect.profile } catch (e: Exception) { 0 }
            
            dolbyEffect.release()
            dolbyEffect = DolbyAudioEffect(EFFECT_PRIORITY, audioSession = 0)
            paramSupportCache.clear()
            
            // Restore the state
            if (wasEnabled || currentDsOn) {
                dolbyEffect.enabled = true
                dolbyEffect.dsOn = true
                dolbyEffect.profile = currentProfile
                Log.i(TAG, "Effect recreated and re-enabled with profile=$currentProfile")
                if (currentDsOn) {
                    dlog(TAG, "Reapplying profile settings after effect recreation")
                    restoreSettings(currentProfile)
                }
            }
        }
    }

    private fun setCurrentProfile() {
        dlog(TAG, "setCurrentProfile")
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        val newProfile = prefs.getString(DolbyConstants.PREF_PROFILE, "0" /*dynamic*/)!!.toInt()
        profile = newProfile
        if (dsOn) {
            dlog(TAG, "Reapplying stored settings for profile=$newProfile")
            restoreSettings(newProfile)
        }
    }

    fun getProfileName(): String? {
        val profile = dolbyEffect.profile.toString()
        val profiles = context.resources.getStringArray(R.array.dolby_profile_values)
        val profileIndex = profiles.indexOf(profile)
        dlog(TAG, "getProfileName: profile=$profile index=$profileIndex")
        return if (profileIndex == -1) null else context.resources.getStringArray(
            R.array.dolby_profile_entries
        )[profileIndex]
    }

    fun resetProfileSpecificSettings() {
        dlog(TAG, "resetProfileSpecificSettings")
        checkEffect()
        dolbyEffect.resetProfileSpecificSettings()
        context.deleteSharedPreferences("profile_$profile")
    }

    fun getPreset(profile: Int = this.profile): String {
        val gains = dolbyEffect.getDapParameter(DsParam.GEQ_BAND_GAINS, profile)
        return gains.joinToString(separator = ",").also {
            dlog(TAG, "getPreset: $it")
        }
    }

    fun setPreset(value: String, profile: Int = this.profile) {
        dlog(TAG, "setPreset: $value")
        checkEffect()
        val gains = value.split(",")
            .map { it.toInt() }
            .toIntArray()
        dolbyEffect.setDapParameter(DsParam.GEQ_BAND_GAINS, gains, profile)
    }

    fun getPresetName(): String {
        val presets = context.resources.getStringArray(R.array.dolby_preset_values)
        val presetIndex = presets.indexOf(getPreset())
        return if (presetIndex == -1) {
            "Custom"
        } else {
            context.resources.getStringArray(
                R.array.dolby_preset_entries
            )[presetIndex]
        }
    }

    fun getHeadphoneVirtEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.HEADPHONE_VIRTUALIZER, profile).also {
            dlog(TAG, "getHeadphoneVirtEnabled: $it")
        }

    fun setHeadphoneVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setHeadphoneVirtEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.HEADPHONE_VIRTUALIZER, value, profile)
    }

    fun getSpeakerVirtEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.SPEAKER_VIRTUALIZER, profile).also {
            dlog(TAG, "getSpeakerVirtEnabled: $it")
        }

    fun setSpeakerVirtEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setSpeakerVirtEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.SPEAKER_VIRTUALIZER, value, profile)
    }

    fun getBassEnhancerEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.BASS_ENHANCER_ENABLE, profile).also {
            dlog(TAG, "getBassEnhancerEnabled: $it")
        }

    fun setBassEnhancerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setBassEnhancerEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.BASS_ENHANCER_ENABLE, value, profile)
    }

    fun getVolumeLevelerEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.VOLUME_LEVELER_ENABLE, profile).also {
            dlog(TAG, "getVolumeLevelerEnabled: $it")
        }

    fun setVolumeLevelerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setVolumeLevelerEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.VOLUME_LEVELER_ENABLE, value, profile)
    }

    fun getVolumeLevelerAmount(profile: Int = this.profile): Int {
        val prefs = profilePrefs(profile)
        val storedValue = clampVolumeLevelerAmount(
            prefs.getInt(DolbyConstants.PREF_VOLUME_AMOUNT, volumeLevelerDefault)
        )
        if (!getVolumeLevelerEnabled(profile)) {
            dlog(TAG, "getVolumeLevelerAmount: leveler disabled, returning stored=$storedValue")
            return storedValue
        }

        val effectValue = clampVolumeLevelerAmount(
            dolbyEffect.getDapParameterInt(DsParam.VOLUME_LEVELER_AMOUNT, profile)
        )

        if (effectValue != storedValue) {
            dlog(
                TAG,
                "getVolumeLevelerAmount: effect=$effectValue stored=$storedValue, reapplying stored value"
            )
            setVolumeLevelerAmount(storedValue, profile, force = true)
            return storedValue
        }

        dlog(TAG, "getVolumeLevelerAmount (clamped): $effectValue")
        return effectValue
    }

    fun setVolumeLevelerAmount(value: Int, profile: Int = this.profile, force: Boolean = false) {
        val clampedValue = clampVolumeLevelerAmount(value)
        if (clampedValue != value) {
            dlog(TAG, "setVolumeLevelerAmount: requested=$value clamped=$clampedValue")
        } else {
            dlog(TAG, "setVolumeLevelerAmount: $value")
        }
        if (!force && !getVolumeLevelerEnabled(profile)) {
            dlog(TAG, "setVolumeLevelerAmount: skipping write, leveler disabled for profile=$profile")
            return
        }
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.VOLUME_LEVELER_AMOUNT, clampedValue, profile)
    }

    fun getVolumeModelerEnabled(profile: Int = this.profile): Boolean {
        if (!isVolumeModelerSupported()) {
            dlog(TAG, "getVolumeModelerEnabled: unsupported")
            return false
        }
        return dolbyEffect.getDapParameterBool(DsParam.VOLUME_MODELER_ENABLE, profile).also {
            dlog(TAG, "getVolumeModelerEnabled: $it")
        }
    }

    fun setVolumeModelerEnabled(value: Boolean, profile: Int = this.profile) {
        if (!isVolumeModelerSupported()) {
            dlog(TAG, "setVolumeModelerEnabled: skipping, unsupported")
            return
        }
        dlog(TAG, "setVolumeModelerEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.VOLUME_MODELER_ENABLE, value, profile)
    }

    fun getAudioOptimizerEnabled(profile: Int = this.profile): Boolean {
        if (!isAudioOptimizerSupported()) {
            dlog(TAG, "getAudioOptimizerEnabled: unsupported")
            return false
        }
        return dolbyEffect.getDapParameterBool(DsParam.AUDIO_OPTIMIZER_ENABLE, profile).also {
            dlog(TAG, "getAudioOptimizerEnabled: $it")
        }
    }

    fun setAudioOptimizerEnabled(value: Boolean, profile: Int = this.profile) {
        if (!isAudioOptimizerSupported()) {
            dlog(TAG, "setAudioOptimizerEnabled: skipping, unsupported")
            return
        }
        dlog(TAG, "setAudioOptimizerEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.AUDIO_OPTIMIZER_ENABLE, value, profile)
    }

    fun getStereoWideningAmount(profile: Int = this.profile): Int {
        if (!stereoWideningSupported) return 0
        val value = clampStereoWideningAmount(
            dolbyEffect.getDapParameterInt(DsParam.STEREO_WIDENING_AMOUNT, profile)
        )
        dlog(TAG, "getStereoWideningAmount: $value")
        return value
    }

    fun setStereoWideningAmount(value: Int, profile: Int = this.profile) {
        if (!stereoWideningSupported) return
        val clampedValue = clampStereoWideningAmount(value)
        if (clampedValue != value) {
            dlog(TAG, "setStereoWideningAmount: requested=$value clamped=$clampedValue")
        } else {
            dlog(TAG, "setStereoWideningAmount: $value")
        }
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.STEREO_WIDENING_AMOUNT, clampedValue, profile)
    }

    fun getDialogueEnhancerEnabled(profile: Int = this.profile) =
        dolbyEffect.getDapParameterBool(DsParam.DIALOGUE_ENHANCER_ENABLE, profile).also {
            dlog(TAG, "getDialogueEnhancerEnabled: $it")
        }

    fun setDialogueEnhancerEnabled(value: Boolean, profile: Int = this.profile) {
        dlog(TAG, "setDialogueEnhancerEnabled: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_ENABLE, value, profile)
    }

    fun getDialogueEnhancerAmount(profile: Int = this.profile): Int {
        val storedValue = clampDialogueAmount(
            profilePrefs(profile).getInt(
                DolbyConstants.PREF_DIALOGUE_AMOUNT,
                defaultDialogueAmount(profile)
            )
        )
        if (!getDialogueEnhancerEnabled(profile)) {
            dlog(TAG, "getDialogueEnhancerAmount: enhancer disabled, returning stored=$storedValue")
            return storedValue
        }
        val effectValue = clampDialogueAmount(
            dolbyEffect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)
        )
        if (effectValue != storedValue) {
            dlog(
                TAG,
                "getDialogueEnhancerAmount: effect=$effectValue stored=$storedValue, syncing"
            )
            checkEffect()
            applyDialogueEnhancerAmount(storedValue, profile)
            return storedValue
        }
        dlog(TAG, "getDialogueEnhancerAmount: $effectValue")
        return effectValue
    }

    fun setDialogueEnhancerAmount(value: Int, profile: Int = this.profile) {
        val clampedValue = clampDialogueAmount(value)
        if (!getDialogueEnhancerEnabled(profile)) {
            dlog(TAG, "setDialogueEnhancerAmount: enhancer disabled, skipping write for profile=$profile")
            return
        }
        if (clampedValue != value) {
            dlog(TAG, "setDialogueEnhancerAmount: requested=$value clamped=$clampedValue")
        } else {
            dlog(TAG, "setDialogueEnhancerAmount: $value")
        }
        checkEffect()
        applyDialogueEnhancerAmount(clampedValue, profile)
    }

    fun getIeqPreset(profile: Int = this.profile) =
        dolbyEffect.getDapParameterInt(DsParam.IEQ_PRESET, profile).also {
            dlog(TAG, "getIeqPreset: $it")
        }

    fun setIeqPreset(value: Int, profile: Int = this.profile) {
        dlog(TAG, "setIeqPreset: $value")
        checkEffect()
        dolbyEffect.setDapParameter(DsParam.IEQ_PRESET, value, profile)
    }

    companion object {
        private const val TAG = "DolbyController"
        private const val EFFECT_PRIORITY = 100
        private const val SUPPORT_PROBE_PROFILE = 0

        @Volatile
        private var instance: DolbyController? = null

        fun getInstance(context: Context) =
            instance ?: synchronized(this) {
                instance ?: DolbyController(context).also { instance = it }
            }
    }

    private fun clampVolumeLevelerAmount(value: Int) =
        value.coerceIn(volumeLevelerMin, volumeLevelerMax)

    private fun clampDialogueAmount(value: Int) =
        dialogueAmountSteps.closest(value)

    private fun clampStereoWideningAmount(value: Int) =
        stereoWideningSteps.closest(value)

    private fun profilePrefs(profile: Int) =
        context.getSharedPreferences("profile_$profile", Context.MODE_PRIVATE)

    private fun defaultDialogueAmount(profile: Int): Int {
        val effectValue = runCatching {
            clampDialogueAmount(
                dolbyEffect.getDapParameterInt(DsParam.DIALOGUE_ENHANCER_AMOUNT, profile)
            )
        }.getOrNull()
        return effectValue?.takeIf { it != 0 } ?: dialogueActiveDefault
    }

    private fun applyDialogueEnhancerAmount(value: Int, profile: Int) {
        dolbyEffect.setDapParameter(DsParam.DIALOGUE_ENHANCER_AMOUNT, value, profile)
    }

    private fun IntArray.closest(value: Int): Int {
        if (isEmpty()) return value
        return minByOrNull { abs(it - value) } ?: value
    }

    private fun isParamSupported(param: DsParam): Boolean {
        return paramSupportCache.getOrPut(param) {
            try {
                checkEffect()
                dolbyEffect.getDapParameter(param, SUPPORT_PROBE_PROFILE)
                true
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Parameter $param is not supported: ${e.message}")
                false
            } catch (e: IllegalStateException) {
                Log.w(TAG, "Parameter $param unavailable in current effect state: ${e.message}")
                false
            } catch (e: UnsupportedOperationException) {
                Log.w(TAG, "Parameter $param not implemented by effect: ${e.message}")
                false
            } catch (e: RuntimeException) {
                Log.w(TAG, "Parameter $param probe failed: ${e.message}")
                false
            }
        }
    }

    fun isVolumeModelerSupported() = isParamSupported(DsParam.VOLUME_MODELER_ENABLE)

    fun isAudioOptimizerSupported() = isParamSupported(DsParam.AUDIO_OPTIMIZER_ENABLE)
}
