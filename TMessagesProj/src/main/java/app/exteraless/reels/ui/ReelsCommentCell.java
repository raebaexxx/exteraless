package app.exteraless.reels.ui;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.TextUtils;
import android.text.style.StrikethroughSpan;
import android.text.style.StyleSpan;
import android.text.style.TypefaceSpan;
import android.text.style.URLSpan;
import android.text.style.UnderlineSpan;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;


/**
 * Один комментарий в шторке: аватар, имя со временем, текст и реакция со счётчиком.
 *
 * <p>Разметка как в Reels: подпись слева, реакция справа от имени. Текст разбирается
 * минимально — жирный, курсив, ссылки, упоминания и хештеги, — потому что полный
 * конвейер клиента рассчитан на ячейку чата с её аватарами, разделителями и вложениями.
 */
public class ReelsCommentCell extends LinearLayout {

    private static final int AVATAR_SIZE_DP = 36;
    private static final int REACTION_SIZE_DP = 26;
    private static final int MAX_LINES = 12;
    private static final int SPAN = Spannable.SPAN_EXCLUSIVE_EXCLUSIVE;

    public interface Delegate {
        void onReactionClick(MessageObject comment);
    }

    private final Delegate delegate;
    private final ImageReceiverView avatar;
    private final TextView nameText;
    private final TextView captionText;
    private final TextView reactionText;
    private final TextView counterText;

    private MessageObject comment;

    public ReelsCommentCell(Context context, Delegate delegate) {
        super(context);
        this.delegate = delegate;
        setOrientation(HORIZONTAL);
        setGravity(Gravity.TOP);
        setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10), AndroidUtilities.dp(14), AndroidUtilities.dp(10));

        avatar = new ImageReceiverView(context);
        avatar.getImageReceiver().setRoundRadiusForAvatar(AndroidUtilities.dp(AVATAR_SIZE_DP));
        addView(avatar, new LayoutParams(AndroidUtilities.dp(AVATAR_SIZE_DP), AndroidUtilities.dp(AVATAR_SIZE_DP)));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(VERTICAL);
        LayoutParams columnParams = new LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1);
        columnParams.leftMargin = AndroidUtilities.dp(10);
        addView(column, columnParams);

        LinearLayout head = new LinearLayout(context);
        head.setOrientation(HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        nameText = new TextView(context);
        nameText.setTextColor(0xFFFFFFFF);
        nameText.setTextSize(14);
        nameText.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        nameText.setMaxLines(1);
        nameText.setEllipsize(TextUtils.TruncateAt.END);
        head.addView(nameText, new LayoutParams(0, LayoutHelper.WRAP_CONTENT, 1));

        reactionText = new TextView(context);
        reactionText.setTextSize(15);
        reactionText.setGravity(Gravity.CENTER);
        reactionText.setContentDescription(LocaleController.getString(R.string.ReelsReact));
        reactionText.setOnClickListener(v -> {
            if (comment != null) {
                delegate.onReactionClick(comment);
            }
        });
        LayoutParams reactionParams = new LayoutParams(AndroidUtilities.dp(REACTION_SIZE_DP), AndroidUtilities.dp(REACTION_SIZE_DP));
        reactionParams.leftMargin = AndroidUtilities.dp(6);
        head.addView(reactionText, reactionParams);

        counterText = new TextView(context);
        counterText.setTextColor(0x99FFFFFF);
        counterText.setTextSize(12);
        counterText.setGravity(Gravity.CENTER);
        counterText.setMaxLines(1);
        LayoutParams counterParams = new LayoutParams(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT);
        counterParams.leftMargin = AndroidUtilities.dp(4);
        head.addView(counterText, counterParams);

        column.addView(head, new LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        captionText = new TextView(context);
        captionText.setTextColor(0xFFFFFFFF);
        captionText.setTextSize(15);
        captionText.setMaxLines(MAX_LINES);
        captionText.setEllipsize(TextUtils.TruncateAt.END);
        LayoutParams captionParams = new LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT);
        captionParams.topMargin = AndroidUtilities.dp(2);
        column.addView(captionText, captionParams);
    }

    public MessageObject getComment() {
        return comment;
    }

    public void setComment(MessageObject comment, String reaction, int reactionCount, boolean chosen) {
        this.comment = comment;
        avatar.getImageReceiver().setImageBitmap((Drawable) null);
        if (comment == null || comment.messageOwner == null) {
            return;
        }
        long fromId = comment.getSenderId();
        if (fromId > 0) {
            TLRPC.User user = MessagesController.getInstance(comment.currentAccount).getUser(fromId);
            if (user != null) {
                nameText.setText(UserObject.getUserName(user));
                AvatarDrawable drawable = new AvatarDrawable(user);
                avatar.getImageReceiver().setForUserOrChat(user, drawable, null);
            }
        } else if (fromId < 0) {
            TLRPC.Chat chat = MessagesController.getInstance(comment.currentAccount).getChat(-fromId);
            if (chat != null) {
                nameText.setText(chat.title);
                AvatarDrawable drawable = new AvatarDrawable();
                drawable.setInfo(chat);
                avatar.getImageReceiver().setForUserOrChat(chat, drawable, chat);
            }
        }
        CharSequence time = relativeTime(comment.messageOwner.date);
        SpannableStringBuilder head = new SpannableStringBuilder(nameText.getText()).append("  ").append(time);
        head.setSpan(new ForegroundColorSpan(0x80FFFFFF), head.length() - time.length(), head.length(), SPAN);
        head.setSpan(new StyleSpan(android.graphics.Typeface.NORMAL), head.length() - time.length(), head.length(), SPAN);
        nameText.setText(head);

        CharSequence text = buildText(comment.messageOwner.message, comment.messageOwner.entities);
        captionText.setText(text);
        captionText.setVisibility(TextUtils.isEmpty(text) ? GONE : VISIBLE);

        boolean hasReaction = !TextUtils.isEmpty(reaction);
        reactionText.setText(reaction);
        reactionText.setVisibility(hasReaction ? VISIBLE : GONE);
        reactionText.setAlpha(chosen ? 1f : 0.45f);
        if (reactionCount > 0 && hasReaction) {
            counterText.setText(LocaleController.formatNumber(reactionCount, ','));
            counterText.setVisibility(VISIBLE);
        } else {
            counterText.setVisibility(INVISIBLE);
        }
    }

    /**
     * Текст комментария с минимальной разметкой.
     *
     * <p>Клиентские сущности разбираем сами: {@code TextStyleSpan} в этом клиенте требует
     * целый конвейер раскладки, а здесь достаточно жирного, курсива, подчёркивания,
     * зачёркивания и ссылок.
     */
    static CharSequence buildText(String text, ArrayList<TLRPC.MessageEntity> entities) {
        if (TextUtils.isEmpty(text)) {
            return null;
        }
        SpannableStringBuilder builder = new SpannableStringBuilder(text);
        if (entities == null) {
            return builder;
        }
        for (int i = 0; i < entities.size(); i++) {
            TLRPC.MessageEntity entity = entities.get(i);
            int start = entity.offset;
            int end = entity.offset + entity.length;
            if (start < 0 || end > builder.length() || end <= start) {
                continue;
            }
            if (entity instanceof TLRPC.TL_messageEntityBold) {
                builder.setSpan(new StyleSpan(android.graphics.Typeface.BOLD), start, end, SPAN);
            } else if (entity instanceof TLRPC.TL_messageEntityItalic) {
                builder.setSpan(new StyleSpan(android.graphics.Typeface.ITALIC), start, end, SPAN);
            } else if (entity instanceof TLRPC.TL_messageEntityUnderline) {
                builder.setSpan(new UnderlineSpan(), start, end, SPAN);
            } else if (entity instanceof TLRPC.TL_messageEntityStrike) {
                builder.setSpan(new StrikethroughSpan(), start, end, SPAN);
            } else if (entity instanceof TLRPC.TL_messageEntityCode) {
                builder.setSpan(new TypefaceSpan("monospace"), start, end, SPAN);
            } else if (entity instanceof TLRPC.TL_messageEntityTextUrl) {
                builder.setSpan(new URLSpan(entity.url), start, end, SPAN);
            } else if (entity instanceof TLRPC.TL_messageEntityUrl) {
                builder.setSpan(new URLSpan("https://" + text.substring(start, end)), start, end, SPAN);
            } else if (entity instanceof TLRPC.TL_messageEntityMention) {
                builder.setSpan(new URLSpan("tg://user?id=" + text.substring(start, end)), start, end, SPAN);
            } else if (entity instanceof TLRPC.TL_messageEntityEmail) {
                builder.setSpan(new URLSpan("mailto:" + text.substring(start, end)), start, end, SPAN);
            }
        }
        return builder;
    }

    /** Короткое время как в Reels: «сейчас», «12 мин», «5 ч», «3 дн», дальше дата. */
    static CharSequence relativeTime(int date) {
        long diff = (System.currentTimeMillis() / 1000) - date;
        if (diff < 60) {
            return LocaleController.getString(R.string.ReelsJustNow);
        }
        if (diff < 3600) {
            return LocaleController.formatPluralString("ReelsMinutesAgo", (int) (diff / 60));
        }
        if (diff < 86400) {
            return LocaleController.formatPluralString("ReelsHoursAgo", (int) (diff / 3600));
        }
        if (diff < 86400 * 7) {
            return LocaleController.formatPluralString("ReelsDaysAgo", (int) (diff / 86400));
        }
        return LocaleController.formatDate(date, false);
    }

    @Override
    protected void onDetachedFromWindow() {
        avatar.cancelLoading();
        super.onDetachedFromWindow();
    }
}
