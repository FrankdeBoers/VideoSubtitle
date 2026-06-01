package com.frank.videosubtitle.ui.settings

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import androidx.core.view.doOnLayout
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
        // Preview represents a 1080x720 video frame; defer until the container
        // is laid out so we know the exact pixel scale.
        val previewHeightPx = binding.previewContainer.height
        if (previewHeightPx <= 0) {
            binding.previewContainer.doOnLayout { renderPreview(s) }
            return
        }
        // ASS-style font size and ASS margins are interpreted as pixels on a
        // 720-tall video frame. Scale them to the preview's actual pixel size.
        val scale = previewHeightPx / REFERENCE_VIDEO_HEIGHT_PX

        binding.previewMain.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, s.fontSize * scale)
            setTextColor(s.fontColor.argb)
            setShadowOutline(s.outline)
        }
        binding.previewTranslated.apply {
            setTextSize(TypedValue.COMPLEX_UNIT_PX, s.fontSizeTranslated * scale)
            setTextColor(s.fontColorTranslated.argb)
            setShadowOutline(s.outlineTranslated)
        }

        val container = binding.previewTextContainer
        val bg = binding.previewSubtitleBg
        val gravity = when (s.alignment) {
            SubtitleAlignment.TopCenter -> Gravity.CENTER_HORIZONTAL or Gravity.TOP
            else -> Gravity.CENTER_HORIZONTAL or Gravity.BOTTOM
        }
        val previewMarginVPx = (s.marginV * scale).toInt()
        val previewMarginHPx = (s.marginH * scale).toInt()
        listOf(container, bg).forEach { v ->
            val lp = v.layoutParams as FrameLayout.LayoutParams
            lp.gravity = gravity
            if (s.alignment == SubtitleAlignment.TopCenter) {
                lp.topMargin = previewMarginVPx
                lp.bottomMargin = 0
            } else {
                lp.bottomMargin = previewMarginVPx
                lp.topMargin = 0
            }
            // marginH > 0 → push right (larger left margin); inverse for negative.
            lp.leftMargin = maxOf(0, previewMarginHPx)
            lp.rightMargin = maxOf(0, -previewMarginHPx)
            v.layoutParams = lp
        }

        if (s.background) {
            bg.isVisible = true
            val alpha = (s.backgroundOpacity.coerceIn(0, 100) * 255 / 100)
            val padPx = (8 * scale).toInt()
            val drawable = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = (4 * scale)
                setColor(Color.argb(alpha, 0, 0, 0))
            }
            bg.background = drawable
            container.post {
                val lp = bg.layoutParams as FrameLayout.LayoutParams
                lp.width = container.width + padPx * 2
                lp.height = container.height + padPx
                bg.layoutParams = lp
            }
        } else {
            bg.isVisible = false
        }
    }

    private companion object {
        const val REFERENCE_VIDEO_HEIGHT_PX = 720f
    }

    private fun android.widget.TextView.setShadowOutline(enabled: Boolean) {
        if (enabled) {
            setShadowLayer(3f, 0f, 0f, Color.BLACK)
        } else {
            setShadowLayer(0f, 0f, 0f, Color.TRANSPARENT)
        }
    }

}
