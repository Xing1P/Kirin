package com.bael.kirin.feature.translation.service.floating

import android.content.Intent
import android.content.Intent.ACTION_VIEW
import android.content.Intent.FLAG_ACTIVITY_NEW_TASK
import android.graphics.PixelFormat.TRANSLUCENT
import android.net.Uri.parse
import android.text.InputType.TYPE_CLASS_TEXT
import android.view.Gravity.START
import android.view.Gravity.TOP
import android.view.View.GONE
import android.view.View.INVISIBLE
import android.view.View.VISIBLE
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.view.WindowManager.LayoutParams
import android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
import android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
import android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
import android.view.WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
import android.widget.ArrayAdapter
import com.bael.kirin.feature.translation.R
import com.bael.kirin.feature.translation.constant.LANGUAGE_AUTO
import com.bael.kirin.feature.translation.constant.SUBJECT_DISMISS_BACKGROUND
import com.bael.kirin.feature.translation.constant.SUBJECT_EXTRA_QUERY
import com.bael.kirin.feature.translation.constant.languages
import com.bael.kirin.feature.translation.databinding.ToggleLayoutBinding
import com.bael.kirin.feature.translation.databinding.TranslationLayoutBinding
import com.bael.kirin.feature.translation.ext.addQueryChangedListener
import com.bael.kirin.feature.translation.preference.Preference
import com.bael.kirin.feature.translation.tracker.Tracker
import com.bael.kirin.feature.translation.util.Util.retrieveDeeplink
import com.bael.kirin.feature.translation.view.listener.LayoutMovementListener
import com.bael.kirin.feature.translation.view.listener.OnKeyEventPreImeListener
import com.bael.kirin.feature.translation.view.listener.SpinnerItemSelectedListener
import com.bael.kirin.lib.api.translation.model.entity.Translation
import com.bael.kirin.lib.arch.base.BaseService
import com.bael.kirin.lib.arch.wrapper.LazyWrapper
import com.bael.kirin.lib.data.model.Data
import com.bael.kirin.lib.resource.app.AppInfo
import com.bael.kirin.lib.resource.util.Util.minOreoSdk
import com.bael.kirin.lib.ui.constant.appIcon
import com.bael.kirin.lib.ui.constant.cerise
import com.bael.kirin.lib.ui.constant.gray
import com.bael.kirin.lib.ui.ext.hideSoftKeyboard
import com.bael.kirin.lib.ui.ext.showSoftKeyboard
import com.bael.kirin.lib.ui.layout.LayoutManager
import com.bael.kirin.lib.ui.notification.NotificationFactory
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject
import com.bael.kirin.feature.translation.screen.background.UI as UIBackground

/**
 * Created by ErickSumargo on 01/06/20.
 */

@AndroidEntryPoint
@FlowPreview
@ExperimentalCoroutinesApi
class UI :
    BaseService(),
    Renderer,
    Action,
    OnKeyEventPreImeListener {
    @Inject
    lateinit var appInfo: AppInfo

    @Inject
    lateinit var layoutManager: LayoutManager

    @Inject
    lateinit var toggleBinder: ToggleLayoutBinding

    @Inject
    lateinit var translationBinder: TranslationLayoutBinding

    @Inject
    lateinit var viewModel: LazyWrapper<ViewModel>

    @Inject
    lateinit var notification: NotificationFactory

    @Inject
    lateinit var preference: Preference

    @Inject
    lateinit var tracker: Tracker

    lateinit var packet: Packet

    lateinit var dispatcher: Dispatcher

    override fun onCreate() {
        super.onCreate()
        dispatcher = Dispatcher(
            context = this,
            viewModel = viewModel.instance,
            renderer = this,
            action = this
        ).also { it.observe(lifecycleOwner = this) }
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
        val query = intent?.getStringExtra(SUBJECT_EXTRA_QUERY)
        packet = Packet(newQuery = query)
        return super.onStartCommand(intent, flags, startId)
    }

    override fun renderToggleLayout(active: Boolean) = execute {
        toggleBinder.toggleLayout.also { layout ->
            layout.setOnTouchListener(
                LayoutMovementListener(
                    windowManager = layoutManager.windowManager,
                    layoutParams = toggleLayoutParams,
                    maxDiffDistance = MAX_FLOATING_DIFF_DISTANCE,
                    onClickLayout = {
                        viewModel().setToggleActivation(
                            active = !active,
                            editMode = preference.useAutoEditingMode
                        )
                        tracker.trackToggleActivation(!active)
                    },
                    onMoveLayout = { params ->
                        layoutManager.updateLayout(layout, params)
                    },
                    onAutoAdjustPositionLayout = { params, coordsStart, coordsEnd ->
                        layoutManager.adjustPositionLayout(
                            layout,
                            params,
                            coordsStart,
                            coordsEnd
                        )
                    }
                )
            )
        }

        toggleBinder.toggleIcon.also { icon ->
            icon.setImageDrawable(appIcon.apply {
                setTint(cerise.takeIf { active } ?: gray)
            })
        }
    }

    override fun renderTranslationLayout() = execute {
        translationBinder.translationLayout.also { layout ->
            layout.setOnKeyPressListener(onKeyEventListener = this@UI)
        }
    }

    override fun renderSourceLanguageSpinner(sourceLanguage: String) = execute {
        translationBinder.sourceLanguageSpinner.also { spinner ->
            val adapter = ArrayAdapter(
                this@UI,
                R.layout.language_item_layout,
                languages.values.toTypedArray()
            )

            spinner.adapter = adapter
            spinner.setSelection(languages.keys.indexOf(sourceLanguage))
            spinner.onItemSelectedListener =
                SpinnerItemSelectedListener { index ->
                    val selectedLanguage = languages.keys.elementAt(index)
                    preference.setSourceLanguage(language = selectedLanguage)

                    viewModel().setSourceLanguage(language = selectedLanguage)
                    viewModel().translate(sourceLanguage = selectedLanguage)
                }
        }
    }

    override fun renderSwapLanguageIcon(
        sourceLanguage: String,
        targetLanguage: String
    ) = execute {
        translationBinder.swapLanguageIcon.also { icon ->
            icon.setOnClickListener {
                if (sourceLanguage != LANGUAGE_AUTO) {
                    viewModel().swapLanguage(
                        sourceLanguage = targetLanguage,
                        targetLanguage = sourceLanguage
                    )

                    preference.setSourceLanguage(language = targetLanguage)
                    preference.setTargetLanguage(language = sourceLanguage)

                    tracker.trackSwapLanguage(sourceLanguage, targetLanguage)
                }
            }
        }
    }

    override fun renderTargetLanguageSpinner(targetLanguage: String) = execute {
        translationBinder.targetLanguageSpinner.also { spinner ->
            val languages = languages.filter { it.key != LANGUAGE_AUTO }
            val adapter = ArrayAdapter(
                this@UI,
                R.layout.language_item_layout,
                languages.values.toTypedArray()
            )

            spinner.adapter = adapter
            spinner.setSelection(languages.keys.indexOf(targetLanguage))
            spinner.onItemSelectedListener =
                SpinnerItemSelectedListener { index ->
                    val selectedLanguage = languages.keys.elementAt(index)
                    preference.setTargetLanguage(language = selectedLanguage)

                    viewModel().setTargetLanguage(language = selectedLanguage)
                    viewModel().translate(targetLanguage = selectedLanguage)
                }
        }
    }

    override fun renderQueryInput(
        sourceLanguage: String,
        targetLanguage: String,
        newQuery: String?
    ) = execute {
        translationBinder.queryInput.also { input ->
            if (newQuery != null) {
                input.setText(newQuery)
                input.setSelection(newQuery.length)
            }

            input.setRawInputType(TYPE_CLASS_TEXT)

            input.setOnFocusChangeListener { _, focus ->
                if (focus) {
                    viewModel().startEditing()
                }
            }

            input.addQueryChangedListener(
                scope = this,
                onQueryChanged = { query ->
                    viewModel().setQuery(query)
                },
                onQueryFixed = { query ->
                    if (preference.useResponsiveTranslator) {
                        viewModel().translate(query = query)
                        tracker.trackTranslationData(sourceLanguage, targetLanguage, query)
                    }
                },
                onQueryDone = { query ->
                    if (!preference.useResponsiveTranslator) {
                        viewModel().translate(query = query)
                        tracker.trackTranslationData(sourceLanguage, targetLanguage, query)
                    }

                    viewModel().stopEditing()
                    tracker.trackStopEditingByKeyboard()
                }
            )

            input.setOnLongClickListener {
                tracker.trackShowContextMenu()
                false
            }
        }
    }

    override fun renderClearQueryIcon(query: String) = execute {
        translationBinder.clearQueryIcon.also { icon ->
            icon.visibility = VISIBLE.takeIf { query.isNotEmpty() } ?: INVISIBLE
            icon.setOnClickListener {
                viewModel().clearQuery()
                tracker.trackClearQuery()
            }
        }
    }

    override fun renderTranslationInput(
        sourceLanguage: String,
        targetLanguage: String,
        query: String,
        data: Data<Translation>
    ) = execute {
        translationBinder.translationInput.also { input ->
            input.alpha = 1f.takeIf { !data.isLoading() || data.isError() } ?: 0.5f
            input.isEnabled = data.let {
                !it.isLoading() && !it.result?.translatedText.isNullOrBlank() || it.isError()
            } && query.isNotBlank()
            input.text = data.result?.translatedText.takeIf { query.isNotEmpty() }.orEmpty()

            input.setOnClickListener {
                viewModel().displayResultDetail()
                tracker.trackDisplayResultDetail(
                    sourceLanguage,
                    targetLanguage,
                    query,
                    result = data.result?.translatedText.orEmpty()
                )
            }
        }
    }

    override fun renderLoadingProgress(data: Data<Translation>) = execute {
        translationBinder.loadingProgress.also { progress ->
            progress.visibility = VISIBLE.takeIf {
                data.isLoading() || data.isError()
            } ?: INVISIBLE
        }
    }

    override fun renderSwipeLayout() = execute {
        translationBinder.swipeLayout.also { layout ->
            layout.setOnTouchListener(
                LayoutMovementListener(
                    windowManager = layoutManager.windowManager,
                    layoutParams = translationLayoutParams,
                    onClickLayout = {},
                    onMoveLayout = { params ->
                        layoutManager.updateLayout(translationBinder.translationLayout, params)
                    }
                )
            )
        }
    }

    override fun processPacket() {
        if (::packet.isInitialized.not()) return
        packet.newQuery?.let(::instantTranslate)
    }

    override fun showToggleLayout() {
        layoutManager.addLayout(
            toggleBinder.toggleLayout,
            toggleLayoutParams.also { it.gravity = START }
        )
    }

    override fun addTranslationLayout() {
        translationBinder.translationLayout.also { layout ->
            layout.visibility = GONE

            layoutManager.addLayout(
                layout,
                translationLayoutParams.also { it.gravity = START or TOP }
            )
        }
    }

    override fun showTranslationLayout(editMode: Boolean) {
        translationBinder.translationLayout.visibility = VISIBLE

        if (!editMode) return
        launch(coroutineContext) {
            delay(100)
            translationBinder.queryInput.requestFocus()
        }
    }

    private fun updateTranslationLayout(flag: Int) {
        layoutManager.updateLayout(
            translationBinder.translationLayout,
            translationLayoutParams.also { it.flags = flag }
        )
    }

    override fun hideTranslationLayout() {
        translationBinder.translationLayout.visibility = GONE
    }

    override fun showBackground() {
        Intent(this, UIBackground::class.java).apply {
            addFlags(FLAG_ACTIVITY_NEW_TASK)
        }.run(::startActivity)
    }

    override fun onBackgroundShown() = Unit

    override fun dismissBackground() {
        Intent(SUBJECT_DISMISS_BACKGROUND).run(::sendBroadcast)
    }

    override fun onBackgroundDismissed() {
        viewModel().stopEditing()
    }

    override fun showSoftKeyboard() {
        updateTranslationLayout(FLAG_NOT_TOUCH_MODAL)
        translationBinder.queryInput.also { input ->
            launch(coroutineContext) {
                delay(150)
                input.showSoftKeyboard()
            }
        }
    }

    override fun hideSoftKeyboard() {
        translationBinder.queryInput.also { input ->
            input.clearFocus()
            input.hideSoftKeyboard()
        }
        updateTranslationLayout(FLAG_NOT_FOCUSABLE)
    }

    override fun onHideSoftKeyboard() {
        viewModel().stopEditing()
        tracker.trackStopEditingByBackPressed()
    }

    override fun instantTranslate(query: String) {
        viewModel().setToggleActivation(
            active = true,
            editMode = false
        )

        viewModel().setSourceLanguage(language = LANGUAGE_AUTO)

        viewModel().translate(
            toggleActive = true,
            sourceLanguage = LANGUAGE_AUTO,
            newQuery = query,
            query = query
        )

        tracker.trackToggleActivation(active = true)
    }

    override fun openGoogleTranslate(
        sourceLanguage: String,
        targetLanguage: String,
        query: String
    ) {
        try {
            val uri = parse(
                retrieveDeeplink(
                    baseUrl = preference.googleTranslateUrl,
                    sourceLanguage = sourceLanguage,
                    targetLanguage = targetLanguage,
                    query = query
                )
            )
            Intent(ACTION_VIEW, uri).apply {
                addFlags(FLAG_ACTIVITY_NEW_TASK)
            }.also(::startActivity)
        } catch (cause: Exception) {
            logger.log(cause)
        }
    }

    override fun stopService() {
        viewModel().stopEditing()
        tracker.trackToggleService(active = false)

        stopSelf()
    }

    override fun onDestroy() {
        dispatcher.clear()
        notification.dismiss(appInfo.id)

        with(layoutManager) {
            removeLayout(toggleBinder.toggleLayout)
            removeLayout(translationBinder.translationLayout)
        }
        super.onDestroy()
    }

    companion object {
        const val MAX_FLOATING_DIFF_DISTANCE: Int = 16

        val toggleLayoutParams: LayoutParams =
            if (minOreoSdk) {
                LayoutParams(
                    WRAP_CONTENT,
                    WRAP_CONTENT,
                    TYPE_APPLICATION_OVERLAY,
                    FLAG_NOT_FOCUSABLE,
                    TRANSLUCENT
                )
            } else {
                LayoutParams(
                    WRAP_CONTENT,
                    WRAP_CONTENT,
                    TYPE_SYSTEM_ALERT,
                    FLAG_NOT_FOCUSABLE,
                    TRANSLUCENT
                )
            }

        val translationLayoutParams: LayoutParams =
            if (minOreoSdk) {
                LayoutParams(
                    MATCH_PARENT,
                    WRAP_CONTENT,
                    TYPE_APPLICATION_OVERLAY,
                    FLAG_NOT_FOCUSABLE,
                    TRANSLUCENT
                )
            } else {
                LayoutParams(
                    MATCH_PARENT,
                    WRAP_CONTENT,
                    TYPE_SYSTEM_ALERT,
                    FLAG_NOT_FOCUSABLE,
                    TRANSLUCENT
                )
            }
    }
}
