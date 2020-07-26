package com.bael.kirin.lib.network.interactor

import com.bael.kirin.lib.logger.contract.Logger
import com.bael.kirin.lib.threading.contract.Threading
import com.bael.kirin.lib.threading.util.Util.IOThread
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext

/**
 * Created by ErickSumargo on 15/06/20.
 */

abstract class BaseInteractor : Threading {
    override val coroutineContext: CoroutineContext get() = IOThread

    @Inject
    protected lateinit var logger: Logger

    private lateinit var job: Job

    override fun execute(
        thread: CoroutineContext,
        block: suspend CoroutineScope.() -> Unit
    ) {
        try {
            if (::job.isInitialized && job.isActive) job.cancel()
            job = async(
                context = thread,
                block = block
            )
        } catch (cause: Exception) {
            logger.log(cause)
        }
    }
}