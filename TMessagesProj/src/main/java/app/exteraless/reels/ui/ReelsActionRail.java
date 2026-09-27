package app.exteraless.reels.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

/**
 * Правая колонка ролика, разложенная как в Reels: сверху аватар канала, ниже реакция со
 * счётчиком, «поделиться» со счётчиком пересылок, сохранение, открытие в канале, скрытие
 * канала, а внизу диск-нота.
 *
 * <p>Кнопки рисуются вручную: висят поверх произвольного кадра, поэтому нужен свой
 * круглый фон с белым силуэтом, который читается на любой картинке. Иконки стандартные,
 * перекрашенные в белый.
 */
class ReelsActionRail extends LinearLayout {

    interface Delegate {
        void onAction(int action);

        void onChannelClick();
    }

    private static final int BUTTON_SIZE_DP = 44;
    private static final int AVATAR_SIZE_DP = 46;
    private static final int DISC_SIZE_DP = 34;
    private static final int SPACING_DP = 12;
    private static final int COUNTER_SIZE_SP = 12;

    private final ChannelAvatarButton avatarButton;
    private final ButtonSlot reactSlot;
    private final ButtonSlot commentsSlot;
    private final ButtonSlot shareSlot;

    ReelsActionRail(Context context, Delegate delegate) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        avatarButton = new ChannelAvatarButton(context, AVATAR_SIZE_DP, delegate::onChannelClick);
        addView(avatarButton, itemParams());

        reactSlot = addButton(context, delegate, null, ReelsPageView.ACTION_REACT, ActionButton.Style.EMOJI);
        commentsSlot = addButton(context, delegate, R.drawable.menu_stream_comments_24,
                ReelsPageView.ACTION_COMMENTS, ActionButton.Style.ICON);
        shareSlot = addButton(context, delegate, R.drawable.msg_share, ReelsPageView.ACTION_SHARE, ActionButton.Style.ICON);
        addButton(context, delegate, R.drawable.msg_download, ReelsPageView.ACTION_SAVE, ActionButton.Style.ICON);
        addButton(context, delegate, R.drawable.filled_forward, ReelsPageView.ACTION_OPEN_CHAT, ActionButton.Style.ICON);
        addButton(context, delegate, R.drawable.msg_archive_hide, ReelsPageView.ACTION_HIDE_CHANNEL, ActionButton.Style.ICON);
        addView(new TrackDisc(context), itemParams());
    }

    private ButtonSlot addButton(Context context, Delegate delegate, Integer iconRes,
                                int action, ActionButton.Style style) {
        ButtonSlot slot = new ButtonSlot(context, action, style);
        if (iconRes != null) {
            slot.button.setIcon(iconRes);
        }
        CharSequence description = description(action);
        if (description != null) {
            slot.button.setContentDescription(description);
        }
        slot.button.setOnClickListener(v -> delegate.onAction(action));
        addView(slot, itemParams());
        return slot;
    }

    private static CharSequence description(int action) {
        switch (action) {
            case ReelsPageView.ACTION_REACT:
                return LocaleController.getString(R.string.ReelsReact);
            case ReelsPageView.ACTION_SHARE:
                return LocaleController.getString(R.string.ReelsShare);
            case ReelsPageView.ACTION_COMMENTS:
                return LocaleController.getString(R.string.ReelsComments);
            case ReelsPageView.ACTION_SAVE:
                return LocaleController.getString(R.string.ReelsSave);
            case ReelsPageView.ACTION_OPEN_CHAT:
                return LocaleController.getString(R.string.ReelsOpenInChat);
            case ReelsPageView.ACTION_HIDE_CHANNEL:
                return LocaleController.getString(R.string.ReelsHideChannel);
            default:
                return null;
        }
    }

    private LayoutParams itemParams() {
        LayoutParams params = new LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        params.topMargin = dp(SPACING_DP);
        return params;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    void bind(TLRPC.Chat chat, ReactionsLayoutInBubble.VisibleReaction favoriteReaction, MessageObject message) {
        avatarButton.setChat(chat);
        boolean hasReaction = favoriteReaction != null;
        reactSlot.button.setEmoji(hasReaction && favoriteReaction.emojicon != null ? favoriteReaction.emojicon : "👍");
        reactSlot.button.setVisibility(hasReaction ? VISIBLE : INVISIBLE);
        reactSlot.setCounterVisible(hasReaction);

        int forwards = message != null && message.messageOwner != null ? message.messageOwner.forwards : 0;
        shareSlot.setCount(forwards);

        // Обсуждение есть не у каждого поста: где его нет, кнопку прячем, но место она
        // оставляет — иначе колонка дёргалась бы при листании.
        boolean hasComments = ReelsPageView.hasComments(message);
        commentsSlot.button.setVisibility(hasComments ? VISIBLE : INVISIBLE);
        commentsSlot.setCount(message != null ? message.getRepliesCount() : 0);
        commentsSlot.setCounterVisible(hasComments);
    }

    void setLikeState(boolean chosen, int count) {
        reactSlot.button.setChosen(chosen);
        reactSlot.setCount(count);
    }

    /** Кнопка вместе со своим счётчиком: счётчики соседей не должны прыгать по высоте. */
    private class ButtonSlot extends LinearLayout {
        final ActionButton button;
        private final TextView counter;

        ButtonSlot(Context context, int action, ActionButton.Style style) {
            super(context);
            setOrientation(VERTICAL);
            setGravity(Gravity.CENTER_HORIZONTAL);
            button = new ActionButton(context, action, style);
            addView(button, new LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            counter = new TextView(context);
            counter.setTextColor(0xFFFFFFFF);
            counter.setTextSize(COUNTER_SIZE_SP);
            counter.setGravity(Gravity.CENTER);
            counter.setMaxLines(1);
            counter.setShadowLayer(2, 0, 1, 0x66000000);
            counter.setVisibility(INVISIBLE);
            LayoutParams counterParams = new LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
            counterParams.topMargin = dp(1);
            addView(counter, counterParams);
        }

        void setCount(int count) {
            if (count > 0) {
                counter.setText(LocaleController.formatNumber(count, ','));
                counter.setVisibility(VISIBLE);
            } else {
                counter.setText(null);
                counter.setVisibility(INVISIBLE);
            }
        }

        void setCounterVisible(boolean visible) {
            counter.setVisibility(visible && counter.getText() != null ? VISIBLE : INVISIBLE);
        }
    }

    /** Аватар канала во главе колонки: открывает канал, как «подписаться» в Reels. */
    private static class ChannelAvatarButton extends FrameLayout {
        private final ImageReceiverView avatar;
        private final TextView plus;

        ChannelAvatarButton(Context context, int sizeDp, Runnable onClick) {
            super(context);
            float density = getResources().getDisplayMetrics().density;
            int size = (int) (sizeDp * density + 0.5f);
            avatar = new ImageReceiverView(context);
            avatar.getImageReceiver().setRoundRadiusForAvatar(size);
            addView(avatar, new LayoutParams(size, size));

            GradientDrawable badge = new GradientDrawable();
            badge.setShape(GradientDrawable.OVAL);
            badge.setColor(0xFFFF2D55);
            plus = new TextView(context);
            plus.setText("+");
            plus.setTextColor(Color.WHITE);
            plus.setTextSize(14);
            plus.setGravity(Gravity.CENTER);
            plus.setBackground(badge);
            LayoutParams plusParams = new LayoutParams((int) (sizeDp * 0.46 * density),
                    (int) (sizeDp * 0.46 * density), Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
            addView(plus, plusParams);

            setContentDescription(LocaleController.getString(R.string.ReelsOpenInChat));
            setOnClickListener(v -> onClick.run());
            setClickable(true);
        }

        void setChat(TLRPC.Chat chat) {
            if (chat == null) {
                avatar.getImageReceiver().setImageBitmap((Drawable) null);
                plus.setVisibility(GONE);
                return;
            }
            AvatarDrawable avatarDrawable = new AvatarDrawable();
            avatarDrawable.setInfo(chat);
            avatar.getImageReceiver().setForUserOrChat(chat, avatarDrawable, chat);
            plus.setVisibility(VISIBLE);
        }
    }

    /** Диск с нотой внизу колонки — визуальная примета Reels. */
    private static class TrackDisc extends View {
        private final Paint discPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint notePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        TrackDisc(Context context) {
            super(context);
            discPaint.setColor(0xFF1C1C1E);
            ringPaint.setColor(0x33FFFFFF);
            ringPaint.setStyle(Paint.Style.STROKE);
            notePaint.setColor(Color.WHITE);
            notePaint.setTextAlign(Paint.Align.CENTER);
            notePaint.setTextSize(dp(DISC_SIZE_DP) * 0.46f);
        }

        private int dp(float value) {
            return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = dp(DISC_SIZE_DP);
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            float radius = Math.min(cx, cy);
            canvas.drawCircle(cx, cy, radius, discPaint);
            canvas.drawCircle(cx, cy, radius * 0.86f, ringPaint);
            Paint.FontMetrics metrics = notePaint.getFontMetrics();
            canvas.drawText("♪", cx, cy - (metrics.ascent + metrics.descent) / 2f, notePaint);
        }
    }

    /** Круглая кнопка: иконка из ресурсов либо эмодзи реакции. */
    static class ActionButton extends View {
        enum Style {
            ICON,
            EMOJI
        }

        final int action;
        private final Style style;
        private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        private int iconRes;
        private Drawable icon;
        private String emoji = "👍";
        private boolean chosen;

        ActionButton(Context context, int action, Style style) {
            super(context);
            this.action = action;
            this.style = style;
            textPaint.setColor(Color.WHITE);
            textPaint.setTextAlign(Paint.Align.CENTER);
            textPaint.setTextSize(dp(BUTTON_SIZE_DP) * 0.5f);
            textPaint.setFakeBoldText(true);
            circlePaint.setColor(0x59000000);
            setBackground(circleBackground());
        }

        private static Drawable circleBackground() {
            GradientDrawable drawable = new GradientDrawable();
            drawable.setShape(GradientDrawable.OVAL);
            drawable.setColor(0x59000000);
            return drawable;
        }

        private int dp(float value) {
            return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
        }

        void setIcon(int res) {
            iconRes = res;
            invalidate();
        }

        void setEmoji(String emoji) {
            this.emoji = TextUtils.isEmpty(emoji) ? "👍" : emoji;
            invalidate();
        }

        void setChosen(boolean chosen) {
            this.chosen = chosen;
            invalidate();
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            int size = dp(BUTTON_SIZE_DP);
            setMeasuredDimension(size, size);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            int cx = getWidth() / 2;
            int cy = getHeight() / 2;
            float radius = Math.min(cx, cy) * 0.86f;
            canvas.drawCircle(cx, cy, radius, circlePaint);
            if (style == Style.EMOJI) {
                textPaint.setAlpha(chosen ? 255 : 170);
                Paint.FontMetrics metrics = textPaint.getFontMetrics();
                float baseline = cy - (metrics.ascent + metrics.descent) / 2f;
                canvas.drawText(emoji, cx, baseline, textPaint);
                return;
            }
            if (iconRes == 0) {
                return;
            }
            if (icon == null) {
                icon = getResources().getDrawable(iconRes);
                if (icon != null) {
                    icon = icon.mutate();
                    icon.setColorFilter(new PorterDuffColorFilter(Color.WHITE, PorterDuff.Mode.SRC_IN));
                }
            }
            if (icon == null) {
                return;
            }
            int size = (int) (radius * 1.7f);
            int left = cx - size / 2;
            int top = cy - size / 2;
            icon.setBounds(left, top, left + size, top + size);
            icon.setAlpha(225);
            icon.draw(canvas);
        }
    }
}
