package app.exteraless.reels;

import org.telegram.messenger.MessageObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

/**
 * Окно загруженных роликов: упорядоченный список (от новых к старым), карта
 * «диалог → идентификаторы» для дедупликации и курсоры пагинации.
 *
 * <p>В отличие от ленты каналов синтетических идентификаторов нет — страница «Клипов»
 * показывает посты как есть, поэтому достаточно настоящей пары (диалог, id).
 */
final class ReelsStore {

    private final ArrayList<MessageObject> messages = new ArrayList<>();
    private final HashMap<Long, HashSet<Integer>> loadedIdsByDialog = new HashMap<>();
    private final ReelsTimelineLoader.Cursor oldestCursor = new ReelsTimelineLoader.Cursor();
    private final ReelsTimelineLoader.Cursor newestCursor = new ReelsTimelineLoader.Cursor();

    private boolean endReached;

    private static boolean isKnown(HashMap<Long, HashSet<Integer>> map, long dialogId, int messageId) {
        HashSet<Integer> ids = map.get(dialogId);
        return ids != null && ids.contains(messageId);
    }

    private static void remember(HashMap<Long, HashSet<Integer>> map, long dialogId, int messageId) {
        HashSet<Integer> ids = map.get(dialogId);
        if (ids == null) {
            ids = new HashSet<>();
            map.put(dialogId, ids);
        }
        ids.add(messageId);
    }

    private static void forget(HashMap<Long, HashSet<Integer>> map, long dialogId, int messageId) {
        HashSet<Integer> ids = map.get(dialogId);
        if (ids != null) {
            ids.remove(messageId);
            if (ids.isEmpty()) {
                map.remove(dialogId);
            }
        }
    }

    /**
     * Порядок роликов: сначала по дате, затем по диалогу, затем по идентификатору.
     * Возвращает положительное число, если первая тройка новее второй.
     */
    static int compareTimeline(int date, long uid, int mid, int otherDate, long otherUid, int otherMid) {
        if (date != otherDate) {
            return Integer.compare(date, otherDate);
        }
        if (uid != otherUid) {
            return Long.compare(uid, otherUid);
        }
        return Integer.compare(mid, otherMid);
    }

    private static int compare(MessageObject first, MessageObject second) {
        return compareTimeline(first.messageOwner.date, first.getDialogId(), first.getRealId(),
                second.messageOwner.date, second.getDialogId(), second.getRealId());
    }

    private int findInsertIndex(MessageObject message) {
        int index = 0;
        while (index < messages.size() && compare(messages.get(index), message) >= 0) {
            index++;
        }
        return index;
    }

    /**
     * Добавляет страницу роликов, сохраняя порядок. Возвращает только те объекты,
     * которые действительно попали в ленту, — по ним UI и считает вставку.
     */
    ArrayList<MessageObject> merge(ArrayList<MessageObject> incoming, boolean atStart) {
        if (incoming == null || incoming.isEmpty()) {
            return new ArrayList<>();
        }
        ArrayList<MessageObject> accepted = new ArrayList<>(incoming.size());
        for (MessageObject message : incoming) {
            if (message == null || message.messageOwner == null) {
                continue;
            }
            if (!isKnown(loadedIdsByDialog, message.getDialogId(), message.getRealId())) {
                remember(loadedIdsByDialog, message.getDialogId(), message.getRealId());
                accepted.add(message);
            }
        }
        if (accepted.isEmpty()) {
            return accepted;
        }
        // Страница «новее курсора» приходит по возрастанию даты, а хранится по убыванию.
        if (atStart) {
            for (int i = accepted.size() - 1; i >= 0; i--) {
                messages.add(0, accepted.get(i));
            }
        } else {
            for (int i = 0; i < accepted.size(); i++) {
                messages.add(findInsertIndex(accepted.get(i)), accepted.get(i));
            }
        }
        return accepted;
    }

    void clear() {
        messages.clear();
        loadedIdsByDialog.clear();
        endReached = false;
        oldestCursor.set(0, 0L, 0);
        newestCursor.set(0, 0L, 0);
    }

    /**
     * Убирает из окна все ролики каналов, которых больше нет в выдаче: пользователь
     * снял галочку с канала или канал вышел из подходящих. Возвращает удалённые посты.
     */
    ArrayList<MessageObject> removeDialogsExcept(HashSet<Long> includedDialogIds) {
        ArrayList<MessageObject> removed = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject message = messages.get(i);
            if (!includedDialogIds.contains(message.getDialogId())) {
                messages.remove(i);
                forget(loadedIdsByDialog, message.getDialogId(), message.getRealId());
                removed.add(0, message);
            }
        }
        if (!removed.isEmpty()) {
            rebuildCursors();
        }
        return removed;
    }

    /**
     * Убирает из ленты историю канала до указанного идентификатора включительно.
     * В {@code changed[0]} кладёт признак того, что лента изменилась.
     */
    ArrayList<MessageObject> deleteHistory(long dialogId, int maxId, boolean[] changed) {
        ArrayList<MessageObject> removed = new ArrayList<>();
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject message = messages.get(i);
            if (message.getDialogId() == dialogId && message.getRealId() > 0 && message.getRealId() <= maxId) {
                messages.remove(i);
                forget(loadedIdsByDialog, dialogId, message.getRealId());
                removed.add(message);
            }
        }
        changed[0] = !removed.isEmpty();
        if (changed[0]) {
            rebuildCursors();
        }
        return removed;
    }

    /** Убирает из ленты конкретные посты канала. В {@code changed[0]} — признак изменения. */
    ArrayList<MessageObject> deleteMessages(long dialogId, ArrayList<Integer> messageIds, boolean[] changed) {
        ArrayList<MessageObject> removed = new ArrayList<>();
        changed[0] = false;
        if (messageIds == null || messageIds.isEmpty()) {
            return removed;
        }
        HashSet<Integer> targetIds = new HashSet<>(messageIds);
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject message = messages.get(i);
            if (message.getDialogId() == dialogId && targetIds.contains(message.getRealId())) {
                messages.remove(i);
                forget(loadedIdsByDialog, dialogId, message.getRealId());
                removed.add(message);
            }
        }
        changed[0] = !removed.isEmpty();
        if (changed[0]) {
            rebuildCursors();
        }
        return removed;
    }

    /** Пересобирает курсоры по оставшимся роликам — после удаления они могли уехать. */
    private void rebuildCursors() {
        int newestDate = 0, newestMid = 0, oldestDate = 0, oldestMid = 0;
        long newestUid = 0, oldestUid = 0;
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            int date = message.messageOwner.date;
            long uid = message.getDialogId();
            int mid = message.getRealId();
            if (newestDate == 0 || compareTimeline(date, uid, mid, newestDate, newestUid, newestMid) > 0) {
                newestDate = date;
                newestUid = uid;
                newestMid = mid;
            }
            if (oldestDate == 0 || compareTimeline(date, uid, mid, oldestDate, oldestUid, oldestMid) < 0) {
                oldestDate = date;
                oldestUid = uid;
                oldestMid = mid;
            }
        }
        if (newestDate == 0) {
            oldestCursor.set(0, 0L, 0);
            newestCursor.set(0, 0L, 0);
            return;
        }
        newestCursor.set(newestDate, newestUid, newestMid);
        oldestCursor.set(oldestDate, oldestUid, oldestMid);
    }

    MessageObject find(long dialogId, int messageId) {
        if (!isKnown(loadedIdsByDialog, dialogId, messageId)) {
            return null;
        }
        for (int i = 0; i < messages.size(); i++) {
            MessageObject message = messages.get(i);
            if (message.getDialogId() == dialogId && message.getRealId() == messageId) {
                return message;
            }
        }
        return null;
    }

    ArrayList<MessageObject> getMessages() {
        return messages;
    }

    int size() {
        return messages.size();
    }

    boolean isEmpty() {
        return messages.isEmpty();
    }

    ReelsTimelineLoader.Cursor getOldestCursor() {
        return oldestCursor;
    }

    ReelsTimelineLoader.Cursor getNewestCursor() {
        return newestCursor;
    }

    void setOldestCursor(int date, long uid, int mid) {
        oldestCursor.set(date, uid, mid);
    }

    void setNewestCursor(int date, long uid, int mid) {
        newestCursor.set(date, uid, mid);
    }

    boolean isEndReached() {
        return endReached;
    }

    void setEndReached(boolean endReached) {
        this.endReached = endReached;
    }

    HashSet<Long> getLoadedDialogIds() {
        return new HashSet<>(loadedIdsByDialog.keySet());
    }

    /**
     * Обрезает окно до указанного количества роликов, отбрасывая самые старые,
     * и сдвигает курсор дозагрузки. Возвращает true, если что-то удалено.
     */
    boolean trim(int limit) {
        if (messages.size() <= limit) {
            return false;
        }
        MessageObject boundary = messages.get(limit);
        int boundaryDate = boundary.messageOwner.date;
        long boundaryUid = boundary.getDialogId();
        int boundaryMid = boundary.getRealId();

        boolean removed = false;
        for (int i = messages.size() - 1; i >= 0; i--) {
            MessageObject message = messages.get(i);
            if (compareTimeline(message.messageOwner.date, message.getDialogId(), message.getRealId(),
                    boundaryDate, boundaryUid, boundaryMid) <= 0) {
                messages.remove(i);
                forget(loadedIdsByDialog, message.getDialogId(), message.getRealId());
                removed = true;
            }
        }
        if (!removed) {
            return false;
        }
        rebuildCursors();
        // Старые ролики могли быть последними, до которых дошла лента: доливать можно снова.
        endReached = false;
        return true;
    }
}
