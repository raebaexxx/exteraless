package app.exteraless.reels.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

/**
 * Правая колонка действий ролика: реакция, «поделиться», «открыть в канале», «сохранить»
 * и «скрыть канал».
 *
 * <p>Кнопки рисуются вручную, а не набраны из готовых строк: в ленте они висят поверх
 * произвольного кадра, поэтому нужен свой круглый фон с белым силуэтом, который читается
 * на любой картинке. Иконки внутри — стандартные, перекрашенные в белый.
 */
class ReelsActionRail extends LinearLayout {

    interface Delegate {
        void onAction(int action);
    }

    private static final int BUTTON_SIZE_DP = 40;
    private static final int SPACING_DP = 18;
    private static final int COUNTER_SIZE_SP = 12;

    private final ActionButton reactButton;
    private final TextView reactCounter;

    ReelsActionRail(Context context, Delegate delegate) {
        super(context);
        setOrientation(VERTICAL);
        setGravity(Gravity.CENTER_HORIZONTAL);

        reactButton = new ActionButton(context, ReelsPageView.ACTION_REACT, ActionButton.Style.EMOJI);
        reactButton.setOnClickListener(v -> delegate.onAction(ReelsPageView.ACTION_REACT));
        // Долгое нажатие открывает остальные реакции канала: в ленте их может быть
        // несколько, а места на отдельные кнопки нет.
        reactButton.setOnLongClickListener(v -> {
            delegate.onAction(ReelsPageView.ACTION_REACTIONS_MENU);
            return true;
        });
        reactCounter = new TextView(context);
        reactCounter.setTextColor(0xFFFFFFFF);
        reactCounter.setTextSize(COUNTER_SIZE_SP);
        reactCounter.setGravity(Gravity.CENTER);
        reactCounter.setMaxLines(1);
        reactCounter.setVisibility(INVISIBLE);
        addView(reactButton, new LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
        addView(reactCounter, counterParams());

        addView(createIconButton(context, delegate, R.drawable.msg_share, R.string.ReelsShare, ReelsPageView.ACTION_SHARE));
        addView(createIconButton(context, delegate, R.drawable.filled_forward, R.string.ReelsOpenInChat, ReelsPageView.ACTION_OPEN_CHAT));
        addView(createIconButton(context, delegate, R.drawable.msg_download, R.string.ReelsSave, ReelsPageView.ACTION_SAVE));
        addView(createIconButton(context, delegate, R.drawable.msg_archive_hide, R.string.ReelsHideChannel, ReelsPageView.ACTION_HIDE_CHANNEL));
    }

    private static ActionButton createIconButton(Context context, Delegate delegate, int iconRes, int descriptionRes, int action) {
        ActionButton button = new ActionButton(context, action, ActionButton.Style.ICON);
        button.setIcon(iconRes);
        button.setContentDescription(LocaleController.getString(descriptionRes));
        button.setOnClickListener(v -> delegate.onAction(action));
        return button;
    }

    private LayoutParams counterParams() {
        LayoutParams params = new LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        params.topMargin = dp(2);
        return params;
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LayoutParams itemParams() {
        LayoutParams params = new LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        params.topMargin = dp(SPACING_DP);
        return params;
    }

    void bind(MessageObject message, ReactionsLayoutInBubble.VisibleReaction favoriteReaction) {
        boolean hasReaction = favoriteReaction != null;
        reactButton.setEmoji(hasReaction && favoriteReaction.emojicon != null ? favoriteReaction.emojicon : "👍");
        reactButton.setVisibility(hasReaction ? VISIBLE : GONE);
        reactCounter.setVisibility(hasReaction && reactCounter.getText().length() > 0 ? VISIBLE : INVISIBLE);
    }

    void setLikeState(boolean chosen, int count) {
        reactButton.setChosen(chosen);
        if (count > 0) {
            reactCounter.setText(LocaleController.formatNumber(count, ','));
            reactCounter.setVisibility(VISIBLE);
        } else {
            reactCounter.setText(null);
            reactCounter.setVisibility(INVISIBLE);
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
            textPaint.setTextSize(dp(BUTTON_SIZE_DP) * 0.48f);
            textPaint.setFakeBoldText(true);
            circlePaint.setColor(0x59000000);
        }

        private int dp(float value) {
            return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
        }

        void setIcon(int res) {
            iconRes = res;
            invalidate();
        }

        void setEmoji(String emoji) {
            this.emoji = emoji == null || TextUtils.isEmpty(emoji) ? "👍" : emoji;
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
            float radius = Math.min(cx, cy) * 0.84f;
            canvas.drawCircle(cx, cy, radius, circlePaint);
            if (style == Style.EMOJI) {
                textPaint.setAlpha(chosen ? 255 : 165);
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
            int size = (int) (radius * 1.9f);
            int left = cx - size / 2;
            int top = cy - size / 2;
            icon.setBounds(left, top, left + size, top + size);
            icon.setAlpha(chosen ? 255 : 225);
            icon.draw(canvas);
        }
    }
}
