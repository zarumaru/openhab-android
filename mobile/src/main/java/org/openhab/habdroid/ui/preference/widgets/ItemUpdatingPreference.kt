/*
 * Copyright (c) 2010-2024 Contributors to the openHAB project
 *
 * See the NOTICE file(s) distributed with this work for additional
 * information.
 *
 * This program and the accompanying materials are made available under the
 * terms of the Eclipse Public License 2.0 which is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * SPDX-License-Identifier: EPL-2.0
 */

package org.openhab.habdroid.ui.preference.widgets

import android.content.Context
import android.content.res.TypedArray
import android.graphics.drawable.Drawable
import android.os.Build
import android.text.Editable
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.LayoutInflater
import android.view.View
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.LifecycleOwner
import androidx.preference.DialogPreference
import androidx.preference.PreferenceDialogFragmentCompat
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.textfield.TextInputLayout
import java.text.DateFormat
import org.openhab.habdroid.R
import org.openhab.habdroid.background.BackgroundTasksManager
import org.openhab.habdroid.background.ItemUpdateWorker
import org.openhab.habdroid.ui.preference.CustomDialogPreference
import org.openhab.habdroid.ui.setupHelpIcon
import org.openhab.habdroid.util.getPrefixForBgTasks
import org.openhab.habdroid.util.getPrefs
import org.openhab.habdroid.util.hasPermissions

class ItemUpdatingPreference constructor(context: Context, attrs: AttributeSet?) : DialogPreference(context, attrs),
    CustomDialogPreference {
    private val howtoUrl: String?
    private var summaryOn: String?
    private val summaryOff: String?
    private val iconOn: Drawable?
    private val iconOff: Drawable?
    private var value: Pair<Boolean, String>? = null
    private val workManager = WorkManager.getInstance(context)

    init {
        context.obtainStyledAttributes(attrs, R.styleable.ItemUpdatingPreference).apply {
            howtoUrl = getString(R.styleable.ItemUpdatingPreference_helpUrl)
            summaryOn = getString(R.styleable.ItemUpdatingPreference_summaryEnabled)
            summaryOff = getString(R.styleable.ItemUpdatingPreference_summaryDisabled)
            iconOn = getDrawable(R.styleable.ItemUpdatingPreference_iconEnabled)
            iconOff = getDrawable(R.styleable.ItemUpdatingPreference_iconDisabled)
            recycle()
        }

        dialogTitle = null
        setPositiveButtonText(android.R.string.ok)
        setNegativeButtonText(android.R.string.cancel)
    }

    fun startObserving(lifecycleOwner: LifecycleOwner) {
        val infoLiveData = workManager.getWorkInfosByTagLiveData(key)
        infoLiveData.observe(lifecycleOwner) {
            updateSummaryAndIcon()
        }
        updateSummaryAndIcon()
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        val setValue = getPersistedString(null)
        value = if (setValue != null) {
            setValue.toItemUpdatePrefValue()
        } else {
            // We ensure the default value is of correct type in onGetDefaultValue()
            @Suppress("UNCHECKED_CAST")
            defaultValue as Pair<Boolean, String>?
            // XXX: persist if not yet present
        }
        updateSummaryAndIcon()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any? {
        return a.getString(index).toItemUpdatePrefValue()
    }

    override fun createDialog(): DialogFragment {
        return PrefDialogFragment.newInstance(key)
    }

    fun setValue(checked: Boolean = this.value?.first ?: false, value: String = this.value?.second.orEmpty()) {
        val newValue = Pair(checked, value)
        if (callChangeListener(newValue)) {
            if (shouldPersist()) {
                persistString("${newValue.first}|${newValue.second}")
            }
            this.value = newValue
            updateSummaryAndIcon()
        }
    }

    private fun updateSummaryAndIcon() {
        val value = value ?: return
        val summary = if (value.first) summaryOn else summaryOff
        val prefix = context.getPrefs().getPrefixForBgTasks()
        val lastUpdateSummarySuffix = buildLastUpdateSummary().let { lastUpdate ->
            if (lastUpdate != null) "\n$lastUpdate" else ""
        }
        setSummary(summary.orEmpty().format(prefix + value.second) + lastUpdateSummarySuffix)

        val icon = if (value.first) iconOn else iconOff
        if (icon != null) {
            setIcon(icon)
        }
    }

    private fun buildLastUpdateSummary(): String? {
        if (value?.first != true) {
            return null
        }
        val lastWork = workManager.getWorkInfosByTag(key)
            .get()
            .lastOrNull { workInfo -> workInfo.state == WorkInfo.State.SUCCEEDED }
            ?: return null

        val dateFormat = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT)
        val ts = lastWork.outputData.getLong(ItemUpdateWorker.OUTPUT_DATA_TIMESTAMP, 0)
        val value = lastWork.outputData.getString(ItemUpdateWorker.OUTPUT_DATA_SENT_VALUE)
        return context.getString(R.string.item_update_summary_success, value, dateFormat.format(ts))
    }

    fun setSummaryOn(summary: String) {
        summaryOn = summary
        updateSummaryAndIcon()
    }

    class PrefDialogFragment : PreferenceDialogFragmentCompat(), CompoundButton.OnCheckedChangeListener, TextWatcher {
        private lateinit var helpIcon: ImageView
        private lateinit var switch: MaterialSwitch
        private lateinit var editorWrapper: TextInputLayout
        private lateinit var editor: EditText
        private lateinit var permissionHint: TextView

        override fun onCreateDialogView(context: Context): View {
            val inflater = LayoutInflater.from(activity)
            val v = inflater.inflate(R.layout.item_updating_pref_dialog, null)
            val pref = preference as ItemUpdatingPreference

            switch = v.findViewById(R.id.enabled)
            switch.setOnCheckedChangeListener(this)
            editor = v.findViewById(R.id.itemName)
            editor.addTextChangedListener(this)
            editorWrapper = v.findViewById(R.id.itemNameWrapper)
            helpIcon = v.findViewById(R.id.help_icon)
            helpIcon.setupHelpIcon(pref.howtoUrl.orEmpty(), R.string.settings_item_update_pref_howto_summary)
            permissionHint = v.findViewById(R.id.permission_hint)
            permissionHint.setText(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    R.string.settings_background_tasks_permission_hint
                } else {
                    R.string.settings_background_tasks_permission_hint_pre_r
                }
            )

            val requiredPermissions = BackgroundTasksManager.getRequiredPermissionsForTask(pref.key)
            permissionHint.isVisible =
                requiredPermissions != null && context.hasPermissions(requiredPermissions) == false

            val label = v.findViewById<TextView>(R.id.enabledLabel)
            label.text = pref.title

            val value = pref.value
            if (value != null) {
                switch.isChecked = value.first
                editor.setText(value.second)
            }

            onCheckedChanged(switch, switch.isChecked)

            return v
        }

        override fun onDialogClosed(positiveResult: Boolean) {
            if (positiveResult) {
                val pref = preference as ItemUpdatingPreference
                pref.setValue(switch.isChecked, editor.text.toString())
            }
        }

        override fun onStart() {
            super.onStart()
            updateOkButtonState()
        }

        override fun onCheckedChanged(button: CompoundButton, checked: Boolean) {
            editorWrapper.isEnabled = checked
            updateOkButtonState()
        }

        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            // no-op
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            // no-op
        }

        override fun afterTextChanged(s: Editable) {
            val value = s.toString()
            if (value.trim().isEmpty() || value.contains(" ") || value.contains("\n")) {
                editorWrapper.error = context?.getString(R.string.error_no_valid_item_name)
            } else {
                editorWrapper.error = null
            }
            updateOkButtonState()
        }

        private fun updateOkButtonState() {
            val dialog = this.dialog
            if (dialog is AlertDialog) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled =
                    !editor.isEnabled || editorWrapper.error == null
            }
        }

        companion object {
            fun newInstance(key: String): PrefDialogFragment {
                val f = PrefDialogFragment()
                f.arguments = bundleOf(ARG_KEY to key)
                return f
            }
        }
    }
}

fun String?.toItemUpdatePrefValue(): Pair<Boolean, String> {
    val pos = this?.indexOf('|')
    if (pos == null || pos < 0) {
        return Pair(false, "")
    }
    return Pair(this!!.substring(0, pos).toBoolean(), substring(pos + 1))
}
