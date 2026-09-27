package app.exteraless.reels.ui;

import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.ImageReceiver;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;


/**
 * Одна страница вертикальной ленты: постер, подписи и действия поверх него.
 *
 * <p>Само видео страницу не рисует: его показывает общий слой плеера поверх пейджера
 * (см. {@link ReelsPlayerController}). Так текстура не переезжает между переиспользованными
 * вьюхами, а на странице остаётся только то, что дёшево перерисовать.
 *
 * <p>Тач обрабатывается осторожно: по вертикали жест должен уходить списку, по горизонтали —
 * панели вкладок. Поэтому вьюха забирает только тапы и отпускает жест при первом же
 * смещении за пределы слинга.
 */
public class ReelsPageView extends FrameLayout {

    public static final int ACTION_REACT = 1;
    public static final int ACTION_SHARE = 2;
    public static final int ACTION_OPEN_CHAT = 3;
    public static final int ACTION_SAVE = 4;
    public static final int ACTION_HIDE_CHANNEL = 5;
    public static final int ACTION_REACTIONS_MENU = 6;
    public static final int ACTION_MUTE = 7;

    private static final int CAPTION_COLLAPSED_LINES = 2;
    private static final int CAPTION_EXPANDED_LINES = 8;

    private static final int COLOR_TEXT = 0xFFFFFFFF;
    private static final int COLOR_TEXT_MUTED = 0x99FFFFFF;

    public interface Delegate {
        void onSingleTap(ReelsPageView page);

        void onDoubleTap(ReelsPageView page, float x, float y);

        void onAction(ReelsPageView page, int action);

        void onChannelClick(ReelsPageView page);

        void onCaptionClick(ReelsPageView page);
    }

    private final Delegate delegate;

    private final ImageReceiverView posterView;
    private final ImageReceiverView avatarView;
    private final AvatarDrawable avatarDrawable = new AvatarDrawable();
    private final TextView channelTitle;
    private final TextView captionText;
    private final TextView metaText;
    private final ImageView playIcon;
    private final ImageView muteIcon;
    private final ProgressBar bufferingIndicator;
    private final ReelsActionRail rail;
    private final ReelsProgressView progressView;
    private final View topScrim;
    private final LinearLayout infoColumn;
    private final GestureDetector gestureDetector;

    private MessageObject message;
    private TLRPC.Chat chat;
    private ReactionsLayoutInBubble.VisibleReaction favoriteReaction;
    private final Runnable hidePlayIcon;

    private boolean captionExpanded;
    private boolean gestureStolen;
    private boolean pausedByUser;
    private boolean active;
    private float downX;
    private float downY;
    private final int touchSlop;

    public ReelsPageView(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();

        // Фона у страницы нет намеренно: под ней лежит слой с видео, и непрозрачная
        // страница закрыла бы ролик вместе с подписями.
        posterView = new ImageReceiverView(context);
        addView(posterView, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        topScrim = createScrim(GradientDrawable.Orientation.TOP_BOTTOM, 0xB3000000, 0x00000000, dp(120));
        addView(topScrim, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, dp(120), Gravity.TOP));
        View bottomScrim = createScrim(GradientDrawable.Orientation.BOTTOM_TOP, 0xE6000000, 0x00000000, dp(220));
        addView(bottomScrim, new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, dp(220), Gravity.BOTTOM));

        playIcon = new ImageView(context);
        playIcon.setImageResource(R.drawable.ic_action_play);
        playIcon.setColorFilter(COLOR_TEXT);
        playIcon.setAlpha(0f);
        playIcon.setVisibility(INVISIBLE);
        addView(playIcon, new FrameLayout.LayoutParams(dp(72), dp(72), Gravity.CENTER));
        hidePlayIcon = () -> {
            if (playIcon.getVisibility() == VISIBLE) {
                playIcon.animate().alpha(0f).setDuration(200).withEndAction(
                        () -> playIcon.setVisibility(INVISIBLE)).start();
            }
        };

        bufferingIndicator = new ProgressBar(context);
        bufferingIndicator.setIndeterminate(true);
        bufferingIndicator.setVisibility(INVISIBLE);
        FrameLayout.LayoutParams bufferingParams = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.CENTER);
        bufferingParams.topMargin = dp(64);
        addView(bufferingIndicator, bufferingParams);

        muteIcon = new ImageView(context);
        muteIcon.setImageResource(R.drawable.filled_profile_mute_24);
        muteIcon.setColorFilter(COLOR_TEXT);
        muteIcon.setPadding(dp(9), dp(9), dp(9), dp(9));
        muteIcon.setBackground(roundBackground(0x33000000, dp(18)));
        muteIcon.setContentDescription(LocaleController.getString(R.string.ReelsMute));
        FrameLayout.LayoutParams muteParams = new FrameLayout.LayoutParams(dp(36), dp(36), Gravity.TOP | Gravity.END);
        muteParams.topMargin = dp(8);
        muteParams.rightMargin = dp(10);
        addView(muteIcon, muteParams);
        muteIcon.setOnClickListener(v -> delegate.onAction(this, ACTION_MUTE));

        infoColumn = new LinearLayout(context);
        infoColumn.setOrientation(LinearLayout.VERTICAL);
        infoColumn.setGravity(Gravity.START);

        LinearLayout channelRow = new LinearLayout(context);
        channelRow.setOrientation(LinearLayout.HORIZONTAL);
        channelRow.setGravity(Gravity.CENTER_VERTICAL);
        avatarView = new ImageReceiverView(context);
        avatarView.getImageReceiver().setRoundRadiusForAvatar(dp(32));
        channelRow.addView(avatarView, new LinearLayout.LayoutParams(dp(32), dp(32)));
        channelTitle = new TextView(context);
        channelTitle.setTextColor(COLOR_TEXT);
        channelTitle.setTextSize(15);
        channelTitle.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        channelTitle.setMaxLines(1);
        channelTitle.setEllipsize(TextUtils.TruncateAt.END);
        channelTitle.setSingleLine(true);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        titleParams.leftMargin = dp(10);
        channelRow.addView(channelTitle, titleParams);
        channelRow.setOnClickListener(v -> delegate.onChannelClick(this));
        infoColumn.addView(channelRow, new LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, dp(32)));

        captionText = new TextView(context);
        captionText.setTextColor(COLOR_TEXT);
        captionText.setTextSize(14);
        captionText.setMaxLines(CAPTION_COLLAPSED_LINES);
        captionText.setEllipsize(TextUtils.TruncateAt.END);
        captionText.setShadowLayer(dp(3), 0, dp(1), 0x66000000);
        captionText.setOnClickListener(v -> toggleCaption());
        LinearLayout.LayoutParams captionParams = new LinearLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        captionParams.topMargin = dp(10);
        infoColumn.addView(captionText, captionParams);

        metaText = new TextView(context);
        metaText.setTextColor(COLOR_TEXT_MUTED);
        metaText.setTextSize(13);
        metaText.setMaxLines(1);
        metaText.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        metaParams.topMargin = dp(6);
        infoColumn.addView(metaText, metaParams);

        FrameLayout.LayoutParams infoParams = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START);
        infoParams.leftMargin = dp(14);
        infoParams.rightMargin = dp(76);
        infoParams.bottomMargin = dp(18);
        addView(infoColumn, infoParams);

        rail = new ReelsActionRail(context, action -> delegate.onAction(ReelsPageView.this, action));
        FrameLayout.LayoutParams railParams = new FrameLayout.LayoutParams(dp(60), LayoutHelper.WRAP_CONTENT, Gravity.BOTTOM | Gravity.END);
        railParams.rightMargin = dp(6);
        railParams.bottomMargin = dp(14);
        addView(rail, railParams);

        progressView = new ReelsProgressView(context);
        FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, dp(3), Gravity.BOTTOM);
        progressView.setVisibility(INVISIBLE);
        addView(progressView, progressParams);

        gestureDetector = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onDown(MotionEvent e) {
                return true;
            }

            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                delegate.onSingleTap(ReelsPageView.this);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                delegate.onDoubleTap(ReelsPageView.this, e.getX(), e.getY());
                return true;
            }
        });
    }

    private int dp(float value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static GradientDrawable roundBackground(int color, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private View createScrim(GradientDrawable.Orientation orientation, int from, int to, int height) {
        View view = new View(getContext());
        GradientDrawable drawable = new GradientDrawable(orientation, new int[]{from, to});
        view.setBackground(drawable);
        view.setLayoutParams(new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, height));
        return view;
    }

    // ---- привязка данных ----

    public void bind(MessageObject message, TLRPC.Chat chat, ReactionsLayoutInBubble.VisibleReaction favoriteReaction) {
        this.message = message;
        this.chat = chat;
        this.favoriteReaction = favoriteReaction;
        captionExpanded = false;
        pausedByUser = false;
        captionText.setMaxLines(CAPTION_COLLAPSED_LINES);
        playIcon.removeCallbacks(hidePlayIcon);
        playIcon.setVisibility(INVISIBLE);
        captionText.setEllipsize(TextUtils.TruncateAt.END);

        channelTitle.setText(chat != null && chat.title != null ? chat.title : "");
        avatarView.setVisibility(chat != null ? VISIBLE : INVISIBLE);
        if (chat != null) {
            avatarDrawable.setInfo(chat);
            avatarView.getImageReceiver().setForUserOrChat(chat, avatarDrawable, chat);
        } else {
            avatarView.getImageReceiver().setImageBitmap((Drawable) null);
        }

        CharSequence caption = message.messageText;
        if (TextUtils.isEmpty(caption)) {
            captionText.setVisibility(GONE);
        } else {
            captionText.setVisibility(VISIBLE);
            captionText.setText(caption);
        }

        StringBuilder meta = new StringBuilder();
        if (message.messageOwner != null && message.messageOwner.views > 0) {
            meta.append(LocaleController.formatNumber(message.messageOwner.views, ','));
            meta.append(' ');
            meta.append(LocaleController.formatPluralString("ReelsViews", message.messageOwner.views));
        }
        if (meta.length() > 0) {
            meta.append("  ·  ");
        }
        meta.append(LocaleController.formatDate(message.messageOwner.date, true));
        metaText.setText(meta);

        loadPoster(message);
        rail.bind(message, favoriteReaction);
        updateLikeCounter();
    }

    private void loadPoster(MessageObject message) {
        ImageReceiver receiver = posterView.getImageReceiver();
        receiver.cancelLoadImage();
        if (message.isPhoto()) {
            TLRPC.MessageMedia media = MessageObject.getMedia(message.messageOwner);
            if (media instanceof TLRPC.TL_messageMediaPhoto) {
                TLRPC.Photo photo = ((TLRPC.TL_messageMediaPhoto) media).photo;
                TLRPC.PhotoSize size = FileLoader.getClosestPhotoSizeWithSize(photo.sizes, AndroidUtilities.getPhotoSize());
                if (size == null) {
                    posterView.setMediaAspect(0, false);
                    receiver.setImageBitmap((Drawable) null);
                    return;
                }
                // Фото вписываем целиком: кропнул бы мем по краям.
                posterView.setMediaAspect(size.w / (float) size.h, false);
                receiver.setImage(ImageLocation.getForPhoto(size, photo), null, null, null, null, 0, null, message, 0);
                return;
            }
        }
        TLRPC.Document document = message.getDocument();
        if (document == null) {
            posterView.setMediaAspect(0, true);
            receiver.setImageBitmap((Drawable) null);
            return;
        }
        posterView.setMediaAspect(documentAspect(document), true);
        if (document.thumbs.isEmpty()) {
            receiver.setImageBitmap((Drawable) null);
            return;
        }
        // 200px на экране 1080p превращается в кашу, пока ролик не начал играть.
        TLRPC.PhotoSize thumb = FileLoader.getClosestPhotoSizeWithSize(document.thumbs,
                Math.max(512, AndroidUtilities.getPhotoSize()));
        receiver.setImage(null, null, ImageLocation.getForDocument(thumb, document), "b", null,
                document.size, null, message, 0);
    }

    /** Пропорции ролика: из атрибута видео, иначе из превью, иначе неизвестны. */
    private static float documentAspect(TLRPC.Document document) {
        for (int i = 0; i < document.attributes.size(); i++) {
            TLRPC.DocumentAttribute attribute = document.attributes.get(i);
            if (attribute instanceof TLRPC.TL_documentAttributeVideo
                    && ((TLRPC.TL_documentAttributeVideo) attribute).w > 0
                    && ((TLRPC.TL_documentAttributeVideo) attribute).h > 0) {
                TLRPC.TL_documentAttributeVideo video = (TLRPC.TL_documentAttributeVideo) attribute;
                return video.w / (float) video.h;
            }
        }
        for (int i = 0; i < document.thumbs.size(); i++) {
            TLRPC.PhotoSize size = document.thumbs.get(i);
            if (size != null && size.w > 0 && size.h > 0) {
                return size.w / (float) size.h;
            }
        }
        return 0;
    }

    private void updateLikeCounter() {
        if (message == null || message.messageOwner == null) {
            return;
        }
        int count = 0;
        boolean chosen = false;
        if (message.messageOwner.reactions != null) {
            for (int i = 0; i < message.messageOwner.reactions.results.size(); i++) {
                TLRPC.ReactionCount reactionCount = message.messageOwner.reactions.results.get(i);
                count += reactionCount.count;
                if (reactionCount.reaction != null && favoriteReaction != null
                        && matches(favoriteReaction, reactionCount.reaction)) {
                    chosen = chosen || reactionCount.chosen;
                }
            }
        }
        rail.setLikeState(chosen, count);
    }

    private static boolean matches(ReactionsLayoutInBubble.VisibleReaction visible, TLRPC.Reaction reaction) {
        if (visible.emojicon != null && reaction instanceof TLRPC.TL_reactionEmoji) {
            return visible.emojicon.equals(((TLRPC.TL_reactionEmoji) reaction).emoticon);
        }
        return visible.documentId != 0 && reaction instanceof TLRPC.TL_reactionCustomEmoji
                && visible.documentId == ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
    }

    public ReactionsLayoutInBubble.VisibleReaction getFavoriteReaction() {
        return favoriteReaction;
    }

    public MessageObject getMessage() {
        return message;
    }

    public TLRPC.Chat getChat() {
        return chat;
    }

    // ---- состояние страницы ----

    /**
     * Страница стала текущей или перестала быть ею. У текущей страницы видны прогресс
     * и отметка просмотренного, у соседних — только постер.
     */
    public void setActive(boolean active) {
        if (this.active == active) {
            return;
        }
        this.active = active;
        progressView.setVisibility(active ? VISIBLE : INVISIBLE);
        if (!active) {
            playIcon.removeCallbacks(hidePlayIcon);
            playIcon.setVisibility(INVISIBLE);
            bufferingIndicator.setVisibility(INVISIBLE);
        }
        if (active) {
            showMedia();
        }
    }

    public boolean isPausedByUser() {
        return pausedByUser;
    }

    public void setPausedByUser(boolean value) {
        pausedByUser = value;
    }

    /** Прячет постер, когда его место занимает слой плеера. */
    public void hideMedia() {
        posterView.setVisibility(INVISIBLE);
        posterView.cancelLoading();
    }

    public void showMedia() {
        posterView.setVisibility(VISIBLE);
    }

    public void showBuffering(boolean buffering) {
        bufferingIndicator.setVisibility(buffering ? VISIBLE : INVISIBLE);
    }

    public void showPlayIcon(boolean paused) {
        playIcon.setImageResource(paused ? R.drawable.ic_action_play : R.drawable.ic_action_pause);
        playIcon.clearAnimation();
        playIcon.setVisibility(VISIBLE);
        playIcon.setAlpha(0f);
        playIcon.setScaleX(0.8f);
        playIcon.setScaleY(0.8f);
        AnimatorSet set = new AnimatorSet();
        set.playTogether(
                ObjectAnimator.ofFloat(playIcon, View.ALPHA, 0f, 1f),
                ObjectAnimator.ofFloat(playIcon, View.SCALE_X, 0.8f, 1f),
                ObjectAnimator.ofFloat(playIcon, View.SCALE_Y, 0.8f, 1f));
        set.setDuration(160);
        set.start();
        playIcon.removeCallbacks(hidePlayIcon);
        playIcon.postDelayed(hidePlayIcon, 700);
    }

    public void setMuted(boolean muted) {
        muteIcon.setImageResource(muted
                ? R.drawable.filled_profile_mute_24
                : R.drawable.filled_profile_unmute_24);
        muteIcon.setContentDescription(LocaleController.getString(
                muted ? R.string.ReelsUnmute : R.string.ReelsMute));
        muteIcon.setOnClickListener(v -> delegate.onAction(this, ACTION_MUTE));
    }

    /**
     * Отступы сверху и снизу: медиа остаётся во всю высоту, а подписи, панель действий и
     * кнопка звука уходят из-под статус-бара и панели вкладок.
     */
    public void setInsets(int top, int bottom) {
        FrameLayout.LayoutParams scrimParams = (FrameLayout.LayoutParams) topScrim.getLayoutParams();
        scrimParams.height = dp(120) + top;
        topScrim.setLayoutParams(scrimParams);

        FrameLayout.LayoutParams muteParams = (FrameLayout.LayoutParams) muteIcon.getLayoutParams();
        muteParams.topMargin = dp(8) + top;
        muteIcon.setLayoutParams(muteParams);

        FrameLayout.LayoutParams infoParams = (FrameLayout.LayoutParams) infoColumn.getLayoutParams();
        infoParams.bottomMargin = dp(18) + bottom;
        infoColumn.setLayoutParams(infoParams);

        FrameLayout.LayoutParams railParams = (FrameLayout.LayoutParams) rail.getLayoutParams();
        railParams.bottomMargin = dp(14) + bottom;
        rail.setLayoutParams(railParams);
    }

    public void setProgress(float progress, float buffered) {
        if (active) {
            progressView.setProgress(progress, buffered);
        }
    }

    public void refreshReaction() {
        updateLikeCounter();
    }

    private void toggleCaption() {
        captionExpanded = !captionExpanded;
        captionText.setMaxLines(captionExpanded ? CAPTION_EXPANDED_LINES : CAPTION_COLLAPSED_LINES);
        if (!captionExpanded) {
            captionText.setEllipsize(TextUtils.TruncateAt.END);
        } else {
            captionText.setEllipsize(null);
        }
        delegate.onCaptionClick(this);
    }

    // ---- жесты ----

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        gestureDetector.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                downX = event.getX();
                downY = event.getY();
                gestureStolen = false;
                return true;
            case MotionEvent.ACTION_UP:
                if (!gestureStolen) {
                    performClick();
                }
                return true;
            case MotionEvent.ACTION_CANCEL:
                return true;
            case MotionEvent.ACTION_MOVE:
                // Сдвиг — не наше дело: список листает по вертикали, панель вкладок — по
                // горизонтали. Отпускаем жест, иначе лента не прокрутится.
                if (Math.abs(event.getX() - downX) > touchSlop || Math.abs(event.getY() - downY) > touchSlop) {
                    gestureStolen = true;
                    return false;
                }
                return true;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDetachedFromWindow() {
        posterView.cancelLoading();
        super.onDetachedFromWindow();
    }

    /** Тонкая полоса прогресса ролика поверх медиа. */
    private static class ReelsProgressView extends View {

        private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint bufferedPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private float progress;
        private float buffered;

        ReelsProgressView(Context context) {
            super(context);
            backgroundPaint.setColor(0x33FFFFFF);
            progressPaint.setColor(0xFFFFFFFF);
            bufferedPaint.setColor(0x66FFFFFF);
        }

        void setProgress(float progress, float buffered) {
            if (Math.abs(this.progress - progress) < 0.001f && Math.abs(this.buffered - buffered) < 0.001f) {
                return;
            }
            this.progress = progress;
            this.buffered = buffered;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            int width = getWidth();
            float radius = getHeight() / 2f;
            canvas.drawRoundRect(0, 0, width, getHeight(), radius, radius, backgroundPaint);
            float bufferedWidth = width * Math.max(0f, Math.min(1f, buffered));
            if (bufferedWidth > 0) {
                canvas.drawRoundRect(0, 0, bufferedWidth, getHeight(), radius, radius, bufferedPaint);
            }
            float progressWidth = width * Math.max(0f, Math.min(1f, progress));
            if (progressWidth > 0) {
                canvas.drawRoundRect(0, 0, progressWidth, getHeight(), radius, radius, progressPaint);
            }
        }
    }
}
