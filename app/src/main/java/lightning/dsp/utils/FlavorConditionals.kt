@file:Suppress("NOTHING_TO_INLINE")
package lightning.dsp.utils

import lightning.dsp.BuildConfig

inline fun isRootless() = BuildConfig.ROOTLESS && !BuildConfig.PLUGIN
inline fun isRoot() = !BuildConfig.ROOTLESS && !BuildConfig.PLUGIN
inline fun isPlugin() = BuildConfig.PLUGIN
