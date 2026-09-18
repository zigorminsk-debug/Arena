package ai.arena.mobile

import android.content.Intent
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.isVisible
import com.google.android.material.bottomsheet.BottomSheetDialog
import ai.arena.mobile.databinding.ItemProfileRowBinding
import ai.arena.mobile.databinding.SheetProfileEditBinding
import ai.arena.mobile.databinding.SheetProfilesBinding
import ai.arena.mobile.databinding.SheetSettingsBinding

/** Нижние шторки: быстрый переход между профилями, настройки профиля и приложения. */
object Sheets {

    fun showSwitch(activity: AppCompatActivity, currentId: String) {
        val binding = SheetProfilesBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)

        ProfileStore.all(activity).forEach { profile ->
            val row = ItemProfileRowBinding.inflate(activity.layoutInflater, binding.rows, false)
            row.tvAvatar.background = circleDrawable(parseColorSafe(profile.color))
            row.tvAvatar.text = profileInitial(profile)
            row.tvName.text = profile.name
            row.tvSubtitle.text = profileSubtitle(activity, profile)
            row.tvCheck.isVisible = profile.id == currentId
            if (profile.id == currentId) {
                row.tvName.setTextColor(Color.parseColor(profile.color))
            }
            row.row.setOnClickListener {
                dialog.dismiss()
                if (profile.id != currentId) ProfileRouter.switch(activity, profile.id)
            }
            row.row.setOnLongClickListener {
                dialog.dismiss()
                showEdit(activity, profile.id) { }
                true
            }
            binding.rows.addView(row.root)
        }

        binding.btnManage.setOnClickListener {
            dialog.dismiss()
            ProfileRouter.openChooser(activity)
        }

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun showEdit(
        activity: AppCompatActivity,
        profileId: String,
        onResetRequest: (() -> Unit)? = null,
        onSaved: () -> Unit,
    ) {
        val binding = SheetProfileEditBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)
        val density = activity.resources.displayMetrics.density
        var color = ProfileStore.get(activity, profileId).color

        binding.tvHint.text = activity.getString(R.string.sheet_edit_hint, profileId)

        val initial = ProfileStore.get(activity, profileId)
        binding.etName.setText(initial.name)
        binding.etAccount.setText(initial.account)
        binding.etGithub.setText(initial.github)
        binding.swDesktop.isChecked = initial.desktopMode
        binding.swCompat.isChecked = initial.googleCompat
        binding.swKeepOn.isChecked = initial.keepScreenOn

        val dots = mutableListOf<Pair<String, TextView>>()
        val size = (density * 34).toInt()

        fun renderColors() {
            dots.forEach { (hex, view) ->
                view.text = if (hex == color) "✓" else ""
            }
        }

        ProfileStore.PALETTE.forEach { hex ->
            val dot = TextView(activity).apply {
                layoutParams = LinearLayout.LayoutParams(size, size).apply {
                    marginEnd = (density * 8).toInt()
                }
                gravity = Gravity.CENTER
                textSize = 15f
                setTextColor(Color.WHITE)
                background = circleDrawable(parseColorSafe(hex))
                setOnClickListener {
                    color = hex
                    renderColors()
                }
            }
            dots.add(hex to dot)
            binding.colorRow.addView(dot)
        }
        renderColors()

        binding.btnSave.setOnClickListener {
            val current = ProfileStore.get(activity, profileId)
            val updated = current.copy(
                name = binding.etName.text?.toString()?.trim().orEmpty().ifEmpty { current.name },
                account = binding.etAccount.text?.toString()?.trim().orEmpty(),
                github = binding.etGithub.text?.toString()?.trim().orEmpty(),
                color = color,
                desktopMode = binding.swDesktop.isChecked,
                googleCompat = binding.swCompat.isChecked,
                keepScreenOn = binding.swKeepOn.isChecked,
            )
            ProfileStore.update(activity, updated)
            Toast.makeText(activity, R.string.toast_saved, Toast.LENGTH_SHORT).show()
            onSaved()
            dialog.dismiss()
        }

        binding.btnReset.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_reset_title)
                .setMessage(R.string.dialog_reset_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_reset) { _, _ ->
                    if (onResetRequest != null) {
                        onResetRequest()
                    } else {
                        ProfileResetter.reset(activity, profileId)
                        Toast.makeText(activity, R.string.toast_reset_done, Toast.LENGTH_SHORT).show()
                    }
                    onSaved()
                    dialog.dismiss()
                }
                .show()
        }

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun showSettings(activity: AppCompatActivity, onChange: () -> Unit) {
        val binding = SheetSettingsBinding.inflate(activity.layoutInflater)
        val dialog = BottomSheetDialog(activity)

        val settings = SettingsStore.read(activity)
        binding.swLight.isChecked = settings.lightTheme
        binding.swZoom.isChecked = settings.pinchZoom
        binding.swPull.isChecked = settings.pullToRefresh
        binding.tvVersion.text = activity.getString(
            R.string.about_version,
            BuildConfig.VERSION_NAME,
            BuildConfig.VERSION_CODE,
        )

        binding.swLight.setOnCheckedChangeListener { _, checked ->
            SettingsStore.write(activity, SettingsStore.read(activity).copy(lightTheme = checked))
            AppCompatDelegate.setDefaultNightMode(
                if (checked) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
            )
            onChange()
        }

        binding.swZoom.setOnCheckedChangeListener { _, checked ->
            SettingsStore.write(activity, SettingsStore.read(activity).copy(pinchZoom = checked))
            onChange()
        }

        binding.swPull.setOnCheckedChangeListener { _, checked ->
            SettingsStore.write(activity, SettingsStore.read(activity).copy(pullToRefresh = checked))
            onChange()
        }

        binding.btnResetAll.setOnClickListener {
            AlertDialog.Builder(activity)
                .setTitle(R.string.dialog_reset_all_title)
                .setMessage(R.string.dialog_reset_all_message)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.action_reset) { _, _ ->
                    ProfileResetter.resetAll(activity)
                    Toast.makeText(activity, R.string.toast_reset_done, Toast.LENGTH_SHORT).show()
                    onChange()
                    dialog.dismiss()
                }
                .show()
        }

        dialog.setContentView(binding.root)
        dialog.show()
    }

    fun showAbout(activity: AppCompatActivity) {
        val message = activity.getString(R.string.about_message) + "\n\n" +
            activity.getString(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE)
        AlertDialog.Builder(activity)
            .setTitle(R.string.about_title)
            .setMessage(message)
            .setNegativeButton(android.R.string.cancel, null)
            .setNeutralButton(R.string.link_help) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Links.HELP)))
                } catch (t: Throwable) {
                    // ignore
                }
            }
            .setPositiveButton(R.string.link_leaderboard) { _, _ ->
                try {
                    activity.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Links.LEADERBOARD)))
                } catch (t: Throwable) {
                    // ignore
                }
            }
            .show()
    }
}
