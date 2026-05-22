package desu.inugram.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.VectorAvatarThumbDrawable;
import org.telegram.ui.ProfileActivity;

/**
 * Old Layout (11.14.1) profile-header layout port. Replaces 12.x's needLayout
 * geometry with the pre-12.0 avatar/name/online positioning. Ported out of the
 * ProfileActivity patch so the patch stays thin (field exposures + one hook);
 * the canonical 11.14 layout math lives here.
 *
 * Pins avatarContainer to 42dp on every call (12.x's setAvatarExpandProgress
 * otherwise stomps it back to 100x100), then runs the 11.14 math.
 */
public final class OldProfileHelper {

    private OldProfileHelper() {}

    public static void needLayout(ProfileActivity p, boolean animated) {
        final ActionBar actionBar = p.getActionBar();
        final int newTop = (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0) + ActionBar.getCurrentActionBarHeight();

        FrameLayout.LayoutParams layoutParams;
        if (p.listView != null && !p.openAnimationInProgress) {
            layoutParams = (FrameLayout.LayoutParams) p.listView.getLayoutParams();
            if (layoutParams.topMargin != newTop) {
                layoutParams.topMargin = newTop;
                p.listView.setLayoutParams(layoutParams);
            }
        }

        if (p.avatarContainer != null) {
            // Always force avatar layout params to 11.14 dimensions; 12.x's
            // setAvatarExpandProgress otherwise stomps these back to 100x100.
            final FrameLayout.LayoutParams alp = (FrameLayout.LayoutParams) p.avatarContainer.getLayoutParams();
            final int target42 = AndroidUtilities.dp(42);
            final int target64 = AndroidUtilities.dp(64);
            if (alp.width != target42 || alp.height != target42 || alp.leftMargin != target64) {
                alp.width = target42;
                alp.height = target42;
                alp.leftMargin = target64;
                p.avatarContainer.requestLayout();
            }

            final float diff = Math.min(1f, p.extraHeight / AndroidUtilities.dp(88f));

            p.listView.setTopGlowOffset((int) p.extraHeight);
            p.listView.setOverScrollMode(p.extraHeight > AndroidUtilities.dp(88f) && p.extraHeight < p.listView.getMeasuredWidth() - newTop ? View.OVER_SCROLL_NEVER : View.OVER_SCROLL_ALWAYS);

            if (p.writeButton != null) {
                p.writeButton.setTranslationY((actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0) + ActionBar.getCurrentActionBarHeight() + p.extraHeight + p.searchTransitionOffset - AndroidUtilities.dp(29.5f));

                boolean writeButtonVisible = diff > 0.2f && !p.searchMode && (p.imageUpdater == null || p.setAvatarRow == -1);
                if (writeButtonVisible && p.chatId != 0) {
                    writeButtonVisible = ChatObject.isChannel(p.currentChat) && !p.currentChat.megagroup && p.chatInfo != null && p.chatInfo.linked_chat_id != 0 && (p.infoHeaderRow != -1 || p.infoHeaderRowEmpty != -1);
                }
                if (!p.openAnimationInProgress) {
                    boolean currentVisible = p.writeButton.getTag() == null;
                    if (writeButtonVisible != currentVisible) {
                        if (writeButtonVisible) {
                            p.writeButton.setTag(null);
                        } else {
                            p.writeButton.setTag(0);
                        }
                        if (p.writeButtonAnimation != null) {
                            AnimatorSet old = p.writeButtonAnimation;
                            p.writeButtonAnimation = null;
                            old.cancel();
                        }
                        if (animated) {
                            p.writeButtonAnimation = new AnimatorSet();
                            if (writeButtonVisible) {
                                p.writeButtonAnimation.setInterpolator(new DecelerateInterpolator());
                                p.writeButtonAnimation.playTogether(
                                        ObjectAnimator.ofFloat(p.writeButton, View.SCALE_X, 1.0f),
                                        ObjectAnimator.ofFloat(p.writeButton, View.SCALE_Y, 1.0f),
                                        ObjectAnimator.ofFloat(p.writeButton, View.ALPHA, 1.0f)
                                );
                            } else {
                                p.writeButtonAnimation.setInterpolator(new AccelerateInterpolator());
                                p.writeButtonAnimation.playTogether(
                                        ObjectAnimator.ofFloat(p.writeButton, View.SCALE_X, 0.2f),
                                        ObjectAnimator.ofFloat(p.writeButton, View.SCALE_Y, 0.2f),
                                        ObjectAnimator.ofFloat(p.writeButton, View.ALPHA, 0.0f)
                                );
                            }
                            p.writeButtonAnimation.setDuration(150);
                            p.writeButtonAnimation.addListener(new AnimatorListenerAdapter() {
                                @Override
                                public void onAnimationEnd(Animator animation) {
                                    if (p.writeButtonAnimation != null && p.writeButtonAnimation.equals(animation)) {
                                        p.writeButtonAnimation = null;
                                    }
                                }
                            });
                            p.writeButtonAnimation.start();
                        } else {
                            p.writeButton.setScaleX(writeButtonVisible ? 1.0f : 0.2f);
                            p.writeButton.setScaleY(writeButtonVisible ? 1.0f : 0.2f);
                            p.writeButton.setAlpha(writeButtonVisible ? 1.0f : 0.0f);
                        }
                    }
                }

                // 12.x: storyView keeps the (width, writeBtnVisible, y) signature;
                // giftsView only takes the y offset.
                if (p.storyView != null) {
                    p.storyView.setExpandCoords(p.avatarContainer2.getMeasuredWidth() - AndroidUtilities.dp(40), writeButtonVisible, (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0) + ActionBar.getCurrentActionBarHeight() + p.extraHeight + p.searchTransitionOffset);
                }
                if (p.giftsView != null) {
                    p.giftsView.setExpandCoords((actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0) + ActionBar.getCurrentActionBarHeight() + p.extraHeight + p.searchTransitionOffset);
                }
            }

            p.avatarX = -AndroidUtilities.dpf2(47f) * diff;
            p.avatarY = (actionBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0) + ActionBar.getCurrentActionBarHeight() / 2.0f * (1.0f + diff) - 21 * AndroidUtilities.density + 27 * AndroidUtilities.density * diff + actionBar.getTranslationY();

            float h = p.openAnimationInProgress ? p.initialAnimationExtraHeight : p.extraHeight;
            if (h > AndroidUtilities.dp(88f) || p.isPulledDown) {
                p.expandProgress = Math.max(0f, Math.min(1f, (h - AndroidUtilities.dp(88f)) / (p.listView.getMeasuredWidth() - newTop - AndroidUtilities.dp(88f))));
                p.avatarScale = AndroidUtilities.lerp((42f + 18f) / 42f, (42f + 42f + 18f) / 42f, Math.min(1f, p.expandProgress * 3f));
                if (p.storyView != null) p.storyView.invalidate();
                if (p.giftsView != null) p.giftsView.invalidate();

                final float durationFactor = Math.min(AndroidUtilities.dpf2(2000f), Math.max(AndroidUtilities.dpf2(1100f), Math.abs(p.listViewVelocityY))) / AndroidUtilities.dpf2(1100f);

                if (p.allowPullingDown && (p.openingAvatar || p.expandProgress >= 0.33f)) {
                    if (!p.isPulledDown) {
                        if (p.otherItem != null) {
                            if (!p.isPeerNoForwards()) {
                                p.otherItem.showSubItem(ProfileActivity.gallery_menu_save);
                            } else {
                                p.otherItem.hideSubItem(ProfileActivity.gallery_menu_save);
                            }
                            if (p.imageUpdater != null) {
                                p.otherItem.showSubItem(ProfileActivity.add_photo);
                                p.otherItem.showSubItem(ProfileActivity.edit_avatar);
                                p.otherItem.showSubItem(ProfileActivity.delete_avatar);
                                p.otherItem.hideSubItem(ProfileActivity.set_as_main);
                                p.otherItem.hideSubItem(ProfileActivity.logout);
                            }
                        }
                        if (p.searchItem != null) p.searchItem.setEnabled(false);
                        p.isPulledDown = true;
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needCheckSystemBarColors, true);
                        p.overlaysView.setOverlaysVisible(true, durationFactor);
                        p.avatarsViewPagerIndicatorView.refreshVisibility(durationFactor);
                        p.avatarsViewPager.setCreateThumbFromParent(true);
                        p.avatarsViewPager.getAdapter().notifyDataSetChanged();
                        p.expandAnimator.cancel();
                        float value = AndroidUtilities.lerp(p.expandAnimatorValues, p.currentExpanAnimatorFracture);
                        p.expandAnimatorValues[0] = value;
                        p.expandAnimatorValues[1] = 1f;
                        if (p.storyView != null && !p.storyView.isEmpty()) {
                            p.expandAnimator.setInterpolator(new FastOutSlowInInterpolator());
                            p.expandAnimator.setDuration((long) ((1f - value) * 1.3f * 250f / durationFactor));
                        } else {
                            p.expandAnimator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                            p.expandAnimator.setDuration((long) ((1f - value) * 250f / durationFactor));
                        }
                        p.expandAnimator.addListener(new AnimatorListenerAdapter() {
                            @Override
                            public void onAnimationStart(Animator animation) {
                                p.setForegroundImage(false);
                                p.avatarsViewPager.setAnimatedFileMaybe(p.avatarImage.getImageReceiver().getAnimation());
                                p.avatarsViewPager.resetCurrentItem();
                            }

                            @Override
                            public void onAnimationEnd(Animator animation) {
                                p.expandAnimator.removeListener(this);
                                p.topView.setBackgroundColor(Color.BLACK);
                                p.avatarContainer.setVisibility(View.GONE);
                                p.avatarsViewPager.setVisibility(View.VISIBLE);
                            }
                        });
                        p.expandAnimator.start();
                    }
                    ViewGroup.LayoutParams params = p.avatarsViewPager.getLayoutParams();
                    params.width = p.listView.getMeasuredWidth();
                    params.height = (int) (h + newTop);
                    p.avatarsViewPager.requestLayout();
                    if (!p.expandAnimator.isRunning()) {
                        float additionalTranslationY = 0;
                        if (p.openAnimationInProgress && p.playProfileAnimation == 2) {
                            additionalTranslationY = -(1.0f - p.avatarAnimationProgress) * AndroidUtilities.dp(50);
                        }
                        p.onlineX = AndroidUtilities.dpf2(16f) - p.onlineTextView[1].getLeft();
                        p.nameTextView[1].setTranslationX(AndroidUtilities.dpf2(18f) - p.nameTextView[1].getLeft());
                        p.nameTextView[1].setTranslationY(newTop + h - AndroidUtilities.dpf2(38f) - p.nameTextView[1].getBottom() + additionalTranslationY);
                        p.onlineTextView[1].setTranslationX(p.getOnlineTextViewTranslationXWithOffsets(p.onlineX));
                        p.onlineTextView[1].setTranslationY(p.getOnlineTextViewTranslationYWithOffsets(newTop + h - AndroidUtilities.dpf2(18f) - p.onlineTextView[1].getBottom() + additionalTranslationY));
                        p.mediaCounterTextView.setTranslationX(p.onlineTextView[1].getTranslationX());
                        p.mediaCounterTextView.setTranslationY(p.onlineTextView[1].getTranslationY());
                        p.updateCollectibleHint();
                    }
                } else {
                    if (p.isPulledDown) {
                        p.isPulledDown = false;
                        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.needCheckSystemBarColors, true);
                        if (p.otherItem != null) {
                            p.otherItem.hideSubItem(ProfileActivity.gallery_menu_save);
                            if (p.imageUpdater != null) {
                                p.otherItem.hideSubItem(ProfileActivity.set_as_main);
                                p.otherItem.hideSubItem(ProfileActivity.edit_avatar);
                                p.otherItem.hideSubItem(ProfileActivity.delete_avatar);
                                p.otherItem.showSubItem(ProfileActivity.add_photo);
                                p.otherItem.showSubItem(ProfileActivity.logout);
                            }
                        }
                        if (p.searchItem != null) p.searchItem.setEnabled(!p.scrolling);
                        p.overlaysView.setOverlaysVisible(false, durationFactor);
                        p.avatarsViewPagerIndicatorView.refreshVisibility(durationFactor);
                        p.expandAnimator.cancel();
                        p.avatarImage.getImageReceiver().setAllowStartAnimation(true);
                        p.avatarImage.getImageReceiver().startAnimation();

                        float value = AndroidUtilities.lerp(p.expandAnimatorValues, p.currentExpanAnimatorFracture);
                        p.expandAnimatorValues[0] = value;
                        p.expandAnimatorValues[1] = 0f;
                        p.expandAnimator.setInterpolator(CubicBezierInterpolator.EASE_BOTH);
                        if (!p.isInLandscapeMode) {
                            p.expandAnimator.setDuration((long) (value * 250f / durationFactor));
                        } else {
                            p.expandAnimator.setDuration(0);
                        }
                        p.topView.setBackgroundColor(p.getThemedColor(Theme.key_avatar_backgroundActionBarBlue));

                        if (!p.doNotSetForeground) {
                            BackupImageView imageView = p.avatarsViewPager.getCurrentItemView();
                            if (imageView != null) {
                                if (imageView.getImageReceiver().getDrawable() instanceof VectorAvatarThumbDrawable) {
                                    p.avatarImage.drawForeground(false);
                                } else {
                                    p.avatarImage.drawForeground(true);
                                    p.avatarImage.setForegroundImageDrawable(imageView.getImageReceiver().getDrawableSafe());
                                }
                            }
                        }
                        p.avatarImage.setForegroundAlpha(1f);
                        p.avatarContainer.setVisibility(View.VISIBLE);
                        p.avatarsViewPager.setVisibility(View.GONE);
                        p.expandAnimator.start();
                    }

                    p.avatarContainer.setScaleX(p.avatarScale);
                    p.avatarContainer.setScaleY(p.avatarScale);

                    if (p.expandAnimator == null || !p.expandAnimator.isRunning()) {
                        // 11.14: refreshNameAndOnlineXY
                        p.nameX = AndroidUtilities.dp(-21f) + p.avatarContainer.getMeasuredWidth() * (p.avatarScale - (42f + 18f) / 42f);
                        p.nameY = (float) Math.floor(p.avatarY) + AndroidUtilities.dp(1.3f) + AndroidUtilities.dp(7f) + p.avatarContainer.getMeasuredHeight() * (p.avatarScale - (42f + 18f) / 42f) / 2f;
                        p.onlineX = AndroidUtilities.dp(-21f) + p.avatarContainer.getMeasuredWidth() * (p.avatarScale - (42f + 18f) / 42f);
                        p.onlineY = (float) Math.floor(p.avatarY) + AndroidUtilities.dp(24) + (float) Math.floor(11 * AndroidUtilities.density) + p.avatarContainer.getMeasuredHeight() * (p.avatarScale - (42f + 18f) / 42f) / 2f;
                        p.nameTextView[1].setTranslationX(p.nameX);
                        p.nameTextView[1].setTranslationY(p.nameY);
                        p.onlineTextView[1].setTranslationX(p.getOnlineTextViewTranslationXWithOffsets(p.onlineX));
                        p.onlineTextView[1].setTranslationY(p.getOnlineTextViewTranslationYWithOffsets(p.onlineY));
                        p.mediaCounterTextView.setTranslationX(p.onlineX);
                        p.mediaCounterTextView.setTranslationY(p.onlineY);
                        p.updateCollectibleHint();
                    }
                }
            } else if (p.extraHeight <= AndroidUtilities.dp(88f)) {
                p.avatarScale = (42 + 18 * diff) / 42.0f;
                if (p.storyView != null) p.storyView.invalidate();
                if (p.giftsView != null) p.giftsView.invalidate();
                float nameScale = 1.0f + 0.12f * diff;
                if (p.expandAnimator == null || !p.expandAnimator.isRunning()) {
                    p.avatarContainer.setScaleX(p.avatarScale);
                    p.avatarContainer.setScaleY(p.avatarScale);
                    p.avatarContainer.setTranslationX(p.avatarX);
                    p.avatarContainer.setTranslationY((float) Math.ceil(p.avatarY));
                    float extra = AndroidUtilities.dp(42) * p.avatarScale - AndroidUtilities.dp(42);
                    p.timeItem.setTranslationX(p.avatarContainer.getX() + AndroidUtilities.dp(16) + extra);
                    p.timeItem.setTranslationY(p.avatarContainer.getY() + AndroidUtilities.dp(15) + extra);
                    p.starBgItem.setTranslationX(p.avatarContainer.getX() + AndroidUtilities.dp(28) + extra);
                    p.starBgItem.setTranslationY(p.avatarContainer.getY() + AndroidUtilities.dp(24) + extra);
                    p.starFgItem.setTranslationX(p.avatarContainer.getX() + AndroidUtilities.dp(28) + extra);
                    p.starFgItem.setTranslationY(p.avatarContainer.getY() + AndroidUtilities.dp(24) + extra);
                }
                p.nameX = -21 * AndroidUtilities.density * diff;
                p.nameY = (float) Math.floor(p.avatarY) + AndroidUtilities.dp(1.3f) + AndroidUtilities.dp(7) * diff + p.titleAnimationsYDiff * (1f - p.avatarAnimationProgress);
                p.onlineX = -21 * AndroidUtilities.density * diff;
                p.onlineY = (float) Math.floor(p.avatarY) + AndroidUtilities.dp(24) + (float) Math.floor(11 * AndroidUtilities.density) * diff;
                if (p.showStatusButton != null) {
                    p.showStatusButton.setAlpha((int) (0xFF * diff));
                }
                for (int a = 0; a < p.nameTextView.length; a++) {
                    if (p.nameTextView[a] == null) continue;
                    if (p.expandAnimator == null || !p.expandAnimator.isRunning()) {
                        p.nameTextView[a].setTranslationX(p.nameX);
                        p.nameTextView[a].setTranslationY(p.nameY);
                        p.onlineTextView[a].setTranslationX(p.getOnlineTextViewTranslationXWithOffsets(p.onlineX));
                        p.onlineTextView[a].setTranslationY(p.getOnlineTextViewTranslationYWithOffsets(p.onlineY));
                        if (a == 1) {
                            p.mediaCounterTextView.setTranslationX(p.onlineX);
                            p.mediaCounterTextView.setTranslationY(p.onlineY);
                        }
                    }
                    p.nameTextView[a].setScaleX(nameScale);
                    p.nameTextView[a].setScaleY(nameScale);
                }
                p.updateCollectibleHint();
            }

            if (!p.openAnimationInProgress && (p.expandAnimator == null || !p.expandAnimator.isRunning())) {
                p.needLayoutText(diff);
            }
        }

        // 12.x extras the original needLayout calls at the end.
        p.updateExtraViews(newTop);
        if (p.avatarContainer != null) {
            final float diff = Math.min(1f, p.extraHeight / AndroidUtilities.dp(88f));
            if (diff >= 1f) p.avatarImage.setAlpha(1f);
        }
    }
}
