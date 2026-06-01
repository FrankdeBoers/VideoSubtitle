package com.frank.videosubtitle.ui.editor

import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.navigation.fragment.navArgs
import androidx.recyclerview.widget.LinearLayoutManager
import com.frank.videosubtitle.R
import com.frank.videosubtitle.data.source.local.SrtSerializer
import com.frank.videosubtitle.databinding.FragmentEditorBinding
import com.frank.videosubtitle.domain.model.SubtitleSegment
import com.frank.videosubtitle.ui.common.BaseFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import android.widget.Toast
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel
import org.koin.core.parameter.parametersOf

class EditorFragment : BaseFragment<FragmentEditorBinding>(FragmentEditorBinding::inflate) {

    private val args: EditorFragmentArgs by navArgs()

    private val viewModel: EditorViewModel by viewModel { parametersOf(args.taskId) }

    private lateinit var adapter: SegmentAdapter

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        adapter = SegmentAdapter(
            onTextClick = { pos -> showTextDialog(pos) },
            onTimeClick = { pos -> showTimeDialog(pos) },
        )
        binding.segments.layoutManager = LinearLayoutManager(requireContext())
        binding.segments.adapter = adapter

        binding.toolbar.setNavigationOnClickListener { handleBack() }
        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save -> { viewModel.save(); true }
                R.id.action_export -> { viewModel.export(); true }
                R.id.action_restore -> { confirmRestore(); true }
                else -> false
            }
        }

        requireActivity().onBackPressedDispatcher.addCallback(
            viewLifecycleOwner,
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    handleBack()
                }
            },
        )

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.uiState.collect { render(it) }
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.effects.collect { effect -> handleEffect(effect) }
            }
        }
    }

    private fun render(state: EditorUiState) {
        binding.title.text = state.title
        binding.emptyLabel.isVisible = state.loaded && state.segments.isEmpty()
        binding.segments.isVisible = state.segments.isNotEmpty()
        adapter.submitList(state.segments)
        binding.toolbar.menu.findItem(R.id.action_restore)?.isEnabled = state.originalAvailable
    }

    private fun handleEffect(effect: EditorEffect) {
        when (effect) {
            is EditorEffect.Toast -> {
                val msg = if (effect.arg != null) getString(effect.messageRes, effect.arg)
                else getString(effect.messageRes)
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
            }
            EditorEffect.NavigateBack -> findNavController().navigateUp()
        }
    }

    private fun handleBack() {
        if (!viewModel.uiState.value.isDirty) {
            findNavController().navigateUp()
            return
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.editor_unsaved_title)
            .setMessage(R.string.editor_unsaved_message)
            .setPositiveButton(R.string.editor_unsaved_save) { _, _ ->
                viewModel.save { ok -> if (ok) findNavController().navigateUp() }
            }
            .setNegativeButton(R.string.editor_unsaved_discard) { _, _ ->
                findNavController().navigateUp()
            }
            .setNeutralButton(R.string.editor_unsaved_keep_editing, null)
            .show()
    }

    private fun confirmRestore() {
        viewModel.restoreOriginal()
    }

    private fun showTextDialog(position: Int) {
        val seg = viewModel.uiState.value.segments.getOrNull(position) ?: return
        val input = TextInputEditText(requireContext()).apply {
            setText(seg.text)
            setSelection(seg.text.length)
            inputType = InputType.TYPE_CLASS_TEXT or
                InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            minLines = 2
            maxLines = 6
        }
        val container = TextInputLayout(requireContext()).apply {
            addView(input)
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.editor_dialog_text_title)
            .setView(container)
            .setPositiveButton(R.string.editor_dialog_ok) { _, _ ->
                viewModel.updateText(position, input.text?.toString().orEmpty())
            }
            .setNegativeButton(R.string.editor_dialog_cancel, null)
            .show()
    }

    private fun showTimeDialog(position: Int) {
        val seg = viewModel.uiState.value.segments.getOrNull(position) ?: return
        val ctx = requireContext()
        val startInput = TextInputEditText(ctx).apply {
            setText(SrtSerializer.formatTimestamp(seg.startMs))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val endInput = TextInputEditText(ctx).apply {
            setText(SrtSerializer.formatTimestamp(seg.endMs))
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val startLayout = TextInputLayout(ctx).apply {
            hint = getString(R.string.editor_dialog_time_start)
            addView(startInput)
        }
        val endLayout = TextInputLayout(ctx).apply {
            hint = getString(R.string.editor_dialog_time_end)
            addView(endInput)
        }
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
            addView(startLayout)
            addView(endLayout)
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.editor_dialog_time_title)
            .setView(container)
            .setPositiveButton(R.string.editor_dialog_ok, null)
            .setNegativeButton(R.string.editor_dialog_cancel, null)
            .create()
        dialog.show()
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val startMs = SrtSerializer.parseTimestamp(startInput.text?.toString()?.trim().orEmpty())
            val endMs = SrtSerializer.parseTimestamp(endInput.text?.toString()?.trim().orEmpty())
            if (startMs == null || endMs == null) {
                Toast.makeText(ctx, R.string.editor_validation_time_format, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            val err = viewModel.updateTime(position, startMs, endMs)
            if (err == null) dialog.dismiss()
            else Toast.makeText(ctx, err, Toast.LENGTH_SHORT).show()
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
