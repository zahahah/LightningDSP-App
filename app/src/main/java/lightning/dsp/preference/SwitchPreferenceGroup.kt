package lightning.dsp.preference

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.preference.Preference
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceViewHolder
import androidx.preference.children
import com.google.android.material.materialswitch.MaterialSwitch
import lightning.dsp.R
import lightning.dsp.utils.extensions.animatedValueAs


@SuppressLint("PrivateResource")
class SwitchPreferenceGroup(context: Context, attrs: AttributeSet) : PreferenceGroup(
    context, attrs, androidx.preference.R.attr.preferenceStyle,
    androidx.preference.R.style.Preference_SwitchPreferenceCompat_Material
) {
    private var childrenVisible = false
    private var switch: MaterialSwitch? = null
    private var itemView: View? = null
    private var bgAnimation: ValueAnimator? = null
    private var isIconVisible: Boolean = false
    private var state = false

    init {
        layoutResource = R.layout.preference_switchgroup
        widgetLayoutResource = R.layout.preference_materialswitch
    }

    override fun onSetInitialValue(defaultValue: Any?) {
        setValueInternal(getPersistedBoolean((defaultValue as? Boolean) ?: false), true)
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Any = a.getBoolean(index, false)

    override fun onBindViewHolder(holder: PreferenceViewHolder) {
        super.onBindViewHolder(holder)

        cancelHeaderAnimation()
        itemView = holder.itemView
        itemView?.background = ContextCompat.getDrawable(context, R.drawable.shape_rounded_highlight)?.mutate()?.apply {
            alpha = if (this@SwitchPreferenceGroup.state) TRANSITION_MAX else TRANSITION_MIN
        }

        setChildrenVisibility(state)
        setIsIconVisible(isIconVisible)

        switch = (holder.findViewById(R.id.switchWidget) as MaterialSwitch).apply {
            // A recycled holder may still have a listener from an earlier bind.
            setOnCheckedChangeListener(null)
            isChecked = state
            isVisible = isSelectable

            setOnCheckedChangeListener { _, isChecked ->
                setValueInternal(isChecked, false)
            }
        }

        holder.itemView.apply {
            setOnClickListener {
                switch?.toggle()
            }
        }
    }

    override fun onDetached() {
        cancelHeaderAnimation()
        switch = null
        itemView = null
        super.onDetached()
    }

    override fun onPrepareAddPreference(preference: Preference): Boolean {
        preference.isVisible = childrenVisible
        return super.onPrepareAddPreference(preference)
    }

    fun setIsIconVisible(value: Boolean) {
        isIconVisible = value
        itemView?.findViewById<View>(R.id.icon_frame)?.isVisible = value
    }

    fun setValue(value: Boolean) {
        setValueInternal(value, true)
    }

    private fun setValueInternal(value: Boolean, notifyChanged: Boolean) {
        setChildrenVisibility(value)
        if (state != value) {
            animateHeaderState(value)

            state = value
            persistBoolean(state)
            if (notifyChanged) {
                notifyChanged()
            }
        }
    }

    private fun cancelHeaderAnimation() {
        bgAnimation?.apply {
            cancel()
            removeAllUpdateListeners()
        }
        bgAnimation = null
    }

    private fun animateHeaderState(selected: Boolean) {
        cancelHeaderAnimation()
        val background = itemView?.background ?: return
        val target = if (selected) TRANSITION_MAX else TRANSITION_MIN
        if (background.alpha == target) return
        bgAnimation = ValueAnimator.ofInt(background.alpha, target).apply {
            duration = 200 // milliseconds
            addUpdateListener { animator ->
                background.alpha = animator.animatedValueAs<Int>() ?: target
            }
            start()
        }
    }

    private fun setChildrenVisibility(visible: Boolean) {
        children.forEach { it.isVisible = visible }
    }

    companion object {
        private const val TRANSITION_MIN = 0
        private const val TRANSITION_MAX = 255
    }
}