package desu.inugram.ui.settings

import android.view.View
import androidx.core.content.edit
import desu.inugram.InuConfig
import desu.inugram.SearchRegistry
import desu.inugram.helpers.InuUtils
import org.telegram.messenger.LocaleController
import org.telegram.messenger.MessagesController
import org.telegram.messenger.NotificationCenter
import org.telegram.messenger.R
import org.telegram.messenger.SharedConfig
import org.telegram.ui.Cells.NotificationsCheckCell
import org.telegram.ui.Cells.TextCheckCell
import org.telegram.ui.Components.BulletinFactory
import org.telegram.ui.Components.UItem
import org.telegram.ui.Components.UniversalAdapter

class AnnoyancesSettingsActivity : SettingsPageActivity() {

    private val aiFeaturesGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuHideAiFeatures),
        listOf(
            ExpandableBoolGroup.Option(R.string.InuHideAiEditor, InuConfig.HIDE_AI_EDITOR),
            ExpandableBoolGroup.Option(R.string.InuHideMessageSummary, InuConfig.HIDE_MESSAGE_SUMMARY),
            ExpandableBoolGroup.Option(R.string.InuHideIvSummary, InuConfig.HIDE_IV_SUMMARY),
        ),
    )

    private val hideSuggestionsGroup = ExpandableBoolGroup(
        LocaleController.getString(R.string.InuHideSuggestions),
        listOf(
            ExpandableBoolGroup.Option(
                R.string.InuHideSuggestionBirthdaySetup,
                InuConfig.HIDE_SUGGESTION_BIRTHDAY_SETUP
            ),
            ExpandableBoolGroup.Option(
                R.string.InuHideSuggestionBirthdayContacts,
                InuConfig.HIDE_SUGGESTION_BIRTHDAY_CONTACTS
            ),
            ExpandableBoolGroup.Option(R.string.InuHideSuggestionPassword, InuConfig.HIDE_SUGGESTION_PASSWORD),
            ExpandableBoolGroup.Option(R.string.InuHideSuggestionPhone, InuConfig.HIDE_SUGGESTION_PHONE),
            ExpandableBoolGroup.Option(R.string.InuHideSuggestionPremium, InuConfig.HIDE_SUGGESTION_PREMIUM),
            ExpandableBoolGroup.Option(R.string.InuHideSuggestionCustom, InuConfig.HIDE_SUGGESTION_CUSTOM),
        ),
    )

    override fun getTitle(): CharSequence = LocaleController.getString(R.string.InuAnnoyances)

    override fun fillItems(items: ArrayList<UItem>, adapter: UniversalAdapter) {
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_HIDE_STORIES,
                R.string.InuHideStories,
                R.string.InuHideStoriesInfo,
                InuConfig.HIDE_STORIES.value
            )
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_REPOST_TO_STORY,
                LocaleController.getString(R.string.InuHideRepostToStory),
            ).setChecked(InuConfig.HIDE_REPOST_TO_STORY.value)
        )
        aiFeaturesGroup.addTo(items) { listView.adapter.update(true) }
        hideSuggestionsGroup.addTo(items) { listView.adapter.update(true) }
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_TRENDING_STICKERS,
                LocaleController.getString(R.string.InuHideTrendingStickers),
            ).setChecked(InuConfig.HIDE_TRENDING_STICKERS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_SENSITIVE,
                LocaleController.getString(R.string.InuDisableSensitive),
            ).setChecked(InuConfig.DISABLE_SENSITIVE.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_BACKGROUNDS,
                LocaleController.getString(R.string.InuDisableChatBackgrounds),
            ).setChecked(InuConfig.DISABLE_CHAT_BACKGROUNDS.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_CHAT_THEMES,
                LocaleController.getString(R.string.InuDisableChatThemes),
            ).setChecked(InuConfig.DISABLE_CHAT_THEMES.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_DISABLE_BG_PARALLAX,
                LocaleController.getString(R.string.InuDisableBgParallax),
            ).setChecked(InuConfig.DISABLE_BG_PARALLAX.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_PAID_REACTION_UPSELL,
                LocaleController.getString(R.string.InuHidePaidReactionUpsell),
            ).setChecked(InuConfig.HIDE_PAID_REACTION_UPSELL.value)
        )
        items.add(
            UItem.asCheck(
                TOGGLE_HIDE_HASHTAG_SUGGESTIONS,
                LocaleController.getString(R.string.InuHideHashtagSuggestions),
            ).setChecked(InuConfig.HIDE_HASHTAG_SUGGESTIONS.value)
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_MOTION_PHOTOS,
                R.string.InuDisableMotionPhotos,
                R.string.InuDisableMotionPhotosInfo,
                InuConfig.DISABLE_MOTION_PHOTOS.value
            )
        )
        items.add(
            mkTwoLineCheckItem(
                TOGGLE_DISABLE_INTRO_STICKER,
                R.string.InuDisableIntroSticker,
                R.string.InuDisableIntroStickerInfo,
                InuConfig.DISABLE_INTRO_STICKER.value
            )
        )
        items.add(UItem.asShadow(null))

        items.add(
            UItem.asButton(
                BUTTON_CLEAR_HINTS,
                LocaleController.getString(R.string.InuClearHints),
            )
        )
    }

    override fun onClick(item: UItem, view: View, position: Int, x: Float, y: Float) {
        if (aiFeaturesGroup.handleClick(item, view) { listView.adapter.update(true) }) return
        if (hideSuggestionsGroup.handleClick(item, view) { listView.adapter.update(true) }) return
        when (item.id) {
            TOGGLE_HIDE_STORIES -> {
                val new = InuConfig.HIDE_STORIES.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
                postNotificationForAllAccounts(NotificationCenter.storiesUpdated)
            }

            TOGGLE_HIDE_TRENDING_STICKERS -> {
                val new = InuConfig.HIDE_TRENDING_STICKERS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_REPOST_TO_STORY -> {
                val new = InuConfig.HIDE_REPOST_TO_STORY.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_SENSITIVE -> {
                val new = InuConfig.DISABLE_SENSITIVE.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_BACKGROUNDS -> {
                val new = InuConfig.DISABLE_CHAT_BACKGROUNDS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_CHAT_THEMES -> {
                val new = InuConfig.DISABLE_CHAT_THEMES.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_BG_PARALLAX -> {
                val new = InuConfig.DISABLE_BG_PARALLAX.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }


            TOGGLE_HIDE_PAID_REACTION_UPSELL -> {
                val new = InuConfig.HIDE_PAID_REACTION_UPSELL.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_HIDE_HASHTAG_SUGGESTIONS -> {
                val new = InuConfig.HIDE_HASHTAG_SUGGESTIONS.toggle()
                (view as? TextCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_MOTION_PHOTOS -> {
                val new = InuConfig.DISABLE_MOTION_PHOTOS.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            TOGGLE_DISABLE_INTRO_STICKER -> {
                val new = InuConfig.DISABLE_INTRO_STICKER.toggle()
                (view as? NotificationsCheckCell)?.isChecked = new
            }

            BUTTON_CLEAR_HINTS -> {
                // holy fucking shit how is it so inconsistent
                SharedConfig.dayNightWallpaperSwitchHint = 99
                SharedConfig.increaseTextSelectionHintShowed()
                SharedConfig.increaseDayNightWallpaperSiwtchHint()
                SharedConfig.increaseScheduledOrNoSoundHintShowed()
                SharedConfig.increaseScheduledHintShowed()
                SharedConfig.incrementCallEncryptionHintDisplayed(99)
                SharedConfig.forwardingOptionsHintHintShowed()
                SharedConfig.setStickersReorderingHintUsed(true)
                SharedConfig.removeTextSelectionHint()
                SharedConfig.replyingOptionsHintHintShowed()
                SharedConfig.removeScheduledOrNoSoundHint()
                SharedConfig.removeScheduledHint()
                SharedConfig.removeLockRecordAudioVideoHint()
                SharedConfig.setStoriesReactionsLongPressHintUsed(true)
                SharedConfig.setStoriesIntroShown(true)
                SharedConfig.updateMessageSeenHintCount(0)
                SharedConfig.updateEmojiInteractionsHintCount(0)
                SharedConfig.updateDayNightThemeSwitchHintCount(0)
                SharedConfig.updateStealthModeSendMessageConfirm(0)
                MessagesController.getGlobalMainSettings().edit {
                    putInt("channelsuggesthint2", 99)
                    putInt("hidecallshint", 99)
                    putInt("channelgifthint", 99)
                    putInt("channelsuggesthint", 99)
                    putInt("savedsearchhint", 99)
                    putInt("savedhint", 99)
                    putInt("voicepausehint", 99)
                    putInt("aihintshown", 99)
                    putInt("voiceoncehint", 99)
                    putInt("viewoncehint", 99)
                    putInt("accountswitchhint", 99)
                    putInt("multistorieshint", 99)
                    putInt("taptostoryhighlighthint", 99)
                    putInt("searchpostsnew", 99)
                    putInt("storydualhint", 99)
                    putInt("storysvddualhint", 99)
                    putInt("storyhint2", 99)
                    putInt("proximityhint", 99)
                    putInt("transcribeButtonPressed", 99)
                    putInt("taptostorysoundhint", 99)
                    putInt("showchattagsinfo", 0)
                    putInt("speedhint", -15)
                    putBoolean("monetizationadshint", true)
                    putBoolean("groupEmojiPackHintShown", true)
                    putBoolean("seekSpeedHintShowed", true)
                    putBoolean("storyprvhint", true)
                    putBoolean("gifhint", true)
                    putBoolean("archivehint_l", true)
                    putBoolean("reminderhint", true)
                    putBoolean("bganimationhint", true)
                    putBoolean("themehint", true)
                    putBoolean("filterhint", true)
                    putBoolean("bizbothint", true)
                    putBoolean("privacyAlertShowed", true)
                    putBoolean("archivehint", false)
                    putBoolean("storyhint", false)
                    putBoolean("trimvoicehint", false)
                }
                InuConfig.VOICE_HINT_SHOWN.value = true;
                BulletinFactory.of(this)
                    .createSimpleBulletin(
                        R.raw.chats_infotip,
                        LocaleController.getString(R.string.InuClearHintsDone)
                    )
                    .show()
            }
        }
    }

    companion object {
        private val TOGGLE_HIDE_STORIES = InuUtils.generateId()
        private val TOGGLE_HIDE_TRENDING_STICKERS = InuUtils.generateId()
        private val TOGGLE_HIDE_REPOST_TO_STORY = InuUtils.generateId()
        private val TOGGLE_DISABLE_SENSITIVE = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_BACKGROUNDS = InuUtils.generateId()
        private val TOGGLE_DISABLE_CHAT_THEMES = InuUtils.generateId()
        private val TOGGLE_DISABLE_BG_PARALLAX = InuUtils.generateId()
        private val TOGGLE_HIDE_PAID_REACTION_UPSELL = InuUtils.generateId()
        private val TOGGLE_HIDE_HASHTAG_SUGGESTIONS = InuUtils.generateId()
        private val TOGGLE_DISABLE_MOTION_PHOTOS = InuUtils.generateId()
        private val TOGGLE_DISABLE_INTRO_STICKER = InuUtils.generateId()
        private val BUTTON_CLEAR_HINTS = InuUtils.generateId()

        @JvmField val PAGE = SearchRegistry.Page(
            slug = "annoyances",
            titleRes = R.string.InuAnnoyances,
            iconRes = R.drawable.menu_hide_gift,
            factory = ::AnnoyancesSettingsActivity,
            entries = listOf(
                SearchRegistry.Entry("hide-stories", R.string.InuHideStories, TOGGLE_HIDE_STORIES),
                SearchRegistry.Entry("hide-repost-to-story", R.string.InuHideRepostToStory, TOGGLE_HIDE_REPOST_TO_STORY),
                SearchRegistry.Entry("hide-trending-stickers", R.string.InuHideTrendingStickers, TOGGLE_HIDE_TRENDING_STICKERS),
                SearchRegistry.Entry("disable-sensitive", R.string.InuDisableSensitive, TOGGLE_DISABLE_SENSITIVE),
                SearchRegistry.Entry("disable-chat-backgrounds", R.string.InuDisableChatBackgrounds, TOGGLE_DISABLE_CHAT_BACKGROUNDS),
                SearchRegistry.Entry("disable-chat-themes", R.string.InuDisableChatThemes, TOGGLE_DISABLE_CHAT_THEMES),
                SearchRegistry.Entry("disable-bg-parallax", R.string.InuDisableBgParallax, TOGGLE_DISABLE_BG_PARALLAX),
                SearchRegistry.Entry("hide-paid-reaction-upsell", R.string.InuHidePaidReactionUpsell, TOGGLE_HIDE_PAID_REACTION_UPSELL),
                SearchRegistry.Entry("hide-hashtag-suggestions", R.string.InuHideHashtagSuggestions, TOGGLE_HIDE_HASHTAG_SUGGESTIONS),
                SearchRegistry.Entry("disable-motion-photos", R.string.InuDisableMotionPhotos, TOGGLE_DISABLE_MOTION_PHOTOS),
                SearchRegistry.Entry("disable-intro-sticker", R.string.InuDisableIntroSticker, TOGGLE_DISABLE_INTRO_STICKER),
                SearchRegistry.Entry("clear-hints", R.string.InuClearHints, BUTTON_CLEAR_HINTS),
            ),
        )
    }
}
