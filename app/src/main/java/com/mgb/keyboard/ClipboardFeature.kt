package com.mgb.keyboard

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mgb.keyboard.clip.ClipList
import com.mgb.keyboard.typing.ClipSelect

/*
 * v0.0.6: clipboard history (+ pinned, delete, search). All new code is here; MgbIme.kt has small hooks marked "v0.0.6".
 * Data stays ONLY on the phone: prefs file "jabra", new key "clips_v1". No network, no new permission.
 * New prefs keys: clip_on(bool,true) = remember copied text, edit_bar(bool,true) = show the Select all/Cut/Copy/Paste bar.
 */

/** Saves/loads the history. Never throws. */
class ClipStore(private val prefs: JabraPrefs) {
    private val list: ClipList = try { ClipList.parse(prefs.str("clips_v1", "")) } catch (_: Throwable) { ClipList() }

    private fun save() { try { prefs.setStr("clips_v1", list.serialize()) } catch (_: Throwable) { } }

    fun add(t: String) { try { if (list.add(t)) save() } catch (_: Throwable) { } }
    fun view(q: String): List<ClipList.Item> = try { list.view(q) } catch (_: Throwable) { emptyList() }
    fun togglePin(i: ClipList.Item): Boolean = try { val ok = list.togglePin(i); if (ok) save(); ok } catch (_: Throwable) { false }
    fun remove(i: ClipList.Item) { try { if (list.remove(i)) save() } catch (_: Throwable) { } }
    fun clearUnpinned() { try { list.clearUnpinned(); save() } catch (_: Throwable) { } }

    // v0.2.5.1: engine wrappers (all safe)
    fun pinnedView(q: String): List<ClipList.Item> = try { list.viewPinned(q) } catch (_: Throwable) { emptyList() }
    fun recentView(q: String): List<ClipList.Item> = try { list.viewRecent(q) } catch (_: Throwable) { emptyList() }
    fun pinnedCount(): Int = try { list.pinnedCount() } catch (_: Throwable) { 0 }
    fun setPinned(sel: List<ClipList.Item>, pin: Boolean): Int = try { val n = list.setPinned(sel, pin); if (n > 0) save(); n } catch (_: Throwable) { 0 }
    fun removeAll(sel: List<ClipList.Item>): Int = try { val n = list.removeAll(sel); if (n > 0) save(); n } catch (_: Throwable) { 0 }
    /** Saves a text as a new clip. Returns ClipList.check(): 0 = saved, 1 = empty, 2 = too long. */
    fun addChecked(t: String): Int = try { val c = ClipList.check(t); if (c == 0) add(t); c } catch (_: Throwable) { 1 }
}

/** The clipboard screen: list (pinned first), tap = paste, star = pin, x = delete, search, clear (asks twice). */
class ClipPanel(private val ctx: Context, private val host: JabraHost, private val store: ClipStore, private val fieldText: () -> String = { "" }) {   // v0.2.5.1: fieldText = selection or whole field text

    private var searching = false
    private var query = ""
    private var confirmClear = false
    private var msg = ""   // v0.2.5.1: one short message for the header line (shown once, then cleared)
    private val sel = ClipSelect()        // v0.2.5.3: select mode state lives HERE (not in the view), so it survives refresh()
    private var confirmDel = false        // v0.2.5.3: "Sure? delete N" two-tap safety

    /** Call when the panel is opened from outside (fresh start). */
    fun reset() { searching = false; query = ""; confirmClear = false; msg = ""; sel.leave(); confirmDel = false }

    private fun dp(v: Int): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun btn(label: String, weight: Float, hDp: Int, onClick: (Button) -> Unit): Button {
        val b = Button(ctx)
        b.text = label
        b.isAllCaps = false
        b.minWidth = 0
        b.minimumWidth = 0
        b.setPadding(0, 0, 0, 0)
        b.setOnClickListener {
            try { host.feedback(b) } catch (_: Throwable) { }
            try { onClick(b) } catch (_: Throwable) { }
        }
        val lp = LinearLayout.LayoutParams(if (weight > 0f) 0 else LinearLayout.LayoutParams.WRAP_CONTENT, dp(hDp), if (weight > 0f) weight else 0f)
        lp.setMargins(dp(2), dp(2), dp(2), dp(2))
        b.layoutParams = lp
        return b
    }

    private fun row(): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        return r
    }

    private fun refresh() { host.showPanel("clipboard") }

    /** Returns null on any problem -> keyboard shows normal letters. */
    fun build(): View? = try { make() } catch (_: Throwable) { null }

    private fun make(): View {
        val root = LinearLayout(ctx)
        root.orientation = LinearLayout.VERTICAL
        root.setBackgroundColor(Color.parseColor("#DDDDDD"))
        root.setPadding(dp(2), dp(4), dp(2), dp(4))

        // v0.2.5.3: forget selected clips that no longer exist (after delete / Clear)
        try { sel.prune(store.pinnedView("") + store.recentView("")) } catch (_: Throwable) { sel.leave() }
        val selOn = sel.isOn

        // top bar
        val top = row()
        top.addView(btn("ABC", 1.0f, 40) { sel.leave(); confirmDel = false; host.showLetters() })   // v0.2.5.3: ABC leaves select mode
        val searchBtn = btn(if (searching) "✕ Search" else "🔍 Search", 1.6f, 40) {
            searching = !searching
            query = ""
            confirmClear = false
            refresh()
        }
        JabraIcons.put(ctx, searchBtn, if (searching) "close" else "search")   // v0.0.7
        top.addView(searchBtn)
        top.addView(btn("Add", 1.0f, 40) { addFromField() })   // v0.2.5.1: save the current field text as a clip
        val clearBtn = btn(if (confirmClear) "Sure? (unpinned)" else "Clear", 1.8f, 40) {
            if (confirmClear) { store.clearUnpinned(); confirmClear = false } else confirmClear = true
            refresh()
        }
        if (confirmClear) clearBtn.textSize = 12f   // v0.2.5.1: the button is a bit narrower now
        top.addView(clearBtn)
        root.addView(top)

        // v0.2.5.1: header line message (e.g. "Added", "Field is empty"); shown once
        // v0.2.5.2: the header line is now always there: left = message, right = [List|Cards] switch
        val shownMsg = msg
        msg = ""
        val cardsOn = try { JabraPrefs(ctx).int("clip_view", 0) == 1 } catch (_: Throwable) { false }
        val head = row()
        head.gravity = Gravity.CENTER_VERTICAL
        val m = TextView(ctx)
        m.text = if (selOn && shownMsg.isEmpty()) sel.count().toString() + " selected" else shownMsg   // v0.2.5.3
        m.textSize = 12f
        m.setTextColor(Color.DKGRAY)
        m.setPadding(dp(8), 0, dp(4), 0)
        m.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        head.addView(m)
        val viewBtn = btn(if (cardsOn) "View: Cards" else "View: List", 0f, 30) {
            try { JabraPrefs(ctx).setInt("clip_view", if (cardsOn) 0 else 1) } catch (_: Throwable) { }
            confirmClear = false
            refresh()
        }
        viewBtn.textSize = 12f
        viewBtn.setPadding(dp(10), 0, dp(10), 0)
        if (!selOn) {   // v0.2.5.3: in select mode the action row below replaces View / Manage
            head.addView(viewBtn)
            val manageBtn = btn("Manage", 0f, 30) { sel.enter(); confirmClear = false; confirmDel = false; refresh() }
            manageBtn.textSize = 12f
            manageBtn.setPadding(dp(10), 0, dp(10), 0)
            head.addView(manageBtn)
        }
        root.addView(head)
        if (selOn) root.addView(selectBar())

        if (searching) {
            val q = TextView(ctx)
            q.text = (if (JabraPrefs(ctx).bool("icons_on", true)) "" else "🔍 ") + query + "▏"   // v0.0.7: icon is on the button now
            q.textSize = 15f
            q.setTextColor(Color.BLACK)
            q.setPadding(dp(8), dp(2), dp(8), dp(2))
            root.addView(q)
        }

        // list
        val items = store.view(query)
        val listH = if (searching) 88 else 176
        val sv = ScrollView(ctx)
        sv.layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(listH))
        val col = LinearLayout(ctx)
        col.orientation = LinearLayout.VERTICAL
        if (items.isEmpty()) {
            val t = TextView(ctx)
            t.text = if (searching) "No match" else "Nothing copied yet. Copy some text and it will show here."
            t.setPadding(dp(8), dp(12), dp(8), dp(12))
            t.setTextColor(Color.DKGRAY)
            col.addView(t)
        }
        // v0.2.5.1: "Pinned" / "Recent" header lines (only when both groups have items and not searching)
        val pinnedItems = store.pinnedView(query)
        val recentItems = store.recentView(query)
        val showHeaders = !searching && pinnedItems.isNotEmpty() && recentItems.isNotEmpty()
        // v0.2.5.2: Cards view (clip_view = 1); any problem -> null -> the old List code below
        val lines = try { JabraPrefs(ctx).int("clip_lines", 0).coerceIn(0, 2) } catch (_: Throwable) { 0 }
        val cards: View? = if (cardsOn && items.isNotEmpty()) {
            ClipCards.build(ctx, host, pinnedItems, recentItems, !searching, lines,
                { it2 -> store.togglePin(it2); refresh() },
                { it2 -> store.remove(it2); refresh() },
                SelectMode(selOn, { x -> sel.isSelected(x) }, { x -> sel.toggle(x); confirmDel = false; refresh() }, { x -> sel.enterWith(x); confirmDel = false; refresh() }))
        } else null
        if (cards != null) {
            col.addView(cards)
        } else {
            if (showHeaders) col.addView(sectionHeader("Pinned"))
            for (entry in pinnedItems) col.addView(itemRow(entry))
            if (showHeaders) col.addView(sectionHeader("Recent"))
            for (entry in recentItems) col.addView(itemRow(entry))
        }
        sv.addView(col)
        root.addView(sv)

        // tiny search keyboard (Latin letters only)
        if (searching) {
            for (letters in arrayOf("qwertyuiop", "asdfghjkl", "zxcvbnm")) {
                val r = row()
                letters.forEach { ch -> r.addView(btn(ch.toString(), 1f, 34) { query += ch; confirmClear = false; refresh() }) }
                if (letters == "asdfghjkl") r.addView(JabraIcons.put(ctx, btn("␣", 1f, 34) { query += " "; refresh() }, "space"))   // v0.0.7
                if (letters == "zxcvbnm") r.addView(JabraIcons.put(ctx, btn("⌫", 1.5f, 34) { if (query.isNotEmpty()) query = query.dropLast(1); refresh() }, "back"))   // v0.0.7
                root.addView(r)
            }
        }
        return root
    }

    /** v0.2.5.1: tap on Add = save selected text (or the whole field) as a new clip. */
    private fun addFromField() {
        val t = try { fieldText() } catch (_: Throwable) { "" }
        when (store.addChecked(t)) {
            0 -> msg = "Added to Recent"
            2 -> msg = "Too long (max " + ClipList.MAX_LEN + ")"
            else -> msg = "Field is empty"
        }
        confirmClear = false
        refresh()
    }

    /** v0.2.5.3: action row in select mode: All | Pin | Unpin | Delete | Done. */
    private fun selectBar(): View {
        val r = row()
        r.addView(btn("All", 1f, 34) {
            sel.selectAll(store.pinnedView(query) + store.recentView(query))
            confirmDel = false
            refresh()
        })
        r.addView(btn("Pin", 1f, 34) {
            val chosen = sel.selection()
            val want = chosen.count { !it.pinned }
            if (chosen.isEmpty()) msg = "Nothing selected"
            else {
                val n = store.setPinned(chosen, true)
                msg = if (n < want) "Pinned " + n + " of " + want + " (limit " + ClipList.MAX_PINNED + ")" else "Pinned " + n
            }
            confirmDel = false
            refresh()
        })
        r.addView(btn("Unpin", 1f, 34) {
            val chosen = sel.selection()
            if (chosen.isEmpty()) msg = "Nothing selected" else msg = "Unpinned " + store.setPinned(chosen, false)
            confirmDel = false
            refresh()
        })
        val n = sel.count()
        val del = btn(if (confirmDel) "Sure? delete " + n else "Delete", if (confirmDel) 2.2f else 1.2f, 34) {
            if (n == 0) { msg = "Nothing selected"; confirmDel = false }
            else if (confirmDel) {
                val gone = store.removeAll(sel.selection())
                sel.clear()
                msg = "Deleted " + gone
                confirmDel = false
            } else confirmDel = true
            refresh()
        }
        if (confirmDel) del.textSize = 12f
        r.addView(del)
        // v0.3.6: Paste = type ALL selected clips in the order you tapped them, joined by a blank line, then back to letters
        r.addView(btn("Paste", 1.2f, 34) {
            val chosen = sel.selection()
            if (chosen.isEmpty()) { msg = "Nothing selected"; confirmDel = false; refresh() }
            else {
                val joined = chosen.joinToString("\n\n") { it.text }
                sel.leave(); confirmDel = false
                host.commitWord()
                host.typeText(joined)
                host.showLetters()
            }
        })
        r.addView(btn("Done", 1f, 34) { sel.leave(); confirmDel = false; refresh() })
        return r
    }

    private fun sectionHeader(label: String): View {
        val h = TextView(ctx)
        h.text = label
        h.textSize = 12f
        h.setTypeface(h.typeface, android.graphics.Typeface.BOLD)
        h.setTextColor(Color.DKGRAY)
        h.setPadding(dp(8), dp(4), dp(8), dp(2))
        return h
    }

    private fun itemRow(item: ClipList.Item): View {
        val lv = try { JabraPrefs(ctx).int("clip_lines", 0).coerceIn(0, 2) } catch (_: Throwable) { 0 }   // v0.2.5.1: 0/1/2 = 2/4/6 lines
        val rowH = 44 + 22 * lv
        val r = row()
        r.gravity = Gravity.CENTER_VERTICAL
        val selOn = sel.isOn
        val picked = selOn && sel.isSelected(item)
        val prefix = if (selOn) (if (picked) "☑ " else "☐ ") + (if (item.pinned) "★ " else "") else (if (item.pinned && !JabraPrefs(ctx).bool("icons_on", true)) "★ " else "")   // v0.2.5.3: check box + pinned tag in select mode
        val t = btn(prefix + item.text.replace("\n", " ⏎ "), 1f, rowH) {
            if (sel.isOn) {   // v0.2.5.3: tap toggles, NO paste
                sel.toggle(item); confirmDel = false; refresh()
            } else {
                host.commitWord()
                host.typeText(item.text)
                host.showLetters()
            }
        }
        if (picked) t.setBackgroundColor(Color.parseColor("#CFE0F5"))
        t.setOnLongClickListener {   // v0.2.5.3: long-press = select mode with this clip selected
            if (sel.isOn) false else { sel.enterWith(item); confirmDel = false; refresh(); true }
        }
        t.textSize = 13f
        t.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        t.maxLines = 2 + 2 * lv
        t.ellipsize = TextUtils.TruncateAt.END
        t.setPadding(dp(8), 0, dp(8), 0)
        r.addView(t)
        if (selOn) return r   // v0.2.5.3: no star / x while selecting
        r.addView(JabraIcons.put(ctx, btn(if (item.pinned) "★" else "☆", 0f, rowH) { store.togglePin(item); refresh() }.also { it.minimumWidth = dp(44); it.minWidth = dp(44) }, if (item.pinned) "pinned" else "pin"))   // v0.0.7
        r.addView(JabraIcons.put(ctx, btn("✕", 0f, rowH) { store.remove(item); refresh() }.also { it.minimumWidth = dp(44); it.minWidth = dp(44) }, "close"))   // v0.0.7
        return r
    }
}
