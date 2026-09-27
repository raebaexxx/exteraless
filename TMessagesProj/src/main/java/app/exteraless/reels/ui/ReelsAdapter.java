package app.exteraless.reels.ui;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.MessageObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.Components.Reactions.ReactionsLayoutInBubble;

import java.util.ArrayList;
import java.util.HashMap;

/**
 * Адаптер вертикальной ленты. Держит список роликов, который меняет
 * {@link app.exteraless.reels.ReelsController}, и нарезает на страницы.
 *
 * <p>Идентификаторы у страниц настоящие (диалог и id поста), а не позиции: при вставке
 * новых роликов сверху список меняется, и по позициям анимации переезжали бы на другие
 * видео.
 */
class ReelsAdapter extends RecyclerView.Adapter<ReelsAdapter.PageHolder> {

    private final ReelsPageView.Delegate delegate;
    private final int currentAccount;
    private final ArrayList<MessageObject> messages;
    private final HashMap<Long, TLRPC.Chat> chats = new HashMap<>();
    private final HashMap<Long, ReactionsLayoutInBubble.VisibleReaction> reactions = new HashMap<>();

    ReelsAdapter(int currentAccount, ReelsPageView.Delegate delegate) {
        this.currentAccount = currentAccount;
        this.delegate = delegate;
        this.messages = new ArrayList<>();
        setHasStableIds(true);
    }

    void setItems(ArrayList<MessageObject> newItems) {
        messages.clear();
        messages.addAll(newItems);
        notifyDataSetChanged();
    }

    void clear() {
        messages.clear();
        notifyDataSetChanged();
    }

    MessageObject getItem(int position) {
        if (position < 0 || position >= messages.size()) {
            return null;
        }
        return messages.get(position);
    }

    int getItemCountSafe() {
        return messages.size();
    }

    void setChat(long dialogId, TLRPC.Chat chat) {
        chats.put(dialogId, chat);
    }

    void setReaction(long dialogId, ReactionsLayoutInBubble.VisibleReaction reaction) {
        reactions.put(dialogId, reaction);
    }

    @Override
    public long getItemId(int position) {
        MessageObject message = getItem(position);
        if (message == null) {
            return position;
        }
        return message.getDialogId() * 1000003L + message.getRealId();
    }

    @NonNull
    @Override
    public PageHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new PageHolder(new ReelsPageView(parent.getContext(), currentAccount, delegate));
    }

    @Override
    public void onBindViewHolder(@NonNull PageHolder holder, int position) {
        holder.bind(getItem(position));
    }

    @Override
    public void onViewRecycled(@NonNull PageHolder holder) {
        holder.recycle();
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    class PageHolder extends RecyclerView.ViewHolder {

        private final ReelsPageView page;

        PageHolder(ReelsPageView page) {
            super(page);
            this.page = page;
        }

        void bind(MessageObject message) {
            if (message == null) {
                return;
            }
            long dialogId = message.getDialogId();
            page.bind(message, chats.get(dialogId), reactions.get(dialogId));
            page.setActive(false);
        }

        void recycle() {
            page.hideMedia();
        }
    }
}
