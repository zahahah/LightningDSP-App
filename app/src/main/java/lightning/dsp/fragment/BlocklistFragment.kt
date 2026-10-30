package lightning.dsp.fragment

import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import lightning.dsp.MainApplication
import lightning.dsp.R
import lightning.dsp.adapter.AppBlocklistAdapter
import lightning.dsp.databinding.FragmentBlocklistBinding
import lightning.dsp.model.AppInfo
import lightning.dsp.model.ItemViewModel
import lightning.dsp.model.room.AppBlocklistViewModel
import lightning.dsp.model.room.AppBlocklistViewModelFactory
import lightning.dsp.model.room.BlockedApp
import lightning.dsp.session.dump.DumpManager
import lightning.dsp.session.rootless.SessionRecordingPolicyManager
import lightning.dsp.utils.extensions.ContextExtensions.getAppIcon
import lightning.dsp.utils.extensions.ContextExtensions.getAppNameFromUidSafe
import lightning.dsp.utils.extensions.ContextExtensions.showAlert
import lightning.dsp.utils.extensions.ContextExtensions.showYesNoAlert
import lightning.dsp.utils.extensions.asHtml
import lightning.dsp.utils.isRootless
import lightning.dsp.utils.preferences.Preferences
import org.koin.android.ext.android.inject

class BlocklistFragment : Fragment() {

    private lateinit var binding: FragmentBlocklistBinding
    private lateinit var appsListFragment: AppsListFragment
    private lateinit var adapter: AppBlocklistAdapter

    private val dumpManager: DumpManager by inject()
    private val preferences: Preferences.App by inject()

    private val viewModel: AppBlocklistViewModel by viewModels {
        AppBlocklistViewModelFactory((requireActivity().application as MainApplication).blockedAppRepository)
    }
    private val appListViewModel: ItemViewModel<AppInfo> by viewModels()

    private val iconCache: HashMap<Int, Drawable> = hashMapOf()

    private lateinit var sessionRecordingPolicyManager: SessionRecordingPolicyManager
    private val policyPollingScope = CoroutineScope(Dispatchers.Main)
    private var restrictedApps = arrayOf<String>()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        binding = FragmentBlocklistBinding.inflate(layoutInflater, container, false)

        sessionRecordingPolicyManager = SessionRecordingPolicyManager(requireContext())

        appsListFragment = AppsListFragment()
        appListViewModel.selectedItem.observe(viewLifecycleOwner) { (appName, packageName, _, _, uid) ->
            // TODO merge BlockedApp and AppInfo?
            viewModel.insert(BlockedApp(uid, packageName, appName))
        }

        adapter = AppBlocklistAdapter()
        adapter.setOnItemClickListener {
            requireContext().showYesNoAlert(R.string.blocklist_delete_title, R.string.blocklist_delete) { confirm ->
                if(confirm)
                    viewModel.delete(it)
            }
        }

        binding.recyclerview.adapter = adapter
        binding.recyclerview.layoutManager = LinearLayoutManager(requireContext())

        binding.notice.isVisible = false
        binding.notice.setOnClickListener {
            requireContext().showAlert(
                getString(R.string.blocklist_unsupported_apps),
                getString(R.string.blocklist_unsupported_apps_message, restrictedApps.joinToString("<br/>")).asHtml()
            )
        }
        updateUnsupportedApps()

        viewModel.blockedApps.observe(requireActivity()) { apps ->
            val isEmpty = apps == null || apps.isEmpty()

            // Update the cached copy of the blocked apps in the adapter.
            apps?.let { list ->
                list.forEach {
                    it.appIcon = if(iconCache.containsKey(it.uid))
                        iconCache[it.uid]
                    else {
                        it.packageName?.let { pkg ->
                            val icon = requireContext().getAppIcon(pkg)
                            icon?.let { i ->
                                iconCache[it.uid] = i
                            }
                            icon
                        }
                    }
                }
                adapter.submitList(list.sortedBy { it.appName })
            }
            binding.recyclerview.isVisible = !isEmpty
            binding.emptyview.isVisible = isEmpty
        }

        return binding.root
    }

    override fun onDestroyView() {
        sessionRecordingPolicyManager.destroy()
        super.onDestroyView()
    }

    override fun onResume() {
        updateUnsupportedApps()
        super.onResume()
    }

    fun showAppSelector() {
        if(!appsListFragment.isAdded)
            appsListFragment.show(childFragmentManager, AppsListFragment::class.java.name)
    }

    private fun updateUnsupportedApps() {
        if(isRootless()) {
            policyPollingScope.launch {
                val isAllowed = preferences.get<Boolean>(R.string.key_session_exclude_restricted)

                restrictedApps = if (!isAllowed) {
                    arrayOf()
                } else {
                    dumpManager.dumpCaptureAllowlistLog()?.let {
                        sessionRecordingPolicyManager.update(it)
                    }

                    sessionRecordingPolicyManager
                        .getRestrictedUids()
                        .map { """${requireContext().getAppNameFromUidSafe(it)} (UID $it)""" }
                        .toTypedArray()
                }

                this@BlocklistFragment.binding.notice.isVisible = restrictedApps.isNotEmpty()
                context?.let {
                    this@BlocklistFragment.binding.noticeLabel.text = it.resources.getQuantityString(
                        R.plurals.unsupported_apps,
                        restrictedApps.size,
                        restrictedApps.size
                    )
                }
            }
        }
    }

    companion object {
        fun newInstance(): BlocklistFragment {
            return BlocklistFragment()
        }
    }
}