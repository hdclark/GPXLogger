package com.hdclark.gpxlogger

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.EditTextPreference
import androidx.preference.PreferenceFragmentCompat

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)
        
        // Only add fragment if not already present (e.g., after configuration change)
        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.settings_container, SettingsFragment())
                .commit()
        }
    }

    class SettingsFragment : PreferenceFragmentCompat() {
        private lateinit var gpxManager: GpxManager
        
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.preferences, rootKey)
            
            gpxManager = GpxManager(requireContext())
            
            // Set up storage path preference to show full path
            val storagePathPref = findPreference<EditTextPreference>("storage_path")
            storagePathPref?.let { pref ->
                updateStoragePathSummary(pref)
                
                pref.setOnPreferenceChangeListener { _, newValue ->
                    // Safe cast with null handling
                    val newFolderName = newValue as? String ?: return@setOnPreferenceChangeListener false
                    
                    // Update summary with new path (value will be saved by preference system)
                    val newPath = gpxManager.getStorageDirectoryForFolder(newFolderName).absolutePath
                    val accessInfo = gpxManager.getStorageAccessibilityInfo()
                    pref.summary = getString(R.string.storage_path_full_summary, newPath, accessInfo.message)
                    
                    // Return true to allow the preference to be saved
                    true
                }
            }
        }
        
        private fun updateStoragePathSummary(pref: EditTextPreference) {
            val accessInfo = gpxManager.getStorageAccessibilityInfo()
            pref.summary = getString(R.string.storage_path_full_summary, accessInfo.fullPath, accessInfo.message)
        }
    }
}
