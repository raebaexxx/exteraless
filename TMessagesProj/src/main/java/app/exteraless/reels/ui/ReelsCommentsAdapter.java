package app.exteraless.reels.ui;

import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.MessageObject;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

/**
 * Список комментариев в шторке.
 *
 * <p>Данные живут в {@link app.exteraless.reels.ReelsCommentsController}: сколько реакций
 * на комментарии и поставил ли её пользователь знает только он, агадаптер спрашивает
 * прямо оттуда и не держит копию, которая разошлась бы с источником.
 */
class ReelsCommentsAdapter extends RecyclerView.Adapter<ReelsCommentsAdapter.Holder> {

    interface Delegate {
        void onReactionClick(MessageObject comment);
    }


    private final Delegate delegate;
    private final app.exteraless.reels.ReelsCommentsController controller;
    private final ArrayList<MessageObject> comments;
    private String reaction = "👍";

    ReelsCommentsAdapter(Delegate delegate, app.exteraless.reels.ReelsCommentsController controller) {
        this.delegate = delegate;
        this.controller = controller;
        this.comments = controller.getComments();
    }

    void setReaction(String reaction) {
        this.reaction = reaction;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ReelsCommentCell cell = new ReelsCommentCell(parent.getContext(),
                comment -> delegate.onReactionClick(comment));
        cell.setLayoutParams(new FrameLayout.LayoutParams(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        return new Holder(cell);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        MessageObject comment = comments.get(position);
        holder.cell.setComment(comment, reaction,
                controller.getReactionCount(comment), controller.isMyReactionSet(comment));
    }

    @Override
    public int getItemCount() {
        return comments.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final ReelsCommentCell cell;

        Holder(ReelsCommentCell cell) {
            super(cell);
            this.cell = cell;
        }
    }
}
