package com.frank.videosubtitle.ui.common

import android.content.Context
import android.util.AttributeSet
import android.view.LayoutInflater
import android.widget.LinearLayout
import androidx.annotation.DrawableRes
import androidx.core.view.isVisible
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.ViewStateBinding

class StateView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : LinearLayout(context, attrs, defStyleAttr) {

    private val binding: ViewStateBinding

    init {
        orientation = VERTICAL
        gravity = android.view.Gravity.CENTER
        val pad = (resources.displayMetrics.density * 24).toInt()
        setPadding(pad, pad, pad, pad)
        LayoutInflater.from(context).inflate(R.layout.view_state, this, true)
        binding = ViewStateBinding.bind(this)
    }

    fun showLoading(headline: CharSequence? = null, body: CharSequence? = null) {
        binding.stateProgress.isVisible = true
        binding.stateIcon.isVisible = false
        applyText(headline, body)
        binding.stateAction.isVisible = false
    }

    fun showEmpty(@DrawableRes iconRes: Int? = null, headline: CharSequence, body: CharSequence? = null) {
        binding.stateProgress.isVisible = false
        applyIcon(iconRes)
        applyText(headline, body)
        binding.stateAction.isVisible = false
    }

    fun showError(
        @DrawableRes iconRes: Int? = android.R.drawable.stat_notify_error,
        headline: CharSequence,
        body: CharSequence? = null,
        actionLabel: CharSequence? = null,
        onAction: (() -> Unit)? = null,
    ) {
        binding.stateProgress.isVisible = false
        applyIcon(iconRes)
        applyText(headline, body)
        if (actionLabel != null && onAction != null) {
            binding.stateAction.text = actionLabel
            binding.stateAction.isVisible = true
            binding.stateAction.setOnClickListener { onAction() }
        } else {
            binding.stateAction.isVisible = false
            binding.stateAction.setOnClickListener(null)
        }
    }

    private fun applyIcon(@DrawableRes iconRes: Int?) {
        if (iconRes != null) {
            binding.stateIcon.setImageResource(iconRes)
            binding.stateIcon.isVisible = true
        } else {
            binding.stateIcon.isVisible = false
        }
    }

    private fun applyText(headline: CharSequence?, body: CharSequence?) {
        if (headline != null) {
            binding.stateHeadline.text = headline
            binding.stateHeadline.isVisible = true
        } else {
            binding.stateHeadline.isVisible = false
        }
        if (body != null) {
            binding.stateBody.text = body
            binding.stateBody.isVisible = true
        } else {
            binding.stateBody.isVisible = false
        }
    }
}
