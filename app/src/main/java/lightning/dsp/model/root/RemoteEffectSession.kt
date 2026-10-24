package lightning.dsp.model.root

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import lightning.dsp.interop.LightningDspRemoteEngine
import lightning.dsp.model.IEffectSession

data class RemoteEffectSession(
    override var packageName: String,
    override var uid: Int,
    var effect: LightningDspRemoteEngine?
) : CoroutineScope by CoroutineScope(Dispatchers.Default), IEffectSession {
    override fun toString(): String {
        return "package=$packageName; uid=$uid"
    }
}