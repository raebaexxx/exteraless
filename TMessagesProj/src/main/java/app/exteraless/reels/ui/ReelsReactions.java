package app.exteraless.reels.ui;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessagesController;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.TLRPC.TL_reactionCustomEmoji;
import org.telegram.tgnet.TLRPC.TL_reactionEmoji;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;

/**
 * Реакции на посты «Клипов».
 *
 * <p>Набор не выдумываем: сервер всё равно отклонит реакцию, которой каналу нельзя, поэтому
 * берём ровно то, что канал разрешил. Если канал разрешил все реакции — отдаём стандартный
 * набор, иначе — список из его настроек.
 */
final class ReelsReactions {

    private static final String[] ALL_REACTIONS = {"👍", "❤️", "🔥", "😂", "😮"};

    /** Реакции, доступные в канале. Пустой список — реакций нет, кнопку прячем. */
    static ArrayList<ReactionsLayoutInBubble.VisibleReaction> availableReactions(int currentAccount, long dialogId) {
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> result = new ArrayList<>();
        TLRPC.ChatFull chatFull = MessagesController.getInstance(currentAccount).getChatFull(-dialogId);
        if (chatFull == null) {
            // Полный канал ещё не приехал: показываем стандартный набор, чтобы кнопка
            // работала сразу, и пусть сервер отвечает — если что-то отклонит, придёт
            // обновление поста с реальным набором.
            for (String emoticon : ALL_REACTIONS) {
                result.add(emoji(emoticon));
            }
            return result;
        }
        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsAll) {
            for (String emoticon : ALL_REACTIONS) {
                result.add(emoji(emoticon));
            }
            return result;
        }
        if (chatFull.available_reactions instanceof TLRPC.TL_chatReactionsSome) {
            ArrayList<TLRPC.Reaction> reactions = ((TLRPC.TL_chatReactionsSome) chatFull.available_reactions).reactions;
            for (int i = 0; i < reactions.size(); i++) {
                TLRPC.Reaction reaction = reactions.get(i);
                if (reaction instanceof TL_reactionEmoji) {
                    result.add(emoji(((TL_reactionEmoji) reaction).emoticon));
                } else if (reaction instanceof TL_reactionCustomEmoji) {
                    ReactionsLayoutInBubble.VisibleReaction visible =
                            new ReactionsLayoutInBubble.VisibleReaction();
                    visible.documentId = ((TL_reactionCustomEmoji) reaction).document_id;
                    visible.hash = visible.documentId;
                    result.add(visible);
                }
            }
        }
        return result;
    }

    private static ReactionsLayoutInBubble.VisibleReaction emoji(String emoticon) {
        ReactionsLayoutInBubble.VisibleReaction visible = new ReactionsLayoutInBubble.VisibleReaction();
        visible.emojicon = emoticon;
        visible.hash = emoticon.hashCode();
        return visible;
    }

    /** Разрешено ли вообще реагировать: у канала должна быть включена реакция на посты. */
    static boolean canReact(int currentAccount, long dialogId) {
        TLRPC.Chat chat = MessagesController.getInstance(currentAccount).getChat(-dialogId);
        if (chat == null) {
            return false;
        }
        if (ChatObject.isChannelAndNotMegaGroup(chat)) {
            return true;
        }
        return !availableReactions(currentAccount, dialogId).isEmpty();
    }

    /** Короткая подпись реакции для подсказки: эмодзи или «кастомная». */
    static String describe(ReactionsLayoutInBubble.VisibleReaction reaction) {
        if (reaction == null) {
            return null;
        }
        return reaction.emojicon;
    }
}
