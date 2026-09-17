package ai.arena.mobile

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import ai.arena.mobile.databinding.ItemHintBinding
import ai.arena.mobile.databinding.ItemProfileBinding

class ProfileAdapter(
    private val onOpen: (Profile) -> Unit,
    private val onEdit: (Profile) -> Unit,
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    private val items = mutableListOf<Any>()

    fun submit(profiles: List<Profile>) {
        items.clear()
        items.addAll(profiles)
        items.add(HINT)
        notifyDataSetChanged()
    }

    override fun getItemCount(): Int = items.size

    override fun getItemViewType(position: Int): Int =
        if (items[position] is Profile) TYPE_PROFILE else TYPE_HINT

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val inflater = LayoutInflater.from(parent.context)
        return if (viewType == TYPE_PROFILE) {
            ProfileHolder(ItemProfileBinding.inflate(inflater, parent, false))
        } else {
            HintHolder(ItemHintBinding.inflate(inflater, parent, false))
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        if (holder is ProfileHolder) {
            val profile = items.getOrNull(position) as? Profile ?: return
            bindProfile(holder, profile)
        }
    }

    private fun bindProfile(holder: ProfileHolder, profile: Profile) {
        val binding = holder.binding
        val context = binding.root.context
        binding.tvAvatar.background = circleDrawable(parseColorSafe(profile.color))
        binding.tvAvatar.text = profileInitial(profile)
        binding.tvName.text = profile.name
        binding.tvSubtitle.text = profileSubtitle(context, profile)
        binding.tvGithub.text = if (profile.github.isNotBlank()) {
            context.getString(R.string.github_connected, profile.github)
        } else {
            context.getString(R.string.github_not_connected)
        }
        binding.btnOpen.setOnClickListener { onOpen(profile) }
        binding.card.setOnClickListener { onOpen(profile) }
        binding.card.setOnLongClickListener {
            onEdit(profile)
            true
        }
        binding.btnMore.setOnClickListener { onEdit(profile) }
    }

    class ProfileHolder(val binding: ItemProfileBinding) : RecyclerView.ViewHolder(binding.root)

    class HintHolder(val binding: ItemHintBinding) : RecyclerView.ViewHolder(binding.root)

    private companion object {
        const val TYPE_PROFILE = 0
        const val TYPE_HINT = 1
        val HINT = Any()
    }
}
