package com.iips.launcher.security

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.util.Log

object SecurityAlarmManager {
    private var mediaPlayer: MediaPlayer? = null
    private var originalVolume: Int = -1

    fun startAlarm(context: Context) {
        if (mediaPlayer?.isPlaying == true) return

        try {
            val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
            
            // Save original volume
            if (originalVolume == -1) {
                originalVolume = audioManager.getStreamVolume(AudioManager.STREAM_ALARM)
            }
            
            // Set volume to max
            val maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM)
            audioManager.setStreamVolume(AudioManager.STREAM_ALARM, maxVolume, 0)

            val alarmUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM) 
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE)
                ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

            if (alarmUri == null) {
                Log.e("SecurityAlarmManager", "No default alarm/ringtone found")
                return
            }

            mediaPlayer = MediaPlayer().apply {
                setDataSource(context, alarmUri)
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                isLooping = true
                prepare()
                start()
            }
            Log.i("SecurityAlarmManager", "Started security alarm")
        } catch (e: Exception) {
            Log.e("SecurityAlarmManager", "Failed to play alarm", e)
        }
    }

    fun stopAlarm(context: Context) {
        try {
            mediaPlayer?.let {
                if (it.isPlaying) {
                    it.stop()
                }
                it.release()
            }
            mediaPlayer = null

            // Restore original volume
            if (originalVolume != -1) {
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audioManager.setStreamVolume(AudioManager.STREAM_ALARM, originalVolume, 0)
                originalVolume = -1
            }
            Log.i("SecurityAlarmManager", "Stopped security alarm")
        } catch (e: Exception) {
            Log.e("SecurityAlarmManager", "Failed to stop alarm", e)
        }
    }
}
