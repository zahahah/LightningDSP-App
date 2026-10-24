package lightning.dsp.interop

import android.content.Context
import android.content.Intent
import lightning.dsp.model.ProcessorMessage
import lightning.dsp.utils.Constants
import lightning.dsp.utils.extensions.ContextExtensions.sendLocalBroadcast
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import timber.log.Timber
import java.io.Serializable

class ProcessorMessageHandler : LightningDspWrapper.LightningDspCallbacks, KoinComponent
{
    private val context: Context by inject()

    // Broadcast processor message
    private fun broadcastProcessorMessage(type: ProcessorMessage.Type, params: Map<ProcessorMessage.Param, Serializable>? = null){
        val intent = Intent(Constants.ACTION_PROCESSOR_MESSAGE)
        intent.putExtra(ProcessorMessage.TYPE, type.value)
        params?.forEach { (k, v) ->
            intent.putExtra(k.name, v)
        }
        context.sendLocalBroadcast(intent)
    }

    override fun onLiveprogOutput(message: String) {
        broadcastProcessorMessage(ProcessorMessage.Type.LiveprogOutput, mapOf(
            ProcessorMessage.Param.LiveprogStdout to message
        ))
    }

    override fun onLiveprogExec(id: String) {
        broadcastProcessorMessage(ProcessorMessage.Type.LiveprogExec, mapOf(
            ProcessorMessage.Param.LiveprogFileId to id
        ))
        Timber.v("onLiveprogExec: $id")
    }

    override fun onLiveprogResult(
        resultCode: Int,
        id: String,
        errorMessage: String?
    ) {
        broadcastProcessorMessage(ProcessorMessage.Type.LiveprogResult, mapOf(
            ProcessorMessage.Param.LiveprogResultCode to resultCode,
            ProcessorMessage.Param.LiveprogFileId to id,
            ProcessorMessage.Param.LiveprogErrorMessage to (errorMessage ?: "")
        ))
        Timber.v("onLiveprogResult: $resultCode; message: $errorMessage")
    }

    override fun onVdcParseError() {
        broadcastProcessorMessage(ProcessorMessage.Type.VdcParseError)
        Timber.v("onVdcParseError")
    }

    override fun onConvolverParseError(errorCode: ProcessorMessage.ConvolverErrorCode) {
        broadcastProcessorMessage(ProcessorMessage.Type.ConvolverParseError, mapOf(
            ProcessorMessage.Param.ConvolverErrorCode to errorCode.value
        ))
        Timber.v("onConvolverParseError")
    }
}