package lightning.dsp.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import lightning.dsp.R
import lightning.dsp.databinding.ActivityAppCompatibilityBinding
import lightning.dsp.fragment.AppCompatibilityFragment
import lightning.dsp.service.RootlessAudioProcessorService
import lightning.dsp.utils.extensions.CompatExtensions.getParcelableAs

class AppCompatibilityActivity : BaseActivity() {
    @SuppressLint("InlinedApi")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val binding = ActivityAppCompatibilityBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            val uid = intent.extras?.getInt(RootlessAudioProcessorService.EXTRA_APP_UID, -1) ?: -1
            val internalCall = intent.extras?.getBoolean(RootlessAudioProcessorService.EXTRA_APP_COMPAT_INTERNAL_CALL, false) ?: false
            val projectIntent = intent.extras?.getParcelableAs<Intent>(RootlessAudioProcessorService.EXTRA_MEDIA_PROJECTION_DATA)
            if(uid < 0) {
                finish()
                return
            }

            supportFragmentManager
                .beginTransaction()
                .replace(R.id.fragment, AppCompatibilityFragment.newInstance(uid, projectIntent, internalCall))
                .commit()
        }
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }

    override fun onPause() {
        super.onPause()
        prefsVar.set(R.string.key_is_app_compat_activity_active, false)
    }

    override fun onDestroy() {
        prefsVar.set(R.string.key_is_app_compat_activity_active, false)
        super.onDestroy()
    }

    override fun onResume() {
        super.onResume()
        prefsVar.set(R.string.key_is_app_compat_activity_active, true)
    }
}