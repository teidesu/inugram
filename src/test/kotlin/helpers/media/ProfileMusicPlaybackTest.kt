package desu.inugram.helpers.media

import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import androidx.test.platform.app.InstrumentationRegistry
import desu.inugram.InuConfig
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.telegram.messenger.MediaController
import org.telegram.messenger.MessageObject
import org.telegram.messenger.MessagesController.SavedMusicList
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.SharedConfig
import org.telegram.messenger.UserConfig
import org.telegram.tgnet.ConnectionsManager
import org.telegram.tgnet.TLRPC
import org.telegram.ui.Cells.AudioPlayerCell
import org.telegram.ui.Components.AudioPlayerAlert
import org.telegram.ui.Components.AudioPlayerAlert.ClippingTextViewSwitcher
import org.telegram.ui.Components.RecyclerListView
import org.telegram.ui.Components.SeekBarView
import org.telegram.ui.LaunchActivity
import org.telegram.ui.ProfileActivity

class ProfileMusicPlaybackTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var activity: LaunchActivity
    private lateinit var audioFile: File
    private lateinit var media: MediaController
    private var originalAutoplayDisabled = false
    private var originalPlaybackOrder = 0
    private val openedSheets = mutableListOf<AudioPlayerAlert>()

    @Before
    fun set_up() {
        val context = instrumentation.targetContext
        audioFile = File.createTempFile("profile_music", ".wav", context.cacheDir)
        val sampleRate = 8_000
        val dataSize = sampleRate * 2 * 30
        audioFile.writeBytes(
            ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
                .put("RIFF".toByteArray()).putInt(36 + dataSize).put("WAVEfmt ".toByteArray())
                .putInt(16).putShort(1).putShort(1).putInt(sampleRate).putInt(sampleRate * 2)
                .putShort(2).putShort(16).put("data".toByteArray()).putInt(dataSize).array()
        )
        // Start the test APK in the foreground even on devices that restrict background launches.
        val launch = instrumentation.uiAutomation.executeShellCommand(
            "am start -W -n ${context.packageName}/${LaunchActivity::class.java.name}"
        )
        val launchResult = ParcelFileDescriptor.AutoCloseInputStream(launch).bufferedReader().use { it.readText() }
        onMain {
            activity = assertNotNull(LaunchActivity.instance?.takeUnless { it.isFinishing || it.isDestroyed }, launchResult)
            for (account in 0 until UserConfig.MAX_ACCOUNT_COUNT) {
                ConnectionsManager.native_setNetworkAvailable(account, false, 0, false)
                ConnectionsManager.native_pauseNetwork(account)
            }
            media = MediaController.getInstance()
            originalAutoplayDisabled = InuConfig.DISABLE_PROFILE_MUSIC_AUTOPLAY.value
            originalPlaybackOrder = when {
                SharedConfig.shuffleMusic -> 2
                SharedConfig.playOrderReversed -> 1
                else -> 0
            }
            InuConfig.DISABLE_PROFILE_MUSIC_AUTOPLAY.value = true
            media.cleanup()
            media.setPlaybackOrderType(0)
        }
    }

    @After
    fun tear_down() {
        if (::activity.isInitialized && ::media.isInitialized) {
            onMain {
                openedSheets.filter { !it.isDismissed }.forEach { it.dismiss() }
                media.cleanup()
                media.setPlaybackOrderType(originalPlaybackOrder)
                InuConfig.DISABLE_PROFILE_MUSIC_AUTOPLAY.value = originalAutoplayDisabled
                activity.finish()
            }
        }
        if (::audioFile.isInitialized) audioFile.delete()
    }

    @Test
    fun browsing_another_profile_keeps_playing_and_controls_the_original_queue() {
        lateinit var first: SavedMusicList
        lateinit var second: SavedMusicList
        lateinit var sheet: AudioPlayerAlert
        onMain {
            first = playlist(710_001, "Original", "Original next")
            second = playlist(710_002, "Browsed")
            openProfile(first).control("playButton").performClick()
            assertSame(first.list[0], media.playingMessageObject)
            openedSheets.last().dismiss()
        }
        awaitPosition(first.list[0], 100)
        var positionBeforeBrowsing = 0L
        onMain {
            positionBeforeBrowsing = media.getProgressMs(first.list[0])
            sheet = openProfile(second)
            assertEquals("Original", sheet.headerTitle())
            assertGlobalQueue(first, 0)
            assertTrue(media.getProgressMs(first.list[0]) >= positionBeforeBrowsing)
            assertFalse(media.isMessagePaused)
            assertEquals(1, sheet.rows().adapter!!.itemCount)
            assertSame(second.list[0], sheet.row(0).messageObject)
        }
        awaitPosition(first.list[0], positionBeforeBrowsing + 250)
        onMain {
            sheet.control("playButton").performClick()
            assertTrue(media.isMessagePaused)
            assertGlobalQueue(first, 0)
        }
        val seekBar = sheet.field<SeekBarView>("seekBarView")
        val layoutDeadline = SystemClock.elapsedRealtime() + 5_000
        var seekBarReady = false
        while (!seekBarReady && SystemClock.elapsedRealtime() < layoutDeadline) {
            onMain {
                if (seekBar.isShown && seekBar.width > 0) {
                    assertGlobalQueue(first, 0)
                    seekBar.tap(0.5f)
                    seekBarReady = true
                }
            }
            if (!seekBarReady) SystemClock.sleep(25)
        }
        assertTrue(seekBarReady, "The seek bar was not laid out")
        awaitPosition(first.list[0], 13_000)
        onMain {
            assertTrue(media.isMessagePaused)
            sheet.control("playButton").performClick()
            assertFalse(media.isMessagePaused)
            sheet.control("nextButton").tap()
            assertGlobalQueue(first, 1)
        }
        awaitHeaderTitle(sheet, "Original next")
        onMain {
            sheet.control("prevButton").tap()
            assertGlobalQueue(first, 0)
        }
        awaitHeaderTitle(sheet, "Original")
        onMain {
            sheet.dismiss()
            assertGlobalQueue(first, 0)
            assertFalse(media.isMessagePaused)
        }
    }

    @Test
    fun choosing_a_browsed_track_switches_the_queue_to_its_profile() = onMain {
        val first = playlist(710_003, "First", "First next")
        val second = playlist(710_004, "Second", "Selected", "Selected next")
        assertTrue(media.inu_playSavedMusic(first, first.list[1]))
        assertTrue(media.pauseMessage(first.list[1]))
        val sheet = openProfile(second)
        assertEquals("First next", sheet.headerTitle())
        assertTrue(media.isMessagePaused)
        assertGlobalQueue(first, 1)

        sheet.row(1).didPressedButton()
        assertGlobalQueue(second, 1)
        assertEquals("Selected", sheet.headerTitle())
        assertFalse(media.isMessagePaused)
        sheet.control("nextButton").tap()
        assertGlobalQueue(second, 2)
    }

    @Test
    fun browsing_and_closing_an_idle_profile_leaves_the_global_player_idle() = onMain {
        val saved = playlist(710_005, "Preview", "Preview next")
        val sheet = openProfile(saved)
        assertEquals("Preview", sheet.headerTitle())
        assertFalse(sheet.control("seekBarView").isEnabled)
        assertNull(media.playingMessageObject)
        assertNull(media.musicList)
        assertTrue(media.playlist.isEmpty())
        sheet.dismiss()
        assertNull(media.playingMessageObject)
        assertNull(media.musicList)
        assertTrue(media.playlist.isEmpty())
    }

    @Test
    fun pagination_and_dismissal_do_not_replace_a_chat_queue() = onMain {
        val chat = playlist(710_006, "Chat song", "Chat next").list
        assertTrue(media.setPlaylist(chat, chat.first(), 0L, false, null))
        val originalQueue = media.playlist.toList()
        val originalIndex = media.playingMessageObjectNum
        val browsed = playlist(710_007, "Profile song")
        val sheet = openProfile(browsed)
        val newTrack = playlist(browsed.dialogId, "Loaded later").list.single()
        browsed.list.add(newTrack)
        NotificationCenter.getInstance(browsed.currentAccount)
            .postNotificationName(NotificationCenter.musicListLoaded, browsed)
        assertEquals(2, sheet.rows().adapter!!.itemCount)
        assertEquals("Chat song", sheet.headerTitle())
        sheet.dismiss()
        assertSame(chat.first(), media.playingMessageObject)
        assertNull(media.musicList)
        assertEquals(originalQueue, media.playlist)
        assertEquals(originalIndex, media.playingMessageObjectNum)
        assertFalse(media.isMessagePaused)
    }

    @Test
    fun opening_another_profile_preserves_the_active_shuffle_order() = onMain {
        val first = playlist(710_008, "One", "Two", "Three")
        val nextIndex = 1
        media.setPlaybackOrderType(2)
        assertTrue(media.inu_playSavedMusic(first, first.list[nextIndex]))
        val originalIndex = media.playingMessageObjectNum
        val sheet = openProfile(playlist(710_009, "Other"))
        assertEquals("Two", sheet.headerTitle())
        assertEquals(originalIndex, media.playingMessageObjectNum)
        assertSame(first.list[nextIndex], media.playingMessageObject)
        sheet.control("nextButton").tap()
        val next = media.playingMessageObject
        assertTrue(next in first.list && next !== first.list[nextIndex])
        assertSame(first, media.musicList)
        assertEquals(first.list, media.playlist)
    }

    private fun assertGlobalQueue(list: SavedMusicList, position: Int) {
        assertSame(list, media.musicList)
        assertEquals(list.list, media.playlist)
        assertEquals(position, media.playingMessageObjectNum)
        assertSame(list.list[position], media.playingMessageObject)
    }

    private fun openProfile(list: SavedMusicList): AudioPlayerAlert {
        val profile = ProfileActivity(Bundle().apply { putLong("user_id", list.dialogId) })
        profile.setParentLayout(activity.actionBarLayout)
        ProfileActivity::class.java.getDeclaredField("savedMusicList").apply {
            isAccessible = true
            set(profile, list)
        }
        ProfileActivity::class.java.getDeclaredMethod("inu_openProfileMusic").apply {
            isAccessible = true
            invoke(profile)
        }
        return (profile.visibleDialog as AudioPlayerAlert).also(openedSheets::add)
    }

    private fun playlist(owner: Long, vararg titles: String): SavedMusicList =
        SavedMusicList(UserConfig.selectedAccount, owner).apply {
            endReached = true
            titles.forEachIndexed { index, title ->
                val document = TLRPC.TL_document().apply {
                    id = owner * 10 + index
                    dc_id = 1
                    mime_type = "audio/wav"
                    size = audioFile.length()
                    attributes.add(TLRPC.TL_documentAttributeAudio().apply {
                        this.title = title
                        performer = "Test artist"
                        duration = 30.0
                        flags = 3
                    })
                }
                list.add(toMessageObject(document).apply {
                    messageOwner.attachPath = audioFile.absolutePath
                    attachPathExists = true
                    mediaExists = true
                })
            }
        }

    private fun awaitHeaderTitle(sheet: AudioPlayerAlert, expected: String) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var title = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain { title = sheet.headerTitle() }
            if (title == expected) return
            SystemClock.sleep(25)
        }
        assertEquals(expected, title)
    }

    private fun awaitPosition(track: MessageObject, minimum: Long) {
        val deadline = SystemClock.elapsedRealtime() + 5_000
        var position = -1L
        while (SystemClock.elapsedRealtime() < deadline) {
            onMain { position = media.getProgressMs(track) }
            if (position >= minimum) return
            SystemClock.sleep(25)
        }
        assertTrue(position >= minimum, "Playback position was $position ms, expected at least $minimum ms")
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    private fun AudioPlayerAlert.headerTitle() = field<ClippingTextViewSwitcher>("titleTextView").textView.text.toString()

    private fun AudioPlayerAlert.rows() = field<RecyclerListView>("listView")

    private fun AudioPlayerAlert.row(position: Int): AudioPlayerCell {
        val rows = rows()
        @Suppress("UNCHECKED_CAST")
        val adapter = rows.adapter as RecyclerView.Adapter<RecyclerView.ViewHolder>
        val holder = adapter.createViewHolder(rows, adapter.getItemViewType(position))
        adapter.bindViewHolder(holder, position)
        return holder.itemView as AudioPlayerCell
    }

    private fun AudioPlayerAlert.control(name: String) = field<View>(name)

    private fun <T> AudioPlayerAlert.field(name: String): T {
        @Suppress("UNCHECKED_CAST")
        return AudioPlayerAlert::class.java.getDeclaredField(name).let {
            it.isAccessible = true
            it.get(this) as T
        }
    }

    private fun View.tap(xFraction: Float = 0.5f) {
        val now = SystemClock.uptimeMillis()
        for (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP)) {
            val event = MotionEvent.obtain(now, now, action, width * xFraction, height * 0.5f, 0)
            dispatchTouchEvent(event)
            event.recycle()
        }
    }
}
