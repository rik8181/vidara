package com.rik.vidaraplus

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

fun Context.oval(color: Int): GradientDrawable =
    GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

/// Round avatar: a coloured circle with the channel's initial, with the (lazily loaded) picture on top.
class AvatarView(context: Context, private val sizeDp: Int) : FrameLayout(context) {
    private val letter = context.label("", sizeDp * 0.42f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
    val image = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }

    init {
        clipToOutline = true
        addView(letter, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(image, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
    }

    fun bind(name: String) {
        val colors = intArrayOf(0xFF8E24AA.toInt(), 0xFF3949AB.toInt(), 0xFF00897B.toInt(),
            0xFFF4511E.toInt(), 0xFF6D4C41.toInt(), 0xFF039BE5.toInt(), 0xFFC62828.toInt())
        val idx = Math.abs(name.hashCode()) % colors.size
        background = context.oval(colors[idx])
        letter.text = name.trim().take(1).uppercase()
        image.setImageDrawable(null)
    }
}

/// Video cards (home, search, related) and music rows.
class FeedAdapter(
    private val api: Api,
    private val onClick: (Item, Int) -> Unit,
) : RecyclerView.Adapter<FeedAdapter.Holder>() {
    private var data: List<Item> = emptyList()
    private var music = false

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        lateinit var thumb: ImageView
        lateinit var badge: TextView
        lateinit var title: TextView
        lateinit var sub: TextView
        var avatar: AvatarView? = null
        var boundChannel: String = ""
    }

    fun submit(items: List<Item>, music: Boolean) {
        data = items
        this.music = music
        notifyDataSetChanged()
    }

    override fun getItemCount() = data.size
    override fun getItemViewType(position: Int) = if (music) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val matchW = ViewGroup.LayoutParams.MATCH_PARENT
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        if (viewType == 1) {
            val row = LinearLayout(c).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(c.dp(12), c.dp(6), c.dp(12), c.dp(6))
                layoutParams = RecyclerView.LayoutParams(matchW, wrap)
            }
            val h = Holder(row)
            h.thumb = ImageView(c).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = c.rounded(Palette.CARD, 6f)
                clipToOutline = true
            }
            val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
            h.title = c.label("", 15f, Color.WHITE).apply { maxLines = 2 }
            h.sub = c.label("", 12.5f, Palette.DIM).apply { maxLines = 1; setPadding(0, c.dp(3), 0, 0) }
            h.badge = c.label("", 11f, Palette.DIM) // nem használt
            texts.addView(h.title)
            texts.addView(h.sub)
            row.addView(h.thumb, LinearLayout.LayoutParams(c.dp(60), c.dp(60)))
            row.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f).apply { leftMargin = c.dp(14) })
            return h
        }
        val card = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = RecyclerView.LayoutParams(matchW, wrap)
        }
        val h = Holder(card)
        val thumbBox = FrameLayout(c)
        h.thumb = ImageView(c).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(Palette.CARD)
        }
        h.badge = c.label("", 12f, Color.WHITE, bold = true).apply {
            background = c.rounded(0xCC000000.toInt(), 4f)
            setPadding(c.dp(5), c.dp(2), c.dp(5), c.dp(2))
        }
        val thumbH = (c.resources.displayMetrics.widthPixels * 9f / 16f).toInt()
        thumbBox.addView(h.thumb, FrameLayout.LayoutParams(matchW, thumbH))
        thumbBox.addView(
            h.badge,
            FrameLayout.LayoutParams(wrap, wrap, Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, c.dp(8), c.dp(8))
            }
        )
        card.addView(thumbBox, LinearLayout.LayoutParams(matchW, thumbH))
        val info = LinearLayout(c).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(c.dp(12), c.dp(12), c.dp(12), c.dp(16))
        }
        h.avatar = AvatarView(c, 36)
        info.addView(h.avatar, LinearLayout.LayoutParams(c.dp(36), c.dp(36)).apply { rightMargin = c.dp(12) })
        val texts = LinearLayout(c).apply { orientation = LinearLayout.VERTICAL }
        h.title = c.label("", 15f, Color.WHITE).apply { maxLines = 2 }
        h.sub = c.label("", 12.5f, Palette.DIM).apply { maxLines = 2; setPadding(0, c.dp(4), 0, 0) }
        texts.addView(h.title)
        texts.addView(h.sub)
        info.addView(texts, LinearLayout.LayoutParams(0, wrap, 1f))
        card.addView(info, LinearLayout.LayoutParams(matchW, wrap))
        return h
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val item = data[position]
        Images.load(h.thumb, item.thumbnail)
        h.title.text = item.title
        if (music) {
            h.sub.text = listOf(item.channel, Fmt.duration(item.duration)).filter { it.isNotEmpty() }.joinToString(" · ")
        } else {
            h.sub.text = Fmt.subtitle(item)
            val d = Fmt.duration(item.duration)
            h.badge.text = d
            h.badge.visibility = if (d.isEmpty()) View.GONE else View.VISIBLE
            val avatar = h.avatar
            if (avatar != null) {
                avatar.bind(item.channel.ifEmpty { "?" })
                h.boundChannel = item.channelId
                // a lista már hozza a profilképet (nincs külön kérés) – ezért azonnal megjelenik
                if (item.avatar.isNotEmpty()) Images.load(avatar.image, item.avatar)
                else Channels.get(api, item.channelId) { info ->
                    if (h.boundChannel == item.channelId && info?.avatar != null) Images.load(avatar.image, info.avatar)
                }
            }
        }
        h.itemView.setOnClickListener {
            val pos = h.bindingAdapterPosition
            if (pos != RecyclerView.NO_POSITION) onClick(data[pos], pos)
        }
    }
}

/// Shorts: full-page, vertically paged videos.
class ShortsAdapter : RecyclerView.Adapter<ShortsAdapter.Holder>() {
    private var data: List<Item> = emptyList()

    class Holder(view: View, val thumb: ImageView, val slot: FrameLayout, val title: TextView, val channel: TextView) :
        RecyclerView.ViewHolder(view)

    fun submit(items: List<Item>) {
        data = items
        notifyDataSetChanged()
    }

    fun itemAt(position: Int): Item? = data.getOrNull(position)

    override fun getItemCount() = data.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val c = parent.context
        val page = FrameLayout(c).apply {
            setBackgroundColor(Color.BLACK)
            layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, parent.height)
        }
        val thumb = ImageView(c).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        val slot = FrameLayout(c)
        val box = LinearLayout(c).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(c.dp(14), c.dp(14), c.dp(14), c.dp(18))
        }
        val channel = c.label("", 14f, Color.WHITE, bold = true)
        val title = c.label("", 14f, Color.WHITE).apply { maxLines = 2; setPadding(0, c.dp(6), 0, 0) }
        box.addView(channel)
        box.addView(title)
        page.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.addView(slot, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        page.addView(
            box,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM)
        )
        return Holder(page, thumb, slot, title, channel)
    }

    override fun onBindViewHolder(h: Holder, position: Int) {
        val item = data[position]
        h.slot.removeAllViews()
        Images.load(h.thumb, item.thumbnail)
        h.title.text = item.title
        h.channel.text = if (item.channel.isEmpty()) "" else "@" + item.channel
    }
}

/// A single adapter that shows any view (the player page header sits on top of the list).
class HeaderAdapter(private val view: View) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
    override fun getItemCount() = 1
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        (view.parent as? ViewGroup)?.removeView(view)
        view.layoutParams = RecyclerView.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        return object : RecyclerView.ViewHolder(view) {}
    }
    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
}

/// The player panel: always exactly 16:9 in normal view (no leftover black band).
class PlayerPanel(context: android.content.Context) : android.widget.FrameLayout(context) {
    var fixedRatio = true
        set(value) { field = value; requestLayout() }

    /// Lehúzás (felülről lefelé húzás) a lejátszón: kicsinyítés lebegő ablakba.
    var onSwipeDown: (() -> Unit)? = null
    var swipeEnabled = false
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private val slop = android.view.ViewConfiguration.get(context).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!swipeEnabled) return false
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; swiping = false }
            android.view.MotionEvent.ACTION_MOVE -> {
                val dy = ev.y - downY
                if (dy > slop * 3 && dy > Math.abs(ev.x - downX) * 1.5f) { swiping = true; return true }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean {
        if (!swiping) return super.onTouchEvent(ev)
        if (ev.actionMasked == android.view.MotionEvent.ACTION_UP) {
            swiping = false
            if (ev.y - downY > resources.displayMetrics.density * 70) onSwipeDown?.invoke()
        } else if (ev.actionMasked == android.view.MotionEvent.ACTION_CANCEL) swiping = false
        return true
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (fixedRatio) {
            val w = MeasureSpec.getSize(widthMeasureSpec)
            super.onMeasure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((w * 9f / 16f).toInt(), MeasureSpec.EXACTLY),
            )
        } else super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
