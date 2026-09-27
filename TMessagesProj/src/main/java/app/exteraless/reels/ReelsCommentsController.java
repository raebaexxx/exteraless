package app.exteraless.reels;

import android.util.SparseArray;

import org.telegram.messenger.ChatObject;
import org.telegram.messenger.MessageObject;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.SendMessagesHelper;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;

/**
 * Ветка комментариев под постом: адрес ветки, страницы комментариев, отправка и реакции.
 *
 * <p>Адреса ветки у поста нет — Telegram хранит только счётчик и идентификатор привязанной
 * группы, а корень обсуждения отдаёт отдельным запросом {@code messages.getDiscussionMessage}.
 * Дальше комментарии читаются обычным {@code messages.getReplies} от этого корня, то есть
 * ровно тем же способом, каким клиент открывает обсуждение под постом канала.
 *
 * <p>Ветка принадлежит не экрану, а посту: контроллер живёт, пока открыта шторка комментариев,
 * и переживает смену страницы ленты под ней.
 */
public class ReelsCommentsController implements NotificationCenter.NotificationCenterDelegate {

    public interface Callback {
        /** Корень ветки найден: можно показывать список и поле ввода. */
        void onThreadReady(TLRPC.Chat chat, MessageObject root, int totalCount);

        /**
         * Список изменился. {@code firstPage} — пришла первая страница: тогда список надо
         * прокрутить вниз, при подгрузке старых позицию пользователя трогать нельзя.
         */
        void onCommentsUpdated(boolean firstPage);

        /** Наш собственный комментарий подтверждён сервером. */
        void onCommentReceived(MessageObject comment);

        /** Ветка не нашлась: показывать нечего. */
        void onThreadFailed();

        /** Комментарий не отправился. */
        void onSendFailed();
    }

    private static final int PAGE_LIMIT = 30;

    private final int account;
    private final Callback callback;

    private final ArrayList<MessageObject> comments = new ArrayList<>();
    /** Отметка «реакцию поставил я»: сервер вернёт её позже, а кнопке нужна сразу. */
    private final SparseArray<Boolean> myReactions = new SparseArray<>();

    private int rootRequestId;
    private int pageRequestId;
    private boolean loadingMore;
    private boolean endReached;
    private boolean observing;

    private long dialogId;
    private TLRPC.Peer threadPeer;
    private int rootId;
    private MessageObject root;
    private TLRPC.Chat chat;
    private int totalCount;

    public ReelsCommentsController(int account, Callback callback) {
        this.account = account;
        this.callback = callback;
    }

    public ArrayList<MessageObject> getComments() {
        return comments;
    }

    public TLRPC.Chat getChat() {
        return chat;
    }

    public boolean isThreadReady() {
        return root != null;
    }

    /** Можно ли писать в ветку: в группе обсуждений и в канале с комментариями. */
    public boolean canSend() {
        if (root == null) {
            return false;
        }
        if (chat == null) {
            // Чата обсуждений в кэше нет: прав проверить нечем. Разрешаем и полагаемся на
            // ответ сервера — при отказе вылезет плашка, а не исчезнет поле ввода.
            return true;
        }
        return ChatObject.canSendMessages(chat);
    }

    public boolean isMyReactionSet(MessageObject comment) {
        Boolean value = comment == null ? null : myReactions.get(comment.getId());
        return value != null && value;
    }

    public int getReactionCount(MessageObject comment) {
        if (comment == null || comment.messageOwner == null || comment.messageOwner.reactions == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < comment.messageOwner.reactions.results.size(); i++) {
            count += comment.messageOwner.reactions.results.get(i).count;
        }
        return count;
    }

    /** Находит ветку поста и читает первую страницу комментариев. */
    public void start(MessageObject post) {
        if (post == null || post.messageOwner == null || post.messageOwner.replies == null) {
            callback.onThreadFailed();
            return;
        }
        final long channelId = -post.getDialogId();
        TLRPC.Chat postChat = MessagesController.getInstance(account).getChat(channelId);
        if (postChat == null) {
            callback.onThreadFailed();
            return;
        }
        TLRPC.TL_messages_getDiscussionMessage request = new TLRPC.TL_messages_getDiscussionMessage();
        request.peer = MessagesController.getInputPeer(postChat);
        request.msg_id = post.getRealId();
        rootRequestId = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                NotificationCenter.getInstance(account).doOnIdle(() -> onRootResponse(post, response, error)));
        startObserving();
    }

    private void onRootResponse(MessageObject post, Object response, TLRPC.TL_error error) {
        rootRequestId = 0;
        if (!(response instanceof TLRPC.TL_messages_discussionMessage)) {
            callback.onThreadFailed();
            return;
        }
        TLRPC.TL_messages_discussionMessage discussion = (TLRPC.TL_messages_discussionMessage) response;
        MessagesController.getInstance(account).putUsers(discussion.users, false);
        MessagesController.getInstance(account).putChats(discussion.chats, false);
        for (int i = 0; i < discussion.messages.size(); i++) {
            TLRPC.Message message = discussion.messages.get(i);
            if (message instanceof TLRPC.TL_messageEmpty || message.action != null) {
                continue;
            }
            long candidate = MessageObject.getDialogId(message);
            if (candidate == 0 || candidate == -post.getDialogId()) {
                // Корень лежит в группе обсуждений; пост самого канала не подходит.
                continue;
            }
            dialogId = candidate;
            threadPeer = message.peer_id;
            rootId = message.id;
            root = new MessageObject(account, message, true, false);
            break;
        }
        if (root == null) {
            callback.onThreadFailed();
            return;
        }
        // getChat ждёт идентификатор чата (положительный), а getDialogId отдаёт
        // идентификатор диалога (у канала он отрицательный). Со знаком минус.
        chat = MessagesController.getInstance(account).getChat(-dialogId);
        if (chat == null) {
            chat = findChat(discussion.chats, -dialogId);
        }
        totalCount = post.getRepliesCount();
        callback.onThreadReady(chat, root, totalCount);
        loadFirstPage();
    }

    /**
     * Адрес ветки для запросов. Строим его из чата, а если чата в кэше нет — из пира
     * корневого сообщения: {@code getInputPeer(TLRPC.Chat)} падает на null, и ровно это
     * валило экран, когда сервер не присылал описание группы обсуждений.
     */
    private TLRPC.InputPeer threadInput() {
        if (chat != null) {
            return MessagesController.getInputPeer(chat);
        }
        if (threadPeer == null) {
            return null;
        }
        return MessagesController.getInstance(account).getInputPeer(threadPeer);
    }

    private static TLRPC.Chat findChat(ArrayList<TLRPC.Chat> chats, long id) {
        if (chats == null) {
            return null;
        }
        for (int i = 0; i < chats.size(); i++) {
            if (chats.get(i) != null && chats.get(i).id == id) {
                return chats.get(i);
            }
        }
        return null;
    }

    private void loadFirstPage() {
        if (pageRequestId != 0) {
            return;
        }
        TLRPC.InputPeer peer = threadInput();
        if (peer == null) {
            callback.onThreadFailed();
            return;
        }
        TLRPC.TL_messages_getReplies request = new TLRPC.TL_messages_getReplies();
        request.peer = peer;
        request.msg_id = rootId;
        // offset_id = 1 с отрицательным add_offset — способ запросить самые новые.
        request.offset_id = 1;
        request.add_offset = -PAGE_LIMIT;
        request.limit = PAGE_LIMIT;
        pageRequestId = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                NotificationCenter.getInstance(account).doOnIdle(() -> onPageResponse(response, true)));
    }

    /** Более старые комментарии: в Reels список подгружается вверх, к началу ветки. */
    public void loadMore() {
        if (loadingMore || endReached || pageRequestId != 0 || comments.isEmpty()) {
            return;
        }
        TLRPC.InputPeer peer = threadInput();
        if (peer == null) {
            loadingMore = false;
            return;
        }
        loadingMore = true;
        TLRPC.TL_messages_getReplies request = new TLRPC.TL_messages_getReplies();
        request.peer = peer;
        request.msg_id = rootId;
        request.offset_id = comments.get(0).getId();
        request.add_offset = 0;
        request.limit = PAGE_LIMIT;
        pageRequestId = ConnectionsManager.getInstance(account).sendRequest(request, (response, error) ->
                NotificationCenter.getInstance(account).doOnIdle(() -> onPageResponse(response, false)));
    }

    private void onPageResponse(Object response, boolean firstPage) {
        pageRequestId = 0;
        loadingMore = false;
        if (!(response instanceof TLRPC.messages_Messages)) {
            if (firstPage) {
                callback.onCommentsUpdated(true);
            }
            return;
        }
        TLRPC.messages_Messages messages = (TLRPC.messages_Messages) response;
        MessagesController.getInstance(account).putUsers(messages.users, false);
        MessagesController.getInstance(account).putChats(messages.chats, false);

        // Сервер отдаёт ветку от новых к старым, а список вверху должен быть таким же.
        ArrayList<MessageObject> block = new ArrayList<>(messages.messages.size());
        for (int i = 0; i < messages.messages.size(); i++) {
            TLRPC.Message message = messages.messages.get(i);
            if (message instanceof TLRPC.TL_messageEmpty || message.action != null) {
                // Служебные сообщения («обсуждение начато») в списке комментариев лишние.
                continue;
            }
            block.add(new MessageObject(account, message, true, false));
        }
        if (block.isEmpty()) {
            endReached = true;
            if (firstPage) {
                callback.onCommentsUpdated(true);
            }
            return;
        }
        if (firstPage) {
            comments.clear();
        }
        for (int i = block.size() - 1; i >= 0; i--) {
            MessageObject comment = block.get(i);
            if (firstPage) {
                comments.add(comment);
            } else if (!containsId(comment.getId())) {
                comments.add(0, comment);
            }
        }
        if (messages.messages.size() < PAGE_LIMIT) {
            endReached = true;
        }
        callback.onCommentsUpdated(firstPage);
    }

    private boolean containsId(int id) {
        for (int i = 0; i < comments.size(); i++) {
            if (comments.get(i).getId() == id) {
                return true;
            }
        }
        return false;
    }

    /** Отправляет комментарий в ветку поста. */
    public void send(SendMessagesHelper helper, String text) {
        if (root == null || text == null || text.trim().isEmpty()) {
            return;
        }
        SendMessagesHelper.SendMessageParams params = SendMessagesHelper.SendMessageParams.of(
                text.trim(), dialogId, root, null, null, true, null, null, null, true, 0, 0, null, false);
        helper.sendMessage(params);
    }

    /**
     * Ставит или снимает реакцию на комментарии. Счётчик и отметка «моя» меняются сразу,
     * серверный ответ их только подтвердит.
     */
    public void react(SendMessagesHelper helper, BaseFragment parent, MessageObject comment,
                      ReactionsLayoutInBubble.VisibleReaction reaction) {
        if (comment == null || reaction == null) {
            return;
        }
        boolean chosen = isMyReactionSet(comment);
        ArrayList<ReactionsLayoutInBubble.VisibleReaction> visible = new ArrayList<>(1);
        visible.add(reaction);
        // addToRecent = false: одиночная кнопка не должна засорять список реакций.
        helper.sendReaction(comment, visible, chosen ? null : reaction, false, false, parent, null);
        myReactions.put(comment.getId(), !chosen);
        bumpReaction(comment, reaction, chosen ? -1 : 1);
    }

    /**
     * Меняет счётчик реакции на комментарии сразу, не дожидаясь сервера.
     *
     * <p>Счётчика может не быть вовсе: {@code messages.getReplies} не обязан присылать
     * реакции, и тогда кнопка выглядела бы мёртвой. В этом случае заводим счётчик с
     * нуля — обновление от сервера его потом перезапишет.
     */
    private void bumpReaction(MessageObject comment, ReactionsLayoutInBubble.VisibleReaction reaction, int delta) {
        if (comment.messageOwner == null) {
            return;
        }
        if (comment.messageOwner.reactions == null) {
            comment.messageOwner.reactions = new TLRPC.TL_messageReactions();
        }
        ArrayList<TLRPC.ReactionCount> results = comment.messageOwner.reactions.results;
        for (int i = 0; i < results.size(); i++) {
            TLRPC.ReactionCount count = results.get(i);
            if (matches(reaction, count.reaction)) {
                count.count = Math.max(0, count.count + delta);
                count.chosen = delta > 0;
                return;
            }
        }
        if (delta <= 0) {
            return;
        }
        TLRPC.ReactionCount count = new TLRPC.TL_reactionCount();
        count.count = 1;
        count.chosen = true;
        if (reaction.documentId != 0) {
            TLRPC.TL_reactionCustomEmoji custom = new TLRPC.TL_reactionCustomEmoji();
            custom.document_id = reaction.documentId;
            count.reaction = custom;
        } else {
            TLRPC.TL_reactionEmoji emoji = new TLRPC.TL_reactionEmoji();
            emoji.emoticon = reaction.emojicon;
            count.reaction = emoji;
        }
        results.add(count);
    }

    static boolean matches(ReactionsLayoutInBubble.VisibleReaction visible, TLRPC.Reaction reaction) {
        if (visible == null || reaction == null) {
            return false;
        }
        if (visible.emojicon != null && reaction instanceof TLRPC.TL_reactionEmoji) {
            return visible.emojicon.equals(((TLRPC.TL_reactionEmoji) reaction).emoticon);
        }
        return visible.documentId != 0 && reaction instanceof TLRPC.TL_reactionCustomEmoji
                && visible.documentId == ((TLRPC.TL_reactionCustomEmoji) reaction).document_id;
    }

    // ---- подтверждение отправки ----

    private void startObserving() {
        if (observing) {
            return;
        }
        observing = true;
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messageReceivedByServer);
        NotificationCenter.getInstance(account).addObserver(this, NotificationCenter.messageSendError);
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (root == null || account != this.account) {
            return;
        }
        if (id == NotificationCenter.messageReceivedByServer) {
            if (args.length < 4 || !(args[3] instanceof Long) || (Long) args[3] != dialogId) {
                return;
            }
            if (!(args[2] instanceof MessageObject)) {
                return;
            }
            MessageObject comment = (MessageObject) args[2];
            // В группе обсуждений есть и чужие сообщения — берём только ветку поста.
            if (comment.messageOwner == null || comment.messageOwner.reply_to == null
                    || comment.messageOwner.reply_to.reply_to_msg_id != rootId) {
                return;
            }
            myReactions.remove(comment.getId());
            if (!containsId(comment.getId())) {
                comments.add(comment);
                callback.onCommentReceived(comment);
            }
        } else if (id == NotificationCenter.messageSendError) {
            callback.onSendFailed();
        }
    }

    public void cancel() {
        stopObserving();
        ConnectionsManager connections = ConnectionsManager.getInstance(account);
        if (rootRequestId != 0) {
            connections.cancelRequest(rootRequestId, false);
            rootRequestId = 0;
        }
        if (pageRequestId != 0) {
            connections.cancelRequest(pageRequestId, false);
            pageRequestId = 0;
        }
    }

    private void stopObserving() {
        if (!observing) {
            return;
        }
        observing = false;
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messageReceivedByServer);
        NotificationCenter.getInstance(account).removeObserver(this, NotificationCenter.messageSendError);
    }
}
