package com.frank.videosubtitle.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.frank.videosubtitle.R
import com.frank.videosubtitle.databinding.FragmentSettingsStyleBinding
import com.frank.videosubtitle.domain.engine.SubtitleAlignment
import com.frank.videosubtitle.domain.model.AppSettings
import com.frank.videosubtitle.domain.model.SubtitleColor
import com.frank.videosubtitle.ui.common.BaseFragment
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.activityViewModel

class SettingsStyleFragment :
    BaseFragment<FragmentSettingsStyleBinding>(FragmentSettingsStyleBinding::inflate) {

    private val viewModel: SettingsViewModel by activityViewModel()

    private var suppressCallbacks = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }

        binding.sliderFontSize.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            viewModel.setFontSize(value.toInt())
        }
        binding.sliderFontSizeTr.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            viewModel.setFontSizeTranslated(value.toInt())
        }
        binding.sliderMarginV.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            viewModel.setMarginV(value.toInt())
        }
        binding.sliderMarginH.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            viewModel.setMarginH(value.toInt())
        }
        binding.sliderBgOpacity.addOnChangeListener { _, value, fromUser ->
            if (!fromUser) return@addOnChangeListener
            viewModel.setBackgroundOpacity(value.toInt())
        }

        binding.groupColor.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            colorFromId(checkedId, translated = false)?.let { viewModel.setFontColor(it) }
        }
        binding.groupColorTr.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            colorFromId(checkedId, translated = true)?.let { viewModel.setFontColorTranslated(it) }
        }
        binding.groupAlignment.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked || suppressCallbacks) return@addOnButtonCheckedListener
            val align = when (checkedId) {
                R.id.align_bottom -> SubtitleAlignment.BottomCenter
                R.id.align_top -> SubtitleAlignment.TopCenter
                else -> return@addOnButtonCheckedListener
            }
            viewModel.setAlignment(align)
        }

        binding.switchOutline.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setOutline(checked)
        }
        binding.switchOutlineTr.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setOutlineTranslated(checked)
        }
        binding.switchBackground.setOnCheckedChangeListener { _, checked ->
            if (suppressCallbacks) return@setOnCheckedChangeListener
            viewModel.setBackground(checked)
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { render(it) }
            }
        }
    }

    private fun colorFromId(checkedId: Int, translated: Boolean): SubtitleColor? = when (checkedId) {
        R.id.color_white, R.id.color_tr_white -> SubtitleColor.White
        R.id.color_yellow, R.id.color_tr_yellow -> SubtitleColor.Yellow
        R.id.color_green, R.id.color_tr_green -> SubtitleColor.LimeGreen
        else -> null
    }.takeIf {
        // Sanity: ensure id matches the side we're handling (avoids cross-fire if IDs collide).
        if (translated) checkedId in listOf(R.id.color_tr_white, R.id.color_tr_yellow, R.id.color_tr_green)
        else checkedId in listOf(R.id.color_white, R.id.color_yellow, R.id.color_green)
    }

    private fun render(s: AppSettings) {
        suppressCallbacks = true
        try {
            binding.sliderFontSize.value = s.fontSize.toFloat()
            binding.sliderFontSizeTr.value = s.fontSizeTranslated.toFloat()
            binding.sliderMarginV.value = s.marginV.toFloat()
            binding.sliderMarginH.value = s.marginH.toFloat()
            binding.sliderBgOpacity.value = s.backgroundOpacity.toFloat()

            binding.labelFontSize.text = getString(R.string.settings_font_size, s.fontSize)
            binding.labelFontSizeTr.text = getString(R.string.settings_font_size, s.fontSizeTranslated)
            binding.labelMarginV.text = getString(R.string.settings_margin_v, s.marginV)
            binding.labelMarginH.text = getString(R.string.settings_margin_h, s.marginH)
            binding.labelBgOpacity.text =
                getString(R.string.settings_background_opacity, s.backgroundOpacity)

            binding.groupColor.check(colorButton(s.fontColor, translated = false))
            binding.groupColorTr.check(colorButton(s.fontColorTranslated, translated = true))
            binding.groupAlignment.check(
                when (s.alignment) {
                    SubtitleAlignment.TopCenter -> R.id.align_top
                    else -> R.id.align_bottom
                },
            )
            binding.switchOutline.isChecked = s.outline
            binding.switchOutlineTr.isChecked = s.outlineTranslated
            binding.switchBackground.isChecked = s.background

            renderPreview(s)
        } finally {
            suppressCallbacks = false
        }
    }

    private fun colorButton(color: SubtitleColor, translated: Boolean): Int = when (color) {
        SubtitleColor.White -> if (translated) R.id.color_tr_white else R.id.color_white
        SubtitleColor.Yellow -> if (translated) R.id.color_tr_yellow else R.id.color_yellow
        SubtitleColor.LimeGreen -> if (translated) R.id.color_tr_green else R.id.color_green
    }

    private fun renderPreview(s: AppSettings) {
        binding.previewMain.apply {
            textSize = s.fontSize.toFloat()
            setTextColor(s.fontColor.argb)
            setShadowOutline(s.outline)
        }
        binding.previewTranslated.apply {
            textSize = s.fontSizeTranslated.toFloat()
            setTextColor(s.fontColorTranslated.argb)
            setShadowOutline(s.outlineTranslated)
        }

        // Translate alignment + offsets onto the FrameLayout child positioning.
        val container = binding.previewTextContainer
        val bg = binding.previewSubtitleBg
        val gravity = when (s.alignment) {
            SubtitleAlignment.TopCenter -> Gravity.CENTER_HORIZONTAL or Gravity.TOP
            else -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        }
        // Scale ASS pixel margins down to dp-friendly preview values; 1 ASS px ≈ 0.5 dp here.
        val previewMarginV = (s.marginV * 0.5f).toInt()
        val previewMarginH = (s.marginH * 0.5f).toInt()
        listOf(container, bg).forEach { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.gravity = gravity
            val baseV = dp(16)
            val baseH = dp(16)
            if (s.alignment == SubtitleAlignment.TopCenter) {
                lp.topMargin = baseV + dp(previewMarginV)
                lp.bottomMargin = 0
            } else {
                lp.bottomMargin = baseV + dp(previewMarginV)
                lp.topMargin = 0
            }
            // marginH > 0 → push right (smaller right margin, bigger left margin).
            // ASS treats MarginL/MarginR independently from center alignment, but
            // for the preview we collapse to a single horizontal offset.
            lp.leftMargin = baseH + dp(maxOf(0, previewMarginH))
            lp.rightMargin = baseH + dp(maxOf(0, -previewMarginH))
            v.layoutParams = lp
        }

        // Background box: sized to match the text container, faded by opacity.
        if (s.background) {
            bg.isVisible = true
            val alpha = (s.backgroundOpacity.coerceIn(0, 100) * 255 / 100)
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(4).toFloat()
                setColor(Color.argb(alpha, 0, 0, 0))
            }
            bg.background = drawable
            // Match the text container's width/height once it lays out.
            container.post {
                val lp = bg.layoutParams as FrameLayout.LayoutParams
                lp.width = container.width + dp(16)
                lp.height = container.height + dp(8)
                bg.layoutParams = lp
            }
        } else {
            bg.isVisible = false
        }
    }

    private fun android.widget.TextView.setShadowOutline(enabled: Boolean) {
        if (enabled) {
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        } else {
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
