package lightning.dsp.activity

import android.os.Bundle
import lightning.dsp.R
import lightning.dsp.databinding.ActivityBlocklistBinding
import lightning.dsp.fragment.BlocklistFragment

class BlocklistActivity : BaseActivity() {

    private lateinit var binding: ActivityBlocklistBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityBlocklistBinding.inflate(layoutInflater)

        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)

        if (savedInstanceState == null) {
            supportFragmentManager
                .beginTransaction()
                .replace(R.id.blocklist_host, BlocklistFragment.newInstance())
                .commit()
        }

        binding.fab.setOnClickListener {
            (supportFragmentManager.findFragmentById(R.id.blocklist_host) as BlocklistFragment).showAppSelector()
        }

        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        binding.toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }
    }
}