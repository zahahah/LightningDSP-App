package me.timschneeberger.rootlessjamesdsp.fragment

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import me.timschneeberger.rootlessjamesdsp.flavor.RootShellImpl
import me.timschneeberger.rootlessjamesdsp.R
import me.timschneeberger.rootlessjamesdsp.databinding.FragmentLibraryLoadErrorBinding
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.openPlayStoreApp
import me.timschneeberger.rootlessjamesdsp.utils.extensions.ContextExtensions.toast

class LibraryLoadErrorFragment : Fragment() {
    private lateinit var binding: FragmentLibraryLoadErrorBinding

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentLibraryLoadErrorBinding.inflate(layoutInflater, container, false)
        binding.rootlessNotice.setOnClickListener {
            requireContext().openPlayStoreApp("me.timschneeberger.rootlessjamesdsp")
        }
        binding.retryButton.setOnClickListener {
            binding.retryButton.isEnabled = false
            RootShellImpl.getShell(object : RootShellImpl.OnShellAttachedCallback {
                override fun onShellAttached(isRoot: Boolean) {
                    val host = activity ?: return
                    if (!isRoot) {
                        host.runOnUiThread {
                            if (isAdded) {
                                binding.retryButton.isEnabled = true
                                host.toast(getString(R.string.load_fail_root_required))
                            }
                        }
                        return
                    }
                    Thread {
                        val recovered = RootShellImpl.cmd(
                            "sh /data/adb/modules/ainur_jamesdsp/audio-hal-recovery.sh --retry"
                        )
                        host.runOnUiThread {
                            if (!isAdded) return@runOnUiThread
                            if (recovered) host.recreate() else {
                                binding.retryButton.isEnabled = true
                                host.toast(getString(R.string.load_fail_recovery_failed))
                            }
                        }
                    }.start()
                }
            })
        }
        return binding.root
    }

    companion object {
        fun newInstance(): LibraryLoadErrorFragment {
            return LibraryLoadErrorFragment()
        }
    }
}
